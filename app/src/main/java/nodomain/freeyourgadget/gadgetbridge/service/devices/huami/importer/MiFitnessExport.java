/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.importer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads the "data copy" export of Mi Fitness (the privacy data export), and converts the data
 * recorded by a Huami band into the Huami representation used by Gadgetbridge.
 */
public class MiFitnessExport {
    private static final Logger LOG = LoggerFactory.getLogger(MiFitnessExport.class);

    private static final String FITNESS_DATA_SUFFIX = "_hlth_center_fitness_data.csv";
    private static final String SPORT_RECORD_SUFFIX = "_hlth_center_sport_record.csv";

    // only the data recorded by the band, not the phone or the app
    private static final String HUAMI_SOURCE_PREFIX = "huami.";

    // HuamiConst activity kinds
    public static final int KIND_UNSET = -1;
    public static final int KIND_ACTIVITY = 1;
    public static final int KIND_LIGHT_SLEEP = 9;
    public static final int KIND_DEEP_SLEEP = 11;
    public static final int KIND_WAKE_UP = 12;

    public static final int HR_NONE = 255;

    public static class Minute {
        public int steps = 0;
        public int heartRate = HR_NONE;
        public int kind = KIND_UNSET;
        public boolean sleep = false;

        public int getIntensity() {
            // the export has no raw intensity, estimate it from the steps
            return Math.min(255, Math.round(steps * 1.6f));
        }
    }

    public static class Stress {
        public final long timestamp;
        public final int stress;

        Stress(final long timestamp, final int stress) {
            this.timestamp = timestamp;
            this.stress = stress;
        }
    }

    public static class RestingHeartRate {
        public final long timestamp;
        public final int utcOffset;
        public final int heartRate;

        RestingHeartRate(final long timestamp, final int utcOffset, final int heartRate) {
            this.timestamp = timestamp;
            this.utcOffset = utcOffset;
            this.heartRate = heartRate;
        }
    }

    public static class Pai {
        public final long timestamp;
        public final int utcOffset;
        public final float low;
        public final float moderate;
        public final float high;
        public final float today;
        public final float total;

        Pai(final long timestamp, final int utcOffset, final JSONObject v) {
            this.timestamp = timestamp;
            this.utcOffset = utcOffset;
            this.low = (float) v.optDouble("low_zone_pai", 0);
            this.moderate = (float) v.optDouble("medium_zone_pai", 0);
            this.high = (float) v.optDouble("high_zone_pai", 0);
            this.today = (float) v.optDouble("daily_pai", 0);
            this.total = (float) v.optDouble("total_pai", 0);
        }
    }

    public static class Workout {
        public final String sport;
        public final long startTime;
        public final long endTime;
        private final JSONObject value;

        Workout(final String sport, final JSONObject value) {
            this.sport = sport;
            this.value = value;
            this.startTime = value.optLong("start_time") * 1000L;
            this.endTime = value.optLong("end_time") * 1000L;
        }

        /**
         * The workout summary in the Huami binary format, version 512, as parsed by
         * HuamiActivitySummaryParser.
         */
        public byte[] toRawSummary(final int huamiSportCode) {
            final int duration = value.optInt("duration");
            double distance = value.optDouble("corrected_distance", 0);
            if (distance == 0) {
                distance = value.optDouble("distance", 0);
            }
            final float speed = duration > 0 ? (float) (distance / duration) : 0;
            int avgPace = value.optInt("avg_pace"); // seconds per km
            if (avgPace == 0 && distance > 0) {
                avgPace = (int) Math.round(duration * 1000 / distance);
            }

            // 140 bytes, then room for the 28 bytes of running / cycling or the 36 bytes of swimming
            final ByteBuffer buf = ByteBuffer.allocate(140 + 36).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) 512);
            buf.putShort((short) huamiSportCode);
            buf.putInt((int) (startTime / 1000));
            buf.putInt((int) (endTime / 1000));
            buf.putInt(0); // base longitude, no GPS
            buf.putInt(0); // base latitude
            buf.putInt(0); // base altitude
            buf.putInt(value.optInt("steps"));
            buf.putInt(duration);
            buf.putInt(0); // max latitude
            buf.putInt(0); // min latitude
            buf.putInt(0); // max longitude
            buf.putInt(0); // min longitude
            buf.putFloat((float) value.optDouble("calories", 0));
            buf.putFloat((float) distance);
            buf.putFloat((float) value.optDouble("rise_height", 0));
            buf.putFloat((float) value.optDouble("fall_height", 0));
            buf.putFloat(-100000); // max altitude, not recorded
            buf.putFloat(100000); // min altitude, not recorded
            buf.putFloat(0); // average altitude
            buf.putFloat((float) value.optDouble("max_speed", 0));
            buf.putFloat(0); // min speed
            buf.putFloat(speed);
            buf.putFloat(value.optInt("min_pace") / 1000f); // seconds per meter
            buf.putFloat(value.optInt("max_pace") / 1000f);
            buf.putFloat(avgPace / 1000f);
            buf.putFloat(value.optInt("max_cadence") / 60f); // steps per second
            buf.putFloat(0); // min cadence
            buf.putFloat(value.optInt("avg_cadence") / 60f);
            buf.putFloat(0); // max stride
            buf.putFloat(0); // min stride
            buf.putFloat(value.optInt("avg_stride") / 100f); // meters
            buf.putFloat((float) distance);
            buf.putInt(0);
            buf.putShort((short) value.optInt("avg_hrm"));
            buf.putShort((short) Math.min(avgPace, Short.MAX_VALUE));
            buf.putShort((short) value.optInt("avg_stride")); // cm
            buf.putShort((short) value.optInt("max_hrm"));
            return buf.array();
        }
    }

    /** One sample per minute, by unix timestamp in seconds */
    public final TreeMap<Integer, Minute> minutes = new TreeMap<>();
    public final List<Stress> stress = new ArrayList<>();
    public final List<RestingHeartRate> restingHeartRates = new ArrayList<>();
    public final List<Pai> pai = new ArrayList<>();
    public final List<Workout> workouts = new ArrayList<>();

    // UTC offset in seconds of each day, from the sleep records
    private final TreeMap<Long, Integer> dayOffsets = new TreeMap<>();
    private final List<String[]> dailyRows = new ArrayList<>();
    private boolean foundFitnessData = false;

    /**
     * Reads a Mi Fitness export: the extracted fitness data and sport record csv files, or a zip
     * holding them. The zip sent by Xiaomi is password protected and must be extracted first.
     */
    public static MiFitnessExport read(final List<File> files) throws IOException {
        final MiFitnessExport export = new MiFitnessExport();
        for (final File file : files) {
            if (isZip(file)) {
                export.readZip(file);
            } else {
                try (InputStream in = new FileInputStream(file)) {
                    export.readCsv(in);
                }
            }
        }
        if (!export.foundFitnessData) {
            throw new IOException("No Mi Fitness fitness data found");
        }
        export.resolveDailyRows();
        return export;
    }

    private void readZip(final File file) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                final String name = entry.getName();
                if (name.endsWith(FITNESS_DATA_SUFFIX) || name.endsWith(SPORT_RECORD_SUFFIX)) {
                    readCsv(zip);
                }
            }
        } catch (final ZipException e) {
            throw new IOException("Unsupported zip, extract the Mi Fitness export first: " + e.getMessage(), e);
        }
    }

    /**
     * The file names are lost when picking files on Android, the csv is identified by its columns.
     */
    private void readCsv(final InputStream in) throws IOException {
        final CsvReader csv = new CsvReader(in);
        final Map<String, Integer> header = csv.readHeader();
        if (header.containsKey("Category")) {
            readSportRecords(csv, header);
        } else if (header.containsKey("Sid") && header.containsKey("Key") && header.containsKey("Value")) {
            readFitnessData(csv, header);
        } else {
            LOG.warn("Ignoring csv with columns {}", header.keySet());
        }
    }

    private static boolean isZip(final File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K';
        }
    }

    private void readFitnessData(final CsvReader csv, final Map<String, Integer> header) throws IOException {
        foundFitnessData = true;
        final int sid = column(header, "Sid");
        final int key = column(header, "Key");
        final int value = column(header, "Value");

        String[] row;
        while ((row = csv.readRow()) != null) {
            if (row.length <= value || !row[sid].startsWith(HUAMI_SOURCE_PREFIX)) {
                continue;
            }
            try {
                final JSONObject v = new JSONObject(row[value]);
                switch (row[key]) {
                    case "steps":
                        minute(v.getLong("time")).steps += v.getInt("steps");
                        break;
                    case "heart_rate":
                        final int bpm = v.getInt("bpm");
                        if (bpm >= 10 && bpm <= 250) {
                            minute(v.getLong("time")).heartRate = bpm;
                        }
                        break;
                    case "stress":
                        stress.add(new Stress(v.getLong("time") * 1000L, v.getInt("stress")));
                        break;
                    case "watch_night_sleep":
                    case "watch_daytime_sleep":
                        readSleep(v);
                        break;
                    case "resting_heart_rate":
                    case "pai":
                        // need the timezone of the day, resolved once all sleep records are read
                        dailyRows.add(new String[]{row[key], row[value]});
                        break;
                }
            } catch (final JSONException e) {
                LOG.warn("Failed to parse {} value: {}", row[key], row[value], e);
            }
        }
    }

    private void readSleep(final JSONObject v) throws JSONException {
        if (v.has("timezone")) {
            // in quarters of an hour
            dayOffsets.put(v.getLong("date_time"), v.getInt("timezone") * 900);
        }
        final JSONArray items = v.optJSONArray("items");
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            final JSONObject item = items.getJSONObject(i);
            final int kind;
            switch (item.getInt("state")) {
                case 2:
                    kind = KIND_DEEP_SLEEP;
                    break;
                case 3:
                case 4: // REM, Huami devices without REM support only know light and deep sleep
                    kind = KIND_LIGHT_SLEEP;
                    break;
                case 5:
                    kind = KIND_WAKE_UP;
                    break;
                default:
                    continue;
            }
            final long start = item.getLong("start_time");
            final long end = item.getLong("end_time");
            for (long ts = start - start % 60; ts < end; ts += 60) {
                final Minute minute = minute(ts);
                minute.kind = kind;
                minute.sleep = true;
            }
        }
    }

    private void resolveDailyRows() {
        for (final String[] row : dailyRows) {
            try {
                final JSONObject v = new JSONObject(row[1]);
                // the day is the local midnight, expressed as if it was UTC
                final long day = v.getLong("date_time");
                final int offset = getUtcOffset(day);
                final long timestamp = (day - offset) * 1000L;
                if ("pai".equals(row[0])) {
                    pai.add(new Pai(timestamp, offset * 1000, v));
                } else {
                    restingHeartRates.add(new RestingHeartRate(timestamp, offset * 1000, v.getInt("bpm")));
                }
            } catch (final JSONException e) {
                LOG.warn("Failed to parse {} value: {}", row[0], row[1], e);
            }
        }
        dailyRows.clear();

        for (final Minute minute : minutes.values()) {
            if (!minute.sleep && (minute.steps > 0 || minute.heartRate != HR_NONE)) {
                minute.kind = KIND_ACTIVITY;
            }
        }
    }

    private int getUtcOffset(final long day) {
        final Map.Entry<Long, Integer> known = dayOffsets.floorEntry(day);
        if (known != null) {
            return known.getValue();
        }
        return TimeZone.getDefault().getOffset(day * 1000L) / 1000;
    }

    private void readSportRecords(final CsvReader csv, final Map<String, Integer> header) throws IOException {
        final int key = column(header, "Key");
        final int value = column(header, "Value");

        String[] row;
        while ((row = csv.readRow()) != null) {
            if (row.length <= value) {
                continue;
            }
            try {
                workouts.add(new Workout(row[key], new JSONObject(row[value])));
            } catch (final JSONException e) {
                LOG.warn("Failed to parse workout {}: {}", row[key], row[value], e);
            }
        }
    }

    private Minute minute(final long timestamp) {
        final int ts = (int) (timestamp - timestamp % 60);
        Minute minute = minutes.get(ts);
        if (minute == null) {
            minute = new Minute();
            minutes.put(ts, minute);
        }
        return minute;
    }

    private static int column(final Map<String, Integer> header, final String name) throws IOException {
        final Integer index = header.get(name);
        if (index == null) {
            throw new IOException("Missing column " + name);
        }
        return index;
    }

    /**
     * Minimal RFC 4180 reader, the values are json with quotes escaped as "".
     */
    private static class CsvReader {
        private final Reader reader;
        private boolean eof = false;

        CsvReader(final InputStream in) {
            // the zip stream must stay open for the next entries
            this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        Map<String, Integer> readHeader() throws IOException {
            final String[] row = readRow();
            if (row == null) {
                throw new IOException("Empty csv");
            }
            final Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < row.length; i++) {
                header.put(row[i].replace("﻿", "").trim(), i);
            }
            return header;
        }

        String[] readRow() throws IOException {
            if (eof) {
                return null;
            }
            final List<String> fields = new ArrayList<>();
            final StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean any = false;
            int c;
            while ((c = reader.read()) != -1) {
                any = true;
                if (quoted) {
                    if (c == '"') {
                        reader.mark(1);
                        final int next = reader.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            quoted = false;
                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        field.append((char) c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else if (c == '\n') {
                    fields.add(field.toString());
                    return fields.toArray(new String[0]);
                } else if (c != '\r') {
                    field.append((char) c);
                }
            }
            eof = true;
            if (!any) {
                return null;
            }
            fields.add(field.toString());
            return fields.toArray(new String[0]);
        }
    }
}
