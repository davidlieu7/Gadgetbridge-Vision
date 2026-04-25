/*  Copyright (C) 2026 Viktor Karpov

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
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.miscale;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Toast;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericImpedanceSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.MiScaleWeightSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.miscale.MiScaleS400Coordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericImpedanceSample;
import nodomain.freeyourgadget.gadgetbridge.entities.MiScaleWeightSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.AbstractDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class MiScaleS400DeviceSupport extends AbstractDeviceSupport {
    public static final String PREF_MISCALE_S400_BIND_KEY = "pref_miscale_s400_bind_key";
    /** The scale's profile whose weigh-ins to store; empty or 0 stores every profile's. */
    public static final String PREF_MISCALE_S400_PROFILE = "pref_miscale_s400_profile";

    private static final Logger LOG = LoggerFactory.getLogger(MiScaleS400DeviceSupport.class);
    private static final ParcelUuid BODY_COMPOSITION_SERVICE_UUID = new ParcelUuid(UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb"));
    private static final ParcelUuid XIAOMI_SERVICE_DATA_UUID = new ParcelUuid(UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb"));
    /**
     * The scale measures at two frequencies and sends the readings in two frames. Xiaomi's own
     * description of the S400 says which is which: "50kHz low frequency - Measure extracellular
     * fluid" and "250kHz high frequency - Measure intracellular fluid"
     * (https://www.mi.com/global/product/xiaomi-body-composition-scale-s400/). A 50 kHz current
     * does not cross cell membranes, so it meets the larger impedance, and on this scale that is
     * the reading sent along with the mass; the second frame carries the smaller reading. The
     * frequencies come from that description, not from a protocol document.
     */
    private static final int IMPEDANCE_LOW_BAND_HZ = 50_000;
    private static final int IMPEDANCE_HIGH_BAND_HZ = 250_000;
    /** The scale measures across the whole body only. */
    private static final int WHOLE_BODY_SECTION = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private byte[] lastServiceData;
    private byte[] lastUnhandledAdvertisement;
    private boolean warnedMissingBindKey = false;
    /** The profile of the last decoded frame, for the message when it is ignored. */
    private int lastProfile = 0;

    // The weigh-in state machine: which frames are in, what was stored, what to do next
    private final MiScaleS400WeighInCollector collector = new MiScaleS400WeighInCollector();

    private final Runnable settleRunnable = () -> {
        LOG.info("S400 measurement settled for {}", maskAddress(getDevice().getAddress()));
        act(collector.settle(System.currentTimeMillis()));
    };

    /**
     * The scale is never connected to: the service keeps it in
     * {@link GBDevice.State#WAITING_FOR_SCAN} and hands us the advertisements it broadcasts.
     */
    @Override
    public boolean connect() {
        return false;
    }

    @Override
    public void onScanResult(final ScanResult scanResult) {
        final String bindKey = getDevicePrefs().getString(PREF_MISCALE_S400_BIND_KEY, null);
        if (!MiScaleS400Decryptor.isValidBindKey(bindKey)) {
            if (!warnedMissingBindKey) {
                warnedMissingBindKey = true;
                GB.toast(getContext().getString(R.string.miscale_s400_bind_key_missing), Toast.LENGTH_LONG, GB.WARN);
            }
            return;
        }
        handleScanResult(scanResult);
    }

    @Override
    public void dispose() {
        handler.removeCallbacks(settleRunnable);
        collector.reset();
        lastServiceData = null;
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    private void handleScanResult(final ScanResult result) {
        final ScanRecord scanRecord = result.getScanRecord();
        if (scanRecord == null) {
            LOG.debug("Ignoring S400 scan result without scan record");
            return;
        }

        final byte[] serviceData = extractServiceData(scanRecord);
        if (serviceData == null) {
            logUnhandledAdvertisement(result, scanRecord);
            return;
        }

        if (lastServiceData != null && Arrays.equals(lastServiceData, serviceData)) {
            // The scale repeats each frame many times
            return;
        }
        lastServiceData = serviceData.clone();

        LOG.debug("S400 frame from {} rssi={} productId=0x{}: {}",
                maskAddress(result.getDevice().getAddress()),
                result.getRssi(),
                Integer.toHexString(MiScaleS400Decryptor.getProductId(stripServiceDataHeader(serviceData))),
                hexdumpWithoutAddress(serviceData, result.getDevice().getAddress()));

        final String bindKey = getDevicePrefs().getString(PREF_MISCALE_S400_BIND_KEY, null);
        final byte[] payload = MiScaleS400Decryptor.decryptPayload(serviceData, getDevice().getAddress(), bindKey);
        if (payload == null) {
            LOG.warn("Failed to decrypt S400 frame from {} (wrong bind key?)", maskAddress(result.getDevice().getAddress()));
            return;
        }
        LOG.debug("S400 payload: {}", GB.hexdump(payload));

        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.parsePayload(payload);
        if (measurement == null) {
            LOG.debug("S400 payload carries no body composition object");
            return;
        }
        LOG.debug("S400 measurement: {}", measurement);
        lastProfile = measurement.getProfile();

        act(collector.offer(measurement, System.currentTimeMillis(), getWantedProfile()));
    }

    /** Does what the collector decided: writes, timer, and what the user gets told. */
    private void act(final MiScaleS400WeighInCollector.Result result) {
        switch (result.ignored) {
            case IDLE:
                return;
            case FINISHED_REPEAT:
                LOG.debug("S400 weigh-in already finished, ignoring repeated frame");
                return;
            case OTHER_PROFILE:
                // Another person on the same scale: the scale assigned the weigh-in to a profile
                // this phone is not set to store (several phones may listen to one scale)
                LOG.info("S400 weigh-in is profile {}'s, not profile {}'s; ignored", lastProfile, getWantedProfile());
                if (result.announceIgnoredProfile) {
                    // Say so once per weigh-in, or a silent scale looks like a broken one
                    GB.toast(getContext().getString(R.string.miscale_s400_profile_ignored, lastProfile, getWantedProfile()),
                            Toast.LENGTH_SHORT, GB.INFO);
                }
                return;
            case NONE:
                break;
        }
        if (result.supersededPending) {
            LOG.info("S400 weigh-in superseded by a new one before it settled");
        }
        for (final MiScaleS400WeighInCollector.Store store : result.stores) {
            saveMeasurement(store);
        }
        if (result.settledWithoutNews) {
            LOG.info("S400 weigh-in settled with nothing beyond what was stored");
        }
        if (result.discardedSecondOnly) {
            LOG.warn("S400 weigh-in only delivered the second impedance reading, discarding");
        }
        if (result.complete) {
            LOG.info("S400 weigh-in {} complete", result.stores.isEmpty() ? "" : result.stores.get(0).weight.getTimestamp());
            handler.removeCallbacks(settleRunnable);
        } else if (result.armSettle) {
            handler.removeCallbacks(settleRunnable);
            handler.postDelayed(settleRunnable, MiScaleS400WeighInCollector.SETTLE_MS);
        }
        if (result.finished) {
            // The scale stays scanned for: the next weigh-in arrives without the user doing anything.
            lastServiceData = null;
        }
    }

    /** The profile from the settings, or 0 when unset, blank or not a number. */
    private int getWantedProfile() {
        final String value = getDevicePrefs().getString(PREF_MISCALE_S400_PROFILE, "");
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (final NumberFormatException e) {
            LOG.warn("Ignoring S400 profile setting '{}': not a number", value);
            return 0;
        }
    }

    private void saveMeasurement(final MiScaleS400WeighInCollector.Store store) {
        final MiScaleS400Decryptor.Measurement measurement = store.weight;
        final MiScaleS400Decryptor.Measurement secondImpedance = store.secondImpedance;
        final long scaleTime = measurement.getTimestamp() * 1000L;
        // The collector keeps one timestamp per weigh-in, so a store that completes an early
        // one replaces its rows rather than adding a second weigh-in
        final long timestamp = store.timestampMs;

        LOG.info("Saving S400 measurement at {} (scale clock {}): {}kg hr={} impedance={}Ω secondImpedance={}Ω profile={}",
                timestamp,
                scaleTime,
                measurement.getWeightKg(),
                measurement.getHeartRate(),
                measurement.getImpedanceOhm(),
                secondImpedance != null ? secondImpedance.getSecondImpedanceOhm() : null,
                measurement.getProfile());

        if (measurement.getImpedanceOhm() != null && secondImpedance != null
                && secondImpedance.getSecondImpedanceOhm() != null
                && measurement.getImpedanceOhm() <= secondImpedance.getSecondImpedanceOhm()) {
            // The frequency assignment below rests on the reading sent with the mass being the
            // larger one. Say so loudly if a scale ever disagrees, so a tester's log shows it.
            LOG.warn("S400 weigh-in {}: the reading sent with the mass ({}Ω) is not larger than the second one ({}Ω)",
                    timestamp, measurement.getImpedanceOhm(), secondImpedance.getSecondImpedanceOhm());
        }

        try (DBHandler db = GBApplication.acquireDB()) {
            final DaoSession session = db.getDaoSession();
            final MiScaleWeightSampleProvider provider = new MiScaleWeightSampleProvider(getDevice(), session);
            final Long userId = DBHelper.getUser(session).getId();
            final Long deviceId = DBHelper.getDevice(getDevice(), session).getId();

            // The 50 kHz reading goes into the same column the Mi Body Composition Scale fills
            // (that scale measures at 50 kHz too), so the weight chart estimates body composition
            // from it; whole ohms.
            final Integer impedanceOhm = measurement.getImpedanceOhm() != null
                    ? Math.round(measurement.getImpedanceOhm()) : null;

            // Both raw readings as well, keyed by frequency, for whatever wants more than the
            // single-band estimate the column above feeds.
            final List<GenericImpedanceSample> impedanceSamples = new ArrayList<>(2);
            if (measurement.getImpedanceOhm() != null) {
                impedanceSamples.add(new GenericImpedanceSample(timestamp, deviceId, userId,
                        WHOLE_BODY_SECTION, IMPEDANCE_LOW_BAND_HZ, measurement.getImpedanceOhm()));
            }
            if (secondImpedance != null && secondImpedance.getSecondImpedanceOhm() != null) {
                impedanceSamples.add(new GenericImpedanceSample(timestamp, deviceId, userId,
                        WHOLE_BODY_SECTION, IMPEDANCE_HIGH_BAND_HZ, secondImpedance.getSecondImpedanceOhm()));
            }

            // One weigh-in is one transaction: either every row of it is stored or none is.
            session.runInTx(() -> {
                provider.addSample(new MiScaleWeightSample(timestamp, deviceId, userId, measurement.getWeightKg(), impedanceOhm));
                if (!impedanceSamples.isEmpty()) {
                    new GenericImpedanceSampleProvider(getDevice(), session).addSamples(impedanceSamples);
                }
                if (measurement.getHeartRate() != null) {
                    session.getGenericHeartRateSampleDao().insertOrReplace(
                            new GenericHeartRateSample(timestamp, deviceId, userId, measurement.getHeartRate())
                    );
                }
            });

            // let the dashboard and charts know there is a new value to show
            GB.signalActivityDataFinish(getDevice());
        } catch (final Exception e) {
            LOG.error("Error saving S400 measurement", e);
        }

        MiScaleS400Coordinator.updateStoredMeasurementInfo(getDevice(), timestamp, measurement.getWeightKg(), measurement.getHeartRate());
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.toast(buildMeasurementToast(measurement, secondImpedance), Toast.LENGTH_LONG, GB.INFO);
    }

    private String buildMeasurementToast(final MiScaleS400Decryptor.Measurement measurement,
                                         final MiScaleS400Decryptor.Measurement secondImpedance) {
        final StringBuilder sb = new StringBuilder(getContext().getString(R.string.weight_kg, measurement.getWeightKg()));

        if (measurement.getHeartRate() != null) {
            sb.append("  ").append(getContext().getString(R.string.bpm_value_unit, measurement.getHeartRate()));
        }

        if (measurement.getImpedanceOhm() != null) {
            sb.append("  ").append(String.format(Locale.getDefault(), "%.1f Ω", measurement.getImpedanceOhm()));
        }

        if (secondImpedance != null && secondImpedance.getSecondImpedanceOhm() != null) {
            sb.append(" / ").append(String.format(Locale.getDefault(), "%.1f Ω", secondImpedance.getSecondImpedanceOhm()));
        }

        // The scale's profile number, so the user can tell which one to store in the settings
        sb.append("  ").append(getContext().getString(R.string.miscale_s400_profile_number, measurement.getProfile()));

        return sb.toString();
    }

    /** The address with all but its last two octets hidden, as Android itself prints a ScanResult. */
    private static String maskAddress(final String address) {
        if (address == null || address.length() < 5) {
            return "XX:XX:XX:XX:XX:XX";
        }
        return "XX:XX:XX:XX:" + address.substring(address.length() - 5);
    }

    /**
     * A hexdump with the scale's address blanked wherever its six bytes appear, in either byte
     * order: a MiBeacon frame carries the address, so the raw bytes would otherwise undo
     * {@link #maskAddress} in the same log line.
     */
    private static String hexdumpWithoutAddress(final byte[] data, final String address) {
        final String hex = GB.hexdump(data);
        if (address == null) {
            return hex;
        }
        final String forward = address.replace(":", "").toUpperCase(Locale.ROOT);
        final StringBuilder reversed = new StringBuilder();
        for (int i = forward.length() - 2; i >= 0; i -= 2) {
            reversed.append(forward, i, i + 2);
        }
        final String blank = "XXXXXXXXXXXX";
        return hex.toUpperCase(Locale.ROOT).replace(reversed.toString(), blank).replace(forward, blank);
    }

    private static byte[] stripServiceDataHeader(final byte[] serviceData) {
        if (serviceData.length > 2 && serviceData[0] == (byte) 0x95 && serviceData[1] == (byte) 0xfe) {
            return Arrays.copyOfRange(serviceData, 2, serviceData.length);
        }
        return serviceData;
    }

    private byte[] extractServiceData(final ScanRecord scanRecord) {
        final byte[] xiaomiServiceData = scanRecord.getServiceData(XIAOMI_SERVICE_DATA_UUID);
        if (xiaomiServiceData != null && xiaomiServiceData.length >= 24) {
            final byte[] payload = new byte[xiaomiServiceData.length + 2];
            payload[0] = (byte) 0x95;
            payload[1] = (byte) 0xfe;
            System.arraycopy(xiaomiServiceData, 0, payload, 2, xiaomiServiceData.length);
            return payload;
        }

        final byte[] directServiceData = scanRecord.getServiceData(BODY_COMPOSITION_SERVICE_UUID);
        if (directServiceData != null && directServiceData.length >= 24) {
            return directServiceData;
        }

        final byte[] rawBytes = scanRecord.getBytes();
        if (rawBytes == null) {
            return null;
        }

        int offset = 0;
        while (offset < rawBytes.length) {
            final int fieldLength = rawBytes[offset] & 0xff;
            if (fieldLength == 0) {
                break;
            }

            final int nextOffset = offset + fieldLength + 1;
            if (nextOffset > rawBytes.length || fieldLength < 3) {
                break;
            }

            final int fieldType = rawBytes[offset + 1] & 0xff;
            if (fieldType == 0x16 && rawBytes[offset + 2] == (byte) 0x95 && rawBytes[offset + 3] == (byte) 0xfe) {
                final int payloadLength = fieldLength - 1;
                final byte[] payload = new byte[payloadLength];
                System.arraycopy(rawBytes, offset + 2, payload, 0, payloadLength);
                return payload;
            }

            offset = nextOffset;
        }

        return null;
    }

    private void logUnhandledAdvertisement(final ScanResult result, final ScanRecord scanRecord) {
        final byte[] rawBytes = scanRecord.getBytes();
        if (rawBytes == null) {
            LOG.debug("Ignoring S400 advertisement without body composition service data from {} and without raw bytes", maskAddress(result.getDevice().getAddress()));
            return;
        }

        if (lastUnhandledAdvertisement != null && Arrays.equals(lastUnhandledAdvertisement, rawBytes)) {
            return;
        }

        lastUnhandledAdvertisement = rawBytes.clone();
        LOG.info("Ignoring S400 advertisement without body composition service data from {} rssi={}: {}",
                maskAddress(result.getDevice().getAddress()), result.getRssi(), hexdumpWithoutAddress(rawBytes, result.getDevice().getAddress()));
    }
}
