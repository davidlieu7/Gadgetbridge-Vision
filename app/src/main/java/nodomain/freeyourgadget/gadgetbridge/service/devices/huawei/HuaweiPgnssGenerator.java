/*  Copyright (C) 2026 Ulveon

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.Constellation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsFileGenerator.PredictionEpoch;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPgnssSeed.Satellite;

/** Converts Huawei HW_EEV2 seeds into the six PGNSS files requested by the GT 7 Pro. */
public final class HuaweiPgnssGenerator {
    private static final long GPS_EPOCH_UNIX_SECONDS = 315964800L;
    private static final int DAYS = 3;
    private static final int EPOCH_SECONDS = 7200;
    private static final Map<Constellation, String> NAV_FIELDS = Map.of(
            Constellation.GPS, "gpsNav", Constellation.BDS, "bdsNav",
            Constellation.GLONASS, "gloNav", Constellation.GALILEO, "galNav", Constellation.QZS, "qzsNav");

    private HuaweiPgnssGenerator() {}

    public static Map<String, byte[]> generate(final byte[] encodedSeed, final long unixMillis) throws IOException {
        final Map<String, byte[]> fields = decodeFields(encodedSeed);
        final long gpsSeconds = checkedGpsTime(fields.get("seedInfo"), unixMillis);
        final Map<String, byte[]> files = new LinkedHashMap<>();
        for (final Constellation constellation : Constellation.values()) {
            checkInterrupted();
            // The GT 7 Pro capture uses an effective zero GLONASS model offset.
            // This differs from the real GPS/UTC leap seconds used in checkedGpsTime.
            final List<Satellite> satellites = HuaweiPgnssSeed.decode(constellation, fields.get(NAV_FIELDS.get(constellation)), 0);
            final byte[] data = constellation == Constellation.GLONASS
                    ? HuaweiPgnssGlonass.generate(satellites, fields.get("gloAcc"), 0, gpsSeconds, DAYS)
                    : generateConstellation(constellation, satellites, gpsSeconds);
            files.put(constellation.getFileName(), data);
        }
        files.put("HW_PGNSS_EXTRA", HuaweiPgnssExtra.decode(fields));
        checkInterrupted();
        return files;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("PGNSS prediction was cancelled");
        }
    }

    static Map<String, byte[]> decodeFields(final byte[] encodedSeed) throws IOException {
        if (encodedSeed == null || encodedSeed.length == 0 || encodedSeed.length > 1024 * 1024) {
            throw new IOException("The PGNSS seed size is invalid");
        }
        try {
            final JsonObject root = JsonParser.parseString(new String(encodedSeed, StandardCharsets.UTF_8)).getAsJsonObject();
            final var entries = root.getAsJsonArray("HiEE");
            if (entries == null || entries.size() > 32) {
                throw new IOException("The PGNSS seed fields are invalid");
            }
            final Map<String, byte[]> fields = new LinkedHashMap<>();
            for (final JsonElement item : entries) {
                final JsonObject entry = item.getAsJsonObject();
                if (entry.size() != 1) {
                    throw new IOException("The PGNSS seed entry is invalid");
                }
                final var field = entry.entrySet().iterator().next();
                if (!field.getValue().isJsonPrimitive() || !field.getValue().getAsJsonPrimitive().isString()
                        || fields.putIfAbsent(field.getKey(), Base64.getDecoder().decode(field.getValue().getAsString())) != null) {
                    throw new IOException("The PGNSS seed has an invalid or repeated field");
                }
            }
            return fields;
        } catch (final IllegalArgumentException | IllegalStateException | ClassCastException | com.google.gson.JsonParseException exception) {
            throw new IOException("The PGNSS seed JSON is invalid", exception);
        }
    }

    static long checkedGpsTime(final byte[] seedInfo, final long unixMillis) throws IOException {
        if (seedInfo == null || seedInfo.length != 14 || unixMillis < 0) {
            throw new IOException("The PGNSS seed time is invalid");
        }
        final ByteBuffer input = ByteBuffer.wrap(seedInfo).order(ByteOrder.BIG_ENDIAN);
        final long start = Integer.toUnsignedLong(input.getInt());
        final long end = Integer.toUnsignedLong(input.getInt());
        final int leapSeconds = Byte.toUnsignedInt(input.get());
        final long gpsSeconds = unixMillis / 1000 - GPS_EPOCH_UNIX_SECONDS + leapSeconds;
        final long firstWindow = Math.floorDiv(gpsSeconds, EPOCH_SECONDS) * EPOCH_SECONDS;
        if (leapSeconds > 64 || start > firstWindow || end <= firstWindow + DAYS * 86400L + EPOCH_SECONDS + 14
                || end - start > 8 * 86400L || gpsSeconds < 0 || gpsSeconds > 0xffffffffL) {
            throw new IOException("The PGNSS seed does not cover the requested three-day prediction");
        }
        return gpsSeconds;
    }

    static byte[] generateConstellation(final Constellation constellation, final List<Satellite> satellites,
                                        final long gpsSeconds) throws IOException {
        final long first = Math.floorDiv(gpsSeconds, EPOCH_SECONDS) * EPOCH_SECONDS + EPOCH_SECONDS
                + (constellation == Constellation.BDS ? 14 : 0);
        final List<PredictionEpoch> epochs = new ArrayList<>();
        for (int epoch = 0; epoch < DAYS * 12; epoch++) {
            final long time = first + epoch * EPOCH_SECONDS;
            final List<byte[]> records = new ArrayList<>();
            for (final Satellite satellite : satellites) {
                checkInterrupted();
                try {
                    final double[] parameters = HuaweiPgnssKepler.fit(constellation, satellite, time);
                    records.add(HuaweiPgnssKeplerEncoder.encode(constellation, satellite.id(), time,
                            parameters, satellite.delays(), satellite.health()));
                } catch (final InterruptedIOException exception) {
                    throw exception;
                } catch (final IOException exception) {
                    // A satellite can lack healthy coverage or exceed the broadcast field limits.
                    // The native converter also omits those satellites from this epoch.
                }
            }
            if (records.isEmpty()) {
                throw new IOException("The PGNSS epoch has no usable satellites");
            }
            epochs.add(new PredictionEpoch(time, List.of(records)));
        }
        return HuaweiAgpsFileGenerator.generatePrediction(constellation, epochs);
    }
}
