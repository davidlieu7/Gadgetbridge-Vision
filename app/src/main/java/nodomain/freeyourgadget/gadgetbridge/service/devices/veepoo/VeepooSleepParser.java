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

import java.util.Arrays;
import java.util.Calendar;

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
import nodomain.freeyourgadget.gadgetbridge.entities.VeepooSleepSummarySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

/**
 * Receives the samples produced by the pure day parsers ({@link VeepooSleepParser} and
 * {@link VeepooOrigin3Parser}). {@link VeepooDeviceSupport} implements it as a trivial adapter
 * over its pending lists, so the parsers stay free of database access and Android dependencies
 * while the append order is exactly the one the inline parsing used to produce.
 */
interface VeepooSampleCollector {
    void addSleepSample(VeepooActivitySample sample);

    void addSleepSummary(VeepooSleepSummarySample summary);

    void addOriginSample(VeepooActivitySample sample);

    void addHrvSample(GenericHrvValueSample sample);

    void addStressSample(GenericStressSample sample);

    void addBreathSample(GenericRespiratoryRateSample sample);

    void addBpSample(GenericBloodPressureSample sample);

    void addSpo2Sample(GenericSpo2Sample sample);

    void addGlucoseSample(GlucoseSample sample);

    void addLipidSample(GenericBloodLipidSample sample);

    void addTemperatureSample(GenericTemperatureSample sample);
}

/**
 * Pure parsers for fully assembled 0xE0 sleep-day payloads: the concatenated byte stream of one
 * requested day in, per-minute {@link VeepooActivitySample} rows plus one
 * {@link VeepooSleepSummarySample} per record out, appended through the given
 * {@link VeepooSampleCollector}. Two layouts exist: the fixed 96-byte records of normal bands and
 * the A1/A3/A5 tagged stream of preciseSleep-capable bands. No database access and no Android
 * dependencies; the chunk assembly, watchdogs and persistence stay in {@link VeepooDeviceSupport}.
 */
final class VeepooSleepParser {
    private static final Logger LOG = LoggerFactory.getLogger(VeepooSleepParser.class);

    private VeepooSleepParser() {
    }

    /**
     * Parses one 96-byte normal sleep record and emits per-minute activity samples.
     * Stages: bitmap bit 0 = deep, 1 = light; window spans are marked '2' (REM).
     */
    public static void parseNormalRecord(byte[] stream, int offset, int dayIndex, long todayMidnightMs, VeepooSampleCollector out) {
        byte[] rec = new byte[VeepooConstants.SLEEP_RECORD_SIZE];
        System.arraycopy(stream, offset, rec, 0, VeepooConstants.SLEEP_RECORD_SIZE);

        int startMonth = rec[1] & 0xFF;
        int startDay = rec[2] & 0xFF;
        int startHour = rec[3] & 0xFF;
        int startMinute = rec[4] & 0xFF;
        int endMonth = rec[5] & 0xFF;
        int endDay = rec[6] & 0xFF;
        int endHour = rec[7] & 0xFF;
        int endMinute = rec[8] & 0xFF;
        int quality = rec[11] & 0xFF;
        int totalSlots = rec[43] & 0xFF;

        if (quality == 255 || quality < 1 || quality > 5) {
            LOG.debug("Discarding sleep record for day {} (quality {})", dayIndex, quality);
            return;
        }
        if (totalSlots > 248) {
            LOG.warn("Discarding sleep record for day {} (invalid total slots {})", dayIndex, totalSlots);
            return;
        }
        if (!VeepooDeviceSupport.isValidDatePart(startMonth, startDay, startHour, startMinute) || !VeepooDeviceSupport.isValidDatePart(endMonth, endDay, endHour, endMinute)) {
            LOG.warn("Discarding sleep record for day {} (invalid date parts)", dayIndex);
            return;
        }

        // Stage bitmap: slot k uses bit (7 - k%8) of byte 12 + k/8.
        char[] stages = new char[totalSlots];
        for (int slot = 0; slot < totalSlots; slot++) {
            int bit = (rec[12 + slot / 8] >>> (7 - slot % 8)) & 1;
            stages[slot] = bit == 0 ? '0' : '1';
        }

        // Window override: if deep time is non-zero, mark REM spans where light time covers them.
        int windowCount = rec[55] & 0xFF;
        int deepSlots = rec[9] & 0xFF;
        int lightSlots = rec[10] & 0xFF;
        if (deepSlots != 0 && windowCount > 0) {
            if (windowCount > 10) {
                windowCount = 10;
            }
            for (int w = 0; w < windowCount; w++) {
                int windowStart = rec[45 + w] & 0xFF;
                int windowEnd = rec[57 + w] & 0xFF;
                int span = windowEnd - windowStart;
                if (span > 0 && lightSlots >= span) {
                    for (int slot = windowStart; slot < Math.min(windowStart + span, totalSlots); slot++) {
                        stages[slot] = '2';
                    }
                }
            }
        }

        Calendar start = anchorSleepDate(dayIndex, startMonth, startDay, startHour, startMinute, todayMidnightMs);
        Calendar end = anchorSleepDate(dayIndex, endMonth, endDay, endHour, endMinute, todayMidnightMs);
        if (end.getTimeInMillis() <= start.getTimeInMillis()) {
            // Crossed a year boundary that anchoring could not resolve; use the tracked duration.
            end.setTimeInMillis(start.getTimeInMillis() + totalSlots * 5L * 60_000L);
        }
        long endCap = start.getTimeInMillis() + 24L * VeepooDeviceSupport.MILLIS_PER_DAY;
        if (end.getTimeInMillis() > endCap) {
            LOG.warn("Sleep record for day {} spans more than a day; clamping", dayIndex);
            end.setTimeInMillis(endCap);
        }

        int totalMinutes = (int) ((end.getTimeInMillis() - start.getTimeInMillis()) / 60_000L);
        long startEpochSeconds = start.getTimeInMillis() / 1000L;

        for (int minuteOffset = 0; minuteOffset < totalMinutes; minuteOffset++) {
            int slot = minuteOffset / 5;
            int rawKind;
            if (slot >= totalSlots) {
                rawKind = VeepooConstants.RAW_KIND_AWAKE_SLEEP;
            } else {
                switch (stages[slot]) {
                    case '0':
                        rawKind = VeepooConstants.RAW_KIND_DEEP_SLEEP;
                        break;
                    case '1':
                        rawKind = VeepooConstants.RAW_KIND_LIGHT_SLEEP;
                        break;
                    case '2':
                        rawKind = VeepooConstants.RAW_KIND_REM_SLEEP;
                        break;
                    default:
                        rawKind = VeepooConstants.RAW_KIND_AWAKE_SLEEP;
                }
            }
            VeepooActivitySample sample = new VeepooActivitySample();
            sample.setTimestamp((int) (startEpochSeconds + minuteOffset * 60L));
            sample.setRawKind(rawKind);
            sample.setSteps(0);
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
            sample.setCaloriesBurnt(0);
            sample.setDistanceMeters(0);
            sample.setDataSource(VeepooDeviceSupport.DATA_SOURCE_SLEEP_SUMMARY);
            out.addSleepSample(sample);
        }
        VeepooSleepSummarySample summary = new VeepooSleepSummarySample();
        summary.setTimestamp(start.getTimeInMillis());
        summary.setDurationMin(totalMinutes);
        summary.setQuality(quality);
        summary.setWakeCount(rec[55] & 0xFF);
        out.addSleepSummary(summary);
        LOG.info("Parsed sleep record for day {}: {} minutes ({} deep slots, {} light slots, quality {})",
                dayIndex, totalMinutes, deepSlots, lightSlots, quality);
    }

    /**
     * Parses the precise sleep stream used by preciseSleep-capable bands: an A1 marker followed by
     * a LE16 record length, then tagged blocks. A3 is the main block (dates and durations), A5 is
     * the per-minute stage curve (BE16 words with the stage in the top three bits).
     */
    public static void parsePrecisionStream(byte[] stream, int dayIndex, long todayMidnightMs, VeepooSampleCollector out) {
        int pos = 0;
        while (pos + 3 <= stream.length) {
            int marker = -1;
            for (int i = pos; i + 3 <= stream.length; i++) {
                if ((stream[i] & 0xFF) == 0xA1) {
                    marker = i;
                    break;
                }
            }
            if (marker < 0) {
                break;
            }
            int recordLength = VeepooDeviceSupport.le16(stream, marker + 1);
            int recordStart = marker + 3;
            int recordEnd = Math.min(recordStart + Math.max(0, recordLength - 3), stream.length);
            parsePrecisionRecord(stream, recordStart, recordEnd, dayIndex, todayMidnightMs, out);
            pos = recordEnd;
        }
    }

    private static void parsePrecisionRecord(byte[] stream, int recordStart, int recordEnd, int dayIndex, long todayMidnightMs, VeepooSampleCollector out) {
        if (recordEnd - recordStart < 40) {
            LOG.warn("Precise sleep record for day {} is too short ({} bytes)", dayIndex, recordEnd - recordStart);
            return;
        }

        byte[] a3 = null;
        int curveStart = -1;
        int curveLen = 0;
        int pos = recordStart;
        while (pos + 2 <= recordEnd) {
            int tag = stream[pos] & 0xFF;
            int payloadStart;
            int payloadLen;
            if (tag == 0xA5) {
                payloadStart = pos + 3;
                payloadLen = VeepooDeviceSupport.le16(stream, pos + 1);
            } else {
                payloadStart = pos + 2;
                payloadLen = stream[pos + 1] & 0xFF;
            }
            if (payloadStart + payloadLen > recordEnd) {
                LOG.debug("Precise sleep tag 0x{} at record offset {} declares more bytes than remain; stopping the walk",
                        VeepooDeviceSupport.hexByte(tag), pos - recordStart);
                break;
            }
            if (tag == 0xA3 && a3 == null) {
                a3 = Arrays.copyOfRange(stream, payloadStart, payloadStart + payloadLen);
            } else if (tag == 0xA5 && curveStart < 0) {
                curveStart = payloadStart;
                curveLen = payloadLen;
            }
            pos = payloadStart + payloadLen;
        }
        if (a3 == null || curveStart < 0) {
            LOG.warn("Precise sleep record for day {} is missing the A3 block or the stage curve", dayIndex);
            return;
        }

        if (a3.length < 27) {
            LOG.warn("Discarding precise sleep record for day {} (A3 block too short: {} bytes)", dayIndex, a3.length);
            return;
        }
        int startMonth = a3[0] & 0xFF;
        int startDay = a3[1] & 0xFF;
        int startHour = a3[2] & 0xFF;
        int startMinute = a3[3] & 0xFF;
        if (!VeepooDeviceSupport.isValidDatePart(startMonth, startDay, startHour, startMinute)) {
            LOG.warn("Discarding precise sleep record for day {} (invalid start date parts)", dayIndex);
            return;
        }
        int deepSleepTime = VeepooDeviceSupport.le16(a3, 19);
        int lightSleepTime = VeepooDeviceSupport.le16(a3, 21);
        int allSleepTime = VeepooDeviceSupport.le16(a3, 25);
        int accurateType = a3.length >= 36 ? a3[35] & 0xFF : 1;

        // Type 0 (scientific sleep off) and type 1 both carry plain BE16 stage words.
        if (accurateType != 0 && accurateType != 1) {
            LOG.warn("Precise sleep record for day {} uses unsupported accuracy type {}; skipping", dayIndex, accurateType);
            return;
        }
        int wordCount = curveLen / 2;
        if (wordCount < 30 || allSleepTime < 30 || allSleepTime > 24 * 60) {
            LOG.warn("Discarding precise sleep record for day {} (curve words {}, total minutes {})", dayIndex, wordCount, allSleepTime);
            return;
        }

        Calendar start = anchorSleepDate(dayIndex, startMonth, startDay, startHour, startMinute, todayMidnightMs);
        long startEpochSeconds = start.getTimeInMillis() / 1000L;
        int minutes = Math.min(wordCount, allSleepTime);
        for (int minuteOffset = 0; minuteOffset < minutes; minuteOffset++) {
            int word = ((stream[curveStart + minuteOffset * 2] & 0xFF) << 8) | (stream[curveStart + minuteOffset * 2 + 1] & 0xFF);
            int stage = (word & 0xE000) >> 13;
            int rawKind;
            switch (stage) {
                case 0:
                    rawKind = VeepooConstants.RAW_KIND_DEEP_SLEEP;
                    break;
                case 1:
                    rawKind = VeepooConstants.RAW_KIND_LIGHT_SLEEP;
                    break;
                case 2:
                    rawKind = VeepooConstants.RAW_KIND_REM_SLEEP;
                    break;
                default:
                    rawKind = VeepooConstants.RAW_KIND_AWAKE_SLEEP;
            }
            VeepooActivitySample sample = new VeepooActivitySample();
            sample.setTimestamp((int) (startEpochSeconds + minuteOffset * 60L));
            sample.setRawKind(rawKind);
            sample.setSteps(0);
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
            sample.setCaloriesBurnt(0);
            sample.setDistanceMeters(0);
            sample.setDataSource(VeepooDeviceSupport.DATA_SOURCE_SLEEP_SUMMARY);
            out.addSleepSample(sample);
        }
        int rawQuality = a3[15] & 0xFF;
        VeepooSleepSummarySample summary = new VeepooSleepSummarySample();
        summary.setTimestamp(start.getTimeInMillis());
        summary.setDurationMin(allSleepTime);
        summary.setQuality(rawQuality >= 1 && rawQuality <= 5 ? rawQuality : null);
        summary.setWakeCount(a3[16] & 0xFF);
        summary.setScoreGetUp(a3[9] & 0xFF);
        summary.setScoreDeep(a3[10] & 0xFF);
        summary.setScoreEfficiency(a3[11] & 0xFF);
        summary.setScoreFallAsleep(a3[12] & 0xFF);
        summary.setScoreTime(a3[13] & 0xFF);
        out.addSleepSummary(summary);
        LOG.info("Parsed precise sleep record for day {}: {} minutes ({} deep, {} light)", dayIndex, minutes, deepSleepTime, lightSleepTime);
    }

    /**
     * Anchors a month/day/hour/minute tuple to an actual date, assuming the year of the fetch
     * anchor (midnight of "today" when the pass started) and rolling back one year when the
     * candidate ends up in the future (December/January boundary).
     */
    private static Calendar anchorSleepDate(int dayIndex, int month, int day, int hour, int minute, long todayMidnightMs) {
        Calendar reference = Calendar.getInstance();
        reference.setTimeInMillis(todayMidnightMs);
        reference.add(Calendar.DAY_OF_MONTH, -dayIndex);
        Calendar candidate = Calendar.getInstance();
        candidate.clear();
        candidate.set(reference.get(Calendar.YEAR), month - 1, day, hour, minute);
        if (candidate.getTimeInMillis() > reference.getTimeInMillis() + VeepooDeviceSupport.MILLIS_PER_DAY) {
            candidate.add(Calendar.YEAR, -1);
        }
        return candidate;
    }
}
