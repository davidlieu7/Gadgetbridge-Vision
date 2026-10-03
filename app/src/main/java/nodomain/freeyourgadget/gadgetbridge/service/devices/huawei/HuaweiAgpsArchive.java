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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Assembles caller-supplied files for the existing Huawei ephemeris uploader. */
public final class HuaweiAgpsArchive {
    private HuaweiAgpsArchive() {
    }

    /**
     * The exact download tag and version negotiated with the watch, plus ordered archive files.
     * The directory becomes the configuration's {@code uuid} field.
     */
    public record Dataset(String downloadTag, int version, String directory,
                          Map<String, byte[]> files) {
    }

    /**
     * Generates an archive without changing the supplied payloads.
     *
     * @param retrievedAtMillis source retrieval time in Unix milliseconds, not archive creation time
     * @param datasets datasets in output order. Each map supplies its file iteration order.
     * @return bytes for {@code ephemeris.zip}
     * @throws IllegalArgumentException if metadata or payloads cannot use the existing uploader
     * @throws IOException if ZIP generation fails
     */
    public static byte[] generate(long retrievedAtMillis, List<Dataset> datasets) throws IOException {
        if (retrievedAtMillis < 0) {
            throw new IllegalArgumentException("Source retrieval time must be nonnegative");
        }
        if (datasets == null || datasets.isEmpty()) {
            throw new IllegalArgumentException("At least one dataset is required");
        }

        JsonObject config = new JsonObject();
        Set<String> tags = new HashSet<>();
        Set<String> directories = new HashSet<>();
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        boolean hasPrecisionIonosphere = false;

        for (Dataset dataset : datasets) {
            if (dataset == null) {
                throw new IllegalArgumentException("Dataset must not be null");
            }
            if (dataset.downloadTag() == null || dataset.downloadTag().isEmpty()) {
                throw new IllegalArgumentException("Download tag must not be empty");
            }
            if (!tags.add(dataset.downloadTag())) {
                throw new IllegalArgumentException("Download tags must be unique");
            }
            // The existing uploader selects the first tag containing this marker.
            if (dataset.downloadTag().contains("HW_PRECSION_ION")) {
                if (hasPrecisionIonosphere) {
                    throw new IllegalArgumentException("Only one precision-ionosphere dataset is supported");
                }
                hasPrecisionIonosphere = true;
            }
            if (dataset.version() < 1 || dataset.version() > 3) {
                throw new IllegalArgumentException("Download version must be 1, 2, or 3");
            }
            validateComponent(dataset.directory(), "Dataset directory");
            if (!directories.add(dataset.directory())) {
                throw new IllegalArgumentException("Dataset directories must be unique");
            }
            if (dataset.files() == null || dataset.files().isEmpty()) {
                throw new IllegalArgumentException("Each dataset must contain files");
            }

            JsonArray files = new JsonArray();
            for (Map.Entry<String, byte[]> file : dataset.files().entrySet()) {
                validateComponent(file.getKey(), "Filename");
                String path = dataset.directory() + "/" + file.getKey();
                if (payloads.containsKey(path)) {
                    throw new IllegalArgumentException("Archive entry paths must be unique");
                }
                if (file.getValue() == null || file.getValue().length == 0) {
                    throw new IllegalArgumentException("File payload must not be empty");
                }
                byte[] data = file.getValue().clone();
                payloads.put(path, data);
                files.add(file.getKey());
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("ver", dataset.version());
            entry.addProperty("uuid", dataset.directory());
            entry.add("files", files);
            config.add(dataset.downloadTag(), entry);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            writeEntry(zip, "time", Long.toString(retrievedAtMillis).getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "ephemeris_config.json", config.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> payload : payloads.entrySet()) {
                writeEntry(zip, payload.getKey(), payload.getValue());
            }
        }
        return output.toByteArray();
    }

    private static void validateComponent(String value, String label) {
        if (value == null || value.isEmpty() || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(label + " must be a nonempty path component");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '/' || character == '\\' || character == ';'
                    || Character.isISOControl(character)) {
                throw new IllegalArgumentException(label + " contains a forbidden character");
            }
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
