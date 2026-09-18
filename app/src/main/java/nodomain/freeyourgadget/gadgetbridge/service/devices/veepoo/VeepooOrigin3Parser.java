/*  Copyright (C) 2025 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.veepoo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.devices.veepoo.VeepooConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodLipidSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GlucoseSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;

/**
 * Pure parser for fully assembled Origin3 (0xDF) day payloads: the raw frames of one requested day
 * in, five-minute activity records plus the per-record all-day vitals out, appended through the
 * given {@link VeepooSampleCollector}. No database access and no Android dependencies; the frame
 * accumulation, watchdogs and persistence stay in {@link VeepooDeviceSupport}.
 */
final class VeepooOrigin3Parser {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooOrigin3Parser.class);

    private VeepooOrigin3Parser() {
    }

    /**
     * Reassembles the per-block payload streams of one Origin3 day and parses each TLV stream.
     * Header fields: [4..5]=LE total blocks, [6]=packets per block, [10]=valid payload bytes,
     * [11..12]=LE CRC-16 of the block payload, [13]=single-packet length j (read once from the
     * first block header on v5; v3 uses 20).
     */
    public static boolean parseDay(List<byte[]> frames, int dayIndex, int protocolVersion, int bloodGlucoseTag,
                                   VeepooSampleCollector out) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        boolean inBlock = false;
        int packetLen = -1; // single-packet length j: read from the first block header, reused for the rest of the day
        int validBytes = -1;
        int checksum = -1;
        int records = 0;

        for (byte[] frame : frames) {
            if ((frame[3] & 0xFF) == 0) {
                records += flushOrigin3Block(stream, validBytes, checksum, dayIndex, bloodGlucoseTag, out);
                inBlock = true;
                if (packetLen < 0) {
                    packetLen = protocolVersion == VeepooConstants.ORIGIN_PROTOCOL_VERSION_DF
                            ? frame[13] & 0xFF : 20;
                    if (packetLen < 5) {
                        packetLen = 20;
                    }
                }
                validBytes = frame[10] & 0xFF;
                checksum = (frame[11] & 0xFF) | ((frame[12] & 0xFF) << 8);
                continue;
            }
            if (!inBlock) {
                LOG.debug("Dropping Origin3 payload frame before any block header (day {})", dayIndex);
                continue;
            }
            int copy = Math.min(packetLen - 4, frame.length - 4);
            stream.write(frame, 4, copy);
        }
        records += flushOrigin3Block(stream, validBytes, checksum, dayIndex, bloodGlucoseTag, out);
        return records > 0;
    }

    private static int flushOrigin3Block(ByteArrayOutputStream stream, int validBytes, int checksum, int dayIndex,
                                         int bloodGlucoseTag, VeepooSampleCollector out) {
        byte[] payload = stream.toByteArray();
        stream.reset();
        if (payload.length == 0) {
            return 0;
        }
        if (validBytes >= 0 && validBytes < payload.length) {
            payload = Arrays.copyOf(payload, validBytes);
        }
        if (checksum >= 0 && vpCrc16(payload) != checksum) {
            LOG.warn("Origin3 block for day {} failed its CRC-16 check; dropping", dayIndex);
            return 0;
        }
        return parseOrigin3TlvStream(payload, dayIndex, bloodGlucoseTag, out);
    }

    /**
     * Parses one Origin3 block stream. Each 0xB1 tag starts a new 5-minute record (hour [2],
     * minute [3]); fields accumulate until the next 0xB1 or the 0xC0 terminator. B2 (SportFiveMinuteData):
     * [0..1]=steps BE16, [2..3]=sport BE16, [4..5]=distance meters BE16, [6..7]=kcal tenths BE16,
     * [8]=pose, [9]=wear. B4 carries the PPG heart-rate array, B5 the ECG one (B4 wins for the
     * record when both are present). The all-day vitals tags are handled per tag and queued for
     * batch persistence: 0xB6 breath rate (1 byte/minute), 0xB8 blood pressure (systolic,
     * diastolic), 0xB9 SpO2 (up to five 1-byte percentages, one per minute) plus the trailing
     * apnea count / hypoxia time / cardiac load groups (aggregated onto the activity record),
     * 0xBE glucose (LE16,
     * 0.01 mmol/L steps), 0xBF MET (1 byte, /10; log-only), 0xC1 pressure/wear (log-only) and
     * 0xC2 lipids (TCHO/TAG/HDL/LDL as LE16 in 0.01 mmol/L steps + uric acid in mg/dL).
     */
    private static int parseOrigin3TlvStream(byte[] payload, int dayIndex, int bloodGlucoseTag, VeepooSampleCollector out) {
        Calendar base = Calendar.getInstance();
        base.add(Calendar.DAY_OF_MONTH, -dayIndex);

        boolean haveRecord = false;
        int hour = -1;
        int minute = -1;
        int steps = 0;
        int distanceMeters = 0;
        int kcalTenths = 0;
        int ppgHeartRate = ActivitySample.NOT_MEASURED;
        int ecgHeartRate = ActivitySample.NOT_MEASURED;
        int ppgHrRecords = 0;
        int ecgHrRecords = 0;
        int hrvRecords = 0;
        int hrvSamplesAdded = 0;
        // Aggregates of the 0xB9 apnea/hypoxia/cardiac-load groups (null until the tag is seen).
        Integer apneaCount = null;
        Integer hypoxiaMinutes = null;
        Integer hypoxiaTimeSum = null;
        Integer cardiacLoadMinutes = null;
        int recordsAdded = 0;

        int pos = 0;
        while (pos + 2 <= payload.length) {
            int tag = payload[pos] & 0xFF;
            if (tag == 0xC0) {
                break;
            }
            int length = payload[pos + 1] & 0xFF;
            if (pos + 2 + length > payload.length) {
                LOG.debug("Origin3 stream for day {} truncated at tag 0x{}", dayIndex, Integer.toHexString(tag));
                break;
            }
            byte[] body = Arrays.copyOfRange(payload, pos + 2, pos + 2 + length);

            if (tag == 0xB1) {
                if (flushOrigin3Record(base, dayIndex, haveRecord, hour, minute, steps, distanceMeters, kcalTenths,
                        ppgHeartRate, ecgHeartRate, apneaCount, hypoxiaMinutes, hypoxiaTimeSum, cardiacLoadMinutes, out)) {
                    recordsAdded++;
                }
                if (body.length == 4 && ((body[0] & 0xFF) | (body[1] & 0xFF)) != 0) {
                    haveRecord = true;
                    hour = body[2] & 0xFF;
                    minute = body[3] & 0xFF;
                } else {
                    haveRecord = false;
                }
                steps = 0;
                distanceMeters = 0;
                kcalTenths = 0;
                ppgHeartRate = ActivitySample.NOT_MEASURED;
                ecgHeartRate = ActivitySample.NOT_MEASURED;
                apneaCount = null;
                hypoxiaMinutes = null;
                hypoxiaTimeSum = null;
                cardiacLoadMinutes = null;
            } else if (haveRecord && tag == 0xB2 && body.length >= 10) {
                steps = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                distanceMeters = ((body[4] & 0xFF) << 8) | (body[5] & 0xFF);
                kcalTenths = ((body[6] & 0xFF) << 8) | (body[7] & 0xFF);
            } else if (haveRecord && tag == 0xB4) {
                int average = averageValidHeartRate(body);
                if (average > 0) {
                    ppgHeartRate = average;
                    ppgHrRecords++;
                }
            } else if (haveRecord && tag == 0xB5) {
                int average = averageValidHeartRate(body);
                if (average > 0) {
                    ecgHeartRate = average;
                    ecgHrRecords++;
                }
            } else if (haveRecord && tag == 0xB7) {
                LOG.debug("Origin3 day {} HRV tag at {}:{} len={} body={}",
                        dayIndex, hour, minute, body.length, VeepooDeviceSupport.hexBytes(body));
                if (hour <= 23 && minute <= 59 && body.length >= 51) {
                    Calendar stamp = (Calendar) base.clone();
                    stamp.set(Calendar.HOUR_OF_DAY, hour);
                    stamp.set(Calendar.MINUTE, minute);
                    stamp.set(Calendar.SECOND, 0);
                    stamp.set(Calendar.MILLISECOND, 0);
                    if (stamp.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) {
                        hrvRecords++;
                        hrvSamplesAdded += parseHrvFiveMinuteRecord(stamp, body, dayIndex, out);
                    }
                }
            } else if (haveRecord && tag == 0xB6 && hour <= 23 && minute <= 59) {
                // Breath rate: one byte per minute (breaths/min).
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                for (int m = 0; stamp != null && m < body.length && m < 5; m++) {
                    int breaths = body[m] & 0xFF;
                    if (breaths >= 5 && breaths <= 100) {
                        GenericRespiratoryRateSample sample = new GenericRespiratoryRateSample();
                        sample.setTimestamp(stamp.getTimeInMillis() + m * 60_000L);
                        sample.setRespiratoryRate(breaths);
                        out.addBreathSample(sample);
                    }
                }
            } else if (haveRecord && tag == 0xB8 && hour <= 23 && minute <= 59 && body.length >= 2) {
                // Blood pressure: systolic then diastolic, one reading per record.
                int systolic = body[0] & 0xFF;
                int diastolic = body[1] & 0xFF;
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && systolic >= 50 && systolic <= 250
                        && diastolic >= 30 && diastolic <= 150) {
                    GenericBloodPressureSample sample = new GenericBloodPressureSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setBpSystolic(systolic);
                    sample.setBpDiastolic(diastolic);
                    sample.setMeanArterialPressure((systolic + 2 * diastolic) / 3);
                    out.addBpSample(sample);
                } else if (stamp != null) {
                    LOG.debug("Ignoring implausible Origin3 BP at {}:{}: {}/{}", hour, minute, systolic, diastolic);
                }
            } else if (haveRecord && tag == 0xB9 && hour <= 23 && minute <= 59) {
                // SpO2: up to five 1-byte percentages, one per minute.
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                for (int m = 0; stamp != null && m < body.length && m < 5; m++) {
                    int spo2 = body[m] & 0xFF;
                    if (spo2 > 0 && spo2 <= 100) {
                        GenericSpo2Sample sample = new GenericSpo2Sample();
                        sample.setTimestamp(stamp.getTimeInMillis() + m * 60_000L);
                        sample.setSpo2(spo2);
                        out.addSpo2Sample(sample);
                    }
                }
                // Apnea count [5..9], hypoxia time [15..19] and cardiac load [20..24]: five 1-byte
                // values per group, one per minute. Aggregated like the vendor app: apnea events are
                // summed; a hypoxia-time or cardiac-load byte is counted only when plausible.
                if (body.length > 5) {
                    int recordApnea = 0;
                    int recordHypoxiaMinutes = 0;
                    int recordHypoxiaTimeSum = 0;
                    int recordCardiacLoadMinutes = 0;
                    for (int m = 0; m < 5; m++) {
                        if (body.length > 5 + m) {
                            int apnea = body[5 + m] & 0xFF;
                            if (apnea > 0) {
                                recordApnea += apnea;
                            }
                        }
                        if (body.length > 15 + m) {
                            int hypoxia = body[15 + m] & 0xFF;
                            if (hypoxia >= 1 && hypoxia <= 60) {
                                recordHypoxiaMinutes++;
                                recordHypoxiaTimeSum += hypoxia;
                            }
                        }
                        if (body.length > 20 + m) {
                            int cardiacLoad = body[20 + m] & 0xFF;
                            if (cardiacLoad >= 1 && cardiacLoad <= 100) {
                                recordCardiacLoadMinutes++;
                            }
                        }
                    }
                    apneaCount = recordApnea;
                    hypoxiaMinutes = recordHypoxiaMinutes;
                    hypoxiaTimeSum = recordHypoxiaTimeSum;
                    cardiacLoadMinutes = recordCardiacLoadMinutes;
                    if (recordApnea == 0 && recordHypoxiaMinutes == 0 && recordCardiacLoadMinutes == 0) {
                        LOG.debug("Origin3 day {} 0xB9 at {}:{}: apnea/hypoxia/cardiac groups present ({} bytes) but all zero",
                                dayIndex, hour, minute, body.length);
                    }
                }
            } else if (haveRecord && tag == 0xBE && hour <= 23 && minute <= 59 && body.length >= 2) {
                // Blood glucose: LE16 in 0.01 mmol/L steps (the vendor app stores value/100 as mmol/L).
                int raw = ((body[1] & 0xFF) << 8) | (body[0] & 0xFF);
                if (bloodGlucoseTag == 5) {
                    // Tag-5 encoding packs the risk level into the top three bits.
                    raw &= 0x1FFF;
                }
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && raw > 0) {
                    GlucoseSample sample = new GlucoseSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setValueMgDl(raw * 0.18); // GB stores mg/dL; the wire value is 0.01 mmol/L
                    out.addGlucoseSample(sample);
                }
            } else if (haveRecord && tag == 0xBF && hour <= 23 && minute <= 59 && body.length >= 1) {
                // MET (metabolic equivalent): one byte, value = byte/10. VeepooActivitySample has no
                // MET field, so the value is logged only.
                LOG.debug("Origin3 day {} MET at {}:{}: {}", dayIndex, hour, minute, (body[0] & 0xFF) / 10.0);
            } else if (haveRecord && tag == 0xC1 && hour <= 23 && minute <= 59 && body.length >= 1) {
                // Per-record stress ("pressure") index, one byte per five-minute record; the vendor
                // stores this byte and charts it as the all-day stress series.
                int stress = body[0] & 0xFF;
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && stress <= 100) {
                    GenericStressSample sample = new GenericStressSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setStress(stress);
                    out.addStressSample(sample);
                } else if (stamp != null) {
                    LOG.debug("Ignoring implausible Origin3 stress at {}:{}: {}", hour, minute, stress);
                }
            } else if (haveRecord && tag == 0xC2 && hour <= 23 && minute <= 59 && body.length >= 10) {
                // Lipids: TCHO/TAG/HDL/LDL as LE16 in 0.01 mmol/L steps plus uric acid as LE16 mg/dL.
                int tcho = ((body[1] & 0xFF) << 8) | (body[0] & 0xFF);
                int trig = ((body[3] & 0xFF) << 8) | (body[2] & 0xFF);
                int hdl = ((body[5] & 0xFF) << 8) | (body[4] & 0xFF);
                int ldl = ((body[7] & 0xFF) << 8) | (body[6] & 0xFF);
                int uricAcid = ((body[9] & 0xFF) << 8) | (body[8] & 0xFF);
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && tcho > 0 && trig > 0 && hdl > 0 && ldl > 0 && uricAcid > 0) {
                    GenericBloodLipidSample sample = new GenericBloodLipidSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setTotalCholesterol(tcho / 100.0f);
                    sample.setTriglyceride(trig / 100.0f);
                    sample.setHdl(hdl / 100.0f);
                    sample.setLdl(ldl / 100.0f);
                    sample.setUricAcid(uricAcid / 10.0f);
                    out.addLipidSample(sample);
                    LOG.debug("Origin3 day {} lipids at {}:{}: tcho={} trig={} hdl={} ldl={} uricAcid={}",
                            dayIndex, hour, minute, tcho / 100.0f, trig / 100.0f, hdl / 100.0f,
                            ldl / 100.0f, uricAcid / 10.0f);
                } else if (stamp != null) {
                    LOG.debug("Ignoring empty Origin3 lipid record at {}:{}: tcho={} trig={} hdl={} ldl={} uricAcid={}",
                            dayIndex, hour, minute, tcho, trig, hdl, ldl, uricAcid);
                }
            } else if (haveRecord && tag == 0xC3 && hour <= 23 && minute <= 59 && body.length >= 4) {
                // Wrist skin temperature: LE16 base and measured values, each in tenths of a
                // degree; the vendor drops the record when either value is outside 120..480
                // (12.0..48.0 C). The on-demand 0x87 measurement stays TYPE_BODY.
                int baseTenths = ((body[1] & 0xFF) << 8) | (body[0] & 0xFF);
                int measuredTenths = ((body[3] & 0xFF) << 8) | (body[2] & 0xFF);
                Calendar stamp = origin3RecordStamp(base, hour, minute);
                if (stamp != null && baseTenths >= 120 && baseTenths <= 480
                        && measuredTenths >= 120 && measuredTenths <= 480) {
                    GenericTemperatureSample sample = new GenericTemperatureSample();
                    sample.setTimestamp(stamp.getTimeInMillis());
                    sample.setTemperature(measuredTenths / 10.0f);
                    sample.setTemperatureType(TemperatureSample.TYPE_SKIN);
                    sample.setTemperatureLocation(TemperatureSample.LOCATION_WRIST);
                    out.addTemperatureSample(sample);
                }
            }
            pos += 2 + length;
        }
        if (flushOrigin3Record(base, dayIndex, haveRecord, hour, minute, steps, distanceMeters, kcalTenths,
                ppgHeartRate, ecgHeartRate, apneaCount, hypoxiaMinutes, hypoxiaTimeSum, cardiacLoadMinutes, out)) {
            recordsAdded++;
        }
        LOG.debug("Origin3 stream for day {}: {} five-minute records", dayIndex, recordsAdded);
        LOG.info("Origin3 day {}: heart rate in {} PPG / {} ECG of {} five-minute records; HRV tags {} ({} minute samples)",
                dayIndex, ppgHrRecords, ecgHrRecords, recordsAdded, hrvRecords, hrvSamplesAdded);
        return recordsAdded;
    }

    /**
     * Record start time for hour/minute within {@code base}, or null when the record lies in the
     * future (the band clock can run ahead of the phone).
     */
    private static Calendar origin3RecordStamp(Calendar base, int hour, int minute) {
        Calendar stamp = (Calendar) base.clone();
        stamp.set(Calendar.HOUR_OF_DAY, hour);
        stamp.set(Calendar.MINUTE, minute);
        stamp.set(Calendar.SECOND, 0);
        stamp.set(Calendar.MILLISECOND, 0);
        if (stamp.getTimeInMillis() > System.currentTimeMillis() + 60_000L) {
            return null;
        }
        return stamp;
    }

    private static boolean flushOrigin3Record(Calendar base, int dayIndex, boolean valid, int hour, int minute,
                                              int steps, int distanceMeters, int kcalTenths,
                                              int ppgHeartRate, int ecgHeartRate,
                                              Integer apneaCount, Integer hypoxiaMinutes,
                                              Integer hypoxiaTimeSum, Integer cardiacLoadMinutes,
                                              VeepooSampleCollector out) {
        if (!valid || hour > 23 || minute > 59) {
            return false;
        }
        Calendar stamp = (Calendar) base.clone();
        stamp.set(Calendar.HOUR_OF_DAY, hour);
        stamp.set(Calendar.MINUTE, minute);
        stamp.set(Calendar.SECOND, 0);
        stamp.set(Calendar.MILLISECOND, 0);
        if (stamp.getTimeInMillis() > System.currentTimeMillis() + 60_000L) {
            LOG.debug("Skipping Origin3 record for day {} with a future timestamp ({})", dayIndex, stamp);
            return false;
        }
        // Prefer the PPG (0xB4) average when both it and the ECG (0xB5) array were present.
        int heartRate = ppgHeartRate != ActivitySample.NOT_MEASURED ? ppgHeartRate : ecgHeartRate;
        if (ppgHeartRate != ActivitySample.NOT_MEASURED && ecgHeartRate != ActivitySample.NOT_MEASURED) {
            LOG.debug("Origin3 day {} record at {}:{}: PPG HR {} preferred over ECG HR {}",
                    dayIndex, hour, minute, ppgHeartRate, ecgHeartRate);
        }
        VeepooActivitySample sample = new VeepooActivitySample();
        sample.setTimestamp((int) (stamp.getTimeInMillis() / 1000L));
        sample.setRawKind(VeepooConstants.RAW_KIND_ACTIVITY);
        sample.setSteps(steps);
        sample.setHeartRate(heartRate);
        sample.setCaloriesBurnt(kcalTenths * 100);
        sample.setDistanceMeters(distanceMeters);
        sample.setDataSource(VeepooDeviceSupport.DATA_SOURCE_ACTIVITY_SUMMARY);
        sample.setApneaCount(apneaCount);
        sample.setHypoxiaMinutes(hypoxiaMinutes);
        sample.setHypoxiaTimeSum(hypoxiaTimeSum);
        sample.setCardiacLoadMinutes(cardiacLoadMinutes);
        out.addOriginSample(sample);
        return true;
    }

    /**
     * Back-fills per-minute heart rate for the pending sleep rows from the Origin3 five-minute
     * records covering them: the 0xE0 sleep stream carries no heart rate of its own, but the band
     * stores PPG/ECG averages in the activity stream. Runs after each Origin3 day parse, while
     * both lists are still in memory (sleep rows persist last, so without this merge they would
     * replace the overlapping activity rows and drop their heart rate).
     */
    public static void mergeHeartRateIntoSleep(List<VeepooActivitySample> originSamples, List<VeepooActivitySample> sleepSamples) {
        if (originSamples.isEmpty() || sleepSamples.isEmpty()) {
            return;
        }
        Map<Integer, Integer> heartRateBySlot = new HashMap<>();
        for (VeepooActivitySample sample : originSamples) {
            int heartRate = sample.getHeartRate();
            if (heartRate >= 30 && heartRate <= 200) {
                heartRateBySlot.put((sample.getTimestamp() / 300) * 300, heartRate);
            }
        }
        if (heartRateBySlot.isEmpty()) {
            return;
        }
        int filled = 0;
        for (VeepooActivitySample sleep : sleepSamples) {
            if (sleep.getHeartRate() != ActivitySample.NOT_MEASURED) {
                continue;
            }
            Integer heartRate = heartRateBySlot.get((sleep.getTimestamp() / 300) * 300);
            if (heartRate != null) {
                sleep.setHeartRate(heartRate);
                filled++;
            }
        }
        if (filled > 0) {
            LOG.info("Back-filled heart rate into {} sleep minutes from Origin3 records", filled);
        }
    }

    /**
     * Parses one Origin3 0xB7 five-minute HRV record: body[0] is unused on this firmware (always
     * 0) and bytes [1..50] hold 50 RR-interval samples in centiseconds, ten per minute. The
     * per-minute HRV value in milliseconds is the average absolute difference of consecutive valid
     * RR samples (centiseconds × 10). A minute with fewer than two valid samples is skipped.
     */
    private static int parseHrvFiveMinuteRecord(Calendar stamp, byte[] body, int dayIndex, VeepooSampleCollector out) {
        int added = 0;
        for (int m = 0; m < 5; m++) {
            int sumAbsDiff = 0;
            int diffs = 0;
            int prevRr = -1;
            for (int i = m * 10; i < m * 10 + 10; i++) {
                int rr = body[i + 1] & 0xFF;
                if (rr >= 30 && rr <= 210) {
                    if (prevRr != -1) {
                        sumAbsDiff += Math.abs(prevRr - rr);
                        diffs++;
                    }
                    prevRr = rr;
                }
            }
            int value = diffs > 0 ? sumAbsDiff * 10 / diffs : 0;
            if (value <= 0 || value > 600) {
                continue;
            }
            GenericHrvValueSample sample = new GenericHrvValueSample();
            sample.setTimestamp(stamp.getTimeInMillis() + m * 60_000L);
            sample.setValue(value);
            out.addHrvSample(sample);
            added++;
        }
        return added;
    }

    /** Averages the heart-rate bytes inside the plausible 30..200 range; 0 when none qualify. */
    private static int averageValidHeartRate(byte[] body) {
        int sum = 0;
        int count = 0;
        for (byte value : body) {
            int rate = value & 0xFF;
            if (rate >= 30 && rate <= 200) {
                sum += rate;
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    /** VeePoo's proprietary CRC-16 (AlarmCrcUtil): init 0xFFFF, table-driven; verifies Origin3 block payloads. */
    private static final int[] VP_CRC16_TABLE = {
            0, 49345, 49537, 320, 49921, 960, 640, 49729,
            50689, 1728, 1920, 51009, 1280, 50625, 50305, 1088,
            52225, 3264, 3456, 52545, 3840, 53185, 52865, 3648,
            2560, 51905, 52097, 2880, 51457, 2496, 2176, 51265,
            55297, 6336, 6528, 55617, 6912, 56257, 55937, 6720,
            7680, 57025, 57217, 8000, 56577, 7616, 7296, 56385,
            5120, 54465, 54657, 5440, 55041, 6080, 5760, 54849,
            53761, 4800, 4992, 54081, 4352, 53697, 53377, 4160,
            61441, 12480, 12672, 61761, 13056, 62401, 62081, 12864,
            13824, 63169, 63361, 14144, 62721, 13760, 13440, 62529,
            15360, 64705, 64897, 15680, 65281, 16320, 16000, 65089,
            64001, 15040, 15232, 64321, 14592, 63937, 63617, 14400,
            10240, 59585, 59777, 10560, 60161, 11200, 10880, 59969,
            60929, 11968, 12160, 61249, 11520, 60865, 60545, 11328,
            58369, 9408, 9600, 58689, 9984, 59329, 59009, 9792,
            8704, 58049, 58241, 9024, 57601, 8640, 8320, 57409,
            40961, 24768, 24960, 41281, 25344, 41921, 41601, 25152,
            26112, 42689, 42881, 26432, 42241, 26048, 25728, 42049,
            27648, 44225, 44417, 27968, 44801, 28608, 28288, 44609,
            43521, 27328, 27520, 43841, 26880, 43457, 43137, 26688,
            30720, 47297, 47489, 31040, 47873, 31680, 31360, 47681,
            48641, 32448, 32640, 48961, 32000, 48577, 48257, 31808,
            46081, 29888, 30080, 46401, 30464, 47041, 46721, 30272,
            29184, 45761, 45953, 29504, 45313, 29120, 28800, 45121,
            20480, 37057, 37249, 20800, 37633, 21440, 21120, 37441,
            38401, 22208, 22400, 38721, 21760, 38337, 38017, 21568,
            39937, 23744, 23936, 40257, 24320, 40897, 40577, 24128,
            23040, 39617, 39809, 23360, 39169, 22976, 22656, 38977,
            34817, 18624, 18816, 35137, 19200, 35777, 35457, 19008,
            19968, 36545, 36737, 20288, 36097, 19904, 19584, 35905,
            17408, 33985, 34177, 17728, 34561, 18368, 18048, 34369,
            33281, 17088, 17280, 33601, 16640, 33217, 32897, 16448
    };

    private static int vpCrc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte value : data) {
            crc = (((crc >> 8) & 0xFF) ^ VP_CRC16_TABLE[(crc & 0xFF) ^ (value & 0xFF)]) & 0xFFFF;
        }
        return crc;
    }
}
