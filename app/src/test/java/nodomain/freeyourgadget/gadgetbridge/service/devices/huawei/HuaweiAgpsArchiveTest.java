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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiAgpsArchive.Dataset;

public class HuaweiAgpsArchiveTest {
    @Test
    public void archiveMatchesUploaderContractAndRetainsSourceAge() throws IOException {
        final Map<String, byte[]> predictionFiles = new LinkedHashMap<>();
        for (final HuaweiAgpsFileGenerator.Constellation constellation : HuaweiAgpsFileGenerator.Constellation.values()) {
            predictionFiles.put(constellation.getFileName(), new byte[]{1, 2, 3});
        }
        predictionFiles.put("HW_PGNSS_EXTRA", new byte[]{4, 5, 6});
        final Dataset agnss = new Dataset("agnss/tag", 3, "live",
                Map.of("HW_AGNSS_RTCM_33", new byte[]{7, 8, 9}));
        final Dataset pgnss = new Dataset("pgnss/tag;", 3, "predicted", predictionFiles);
        final Map<String, byte[]> entries = unzip(HuaweiAgpsArchive.generate(123456789L, List.of(agnss, pgnss)));

        assertEquals(9, entries.size());
        assertEquals("123456789", new String(entries.get("time"), StandardCharsets.UTF_8));
        final JsonObject config = JsonParser.parseString(new String(entries.get("ephemeris_config.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(2, config.size());
        for (final Dataset dataset : List.of(agnss, pgnss)) {
            final JsonObject metadata = config.getAsJsonObject(dataset.downloadTag());
            assertEquals(dataset.version(), metadata.get("ver").getAsInt());
            assertEquals(dataset.directory(), metadata.get("uuid").getAsString());
            final List<String> names = new ArrayList<>();
            metadata.getAsJsonArray("files").forEach(name -> names.add(name.getAsString()));
            assertEquals(new ArrayList<>(dataset.files().keySet()), names);
            for (final String name : names) {
                assertArrayEquals(dataset.files().get(name), entries.get(dataset.directory() + "/" + name));
            }
        }
        assertEquals("time", new ArrayList<>(entries.keySet()).get(0));
        assertEquals("ephemeris_config.json", new ArrayList<>(entries.keySet()).get(1));
    }

    @Test
    public void archiveRejectsAmbiguousMetadataAndUnsafeNames() {
        final Dataset valid = dataset("file", new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(-1, List.of(valid)));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(1, List.of(valid, valid)));
        for (final int version : new int[]{0, 4}) {
            assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(1,
                    List.of(new Dataset("tag", version, "data", Map.of("file", new byte[]{1})))));
        }
        for (final String name : List.of("", ".", "..", "a/b", "a\\b", "a;b", "a\n")) {
            assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(1,
                    List.of(dataset(name, new byte[]{1}))));
            assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(1,
                    List.of(new Dataset("tag", 3, name, Map.of("file", new byte[]{1})))));
        }
    }

    @Test
    public void archiveRejectsAmbiguousPrecisionIonosphereTags() throws IOException {
        final Dataset first = new Dataset("first/HW_PRECSION_ION", 3, "first", Map.of("file", new byte[]{1}));
        final Dataset second = new Dataset("second/HW_PRECSION_ION", 3, "second", Map.of("file", new byte[]{2}));
        assertEquals(3, unzip(HuaweiAgpsArchive.generate(1, List.of(first))).size());
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(1, List.of(first, second)));
    }

    @Test
    public void archiveRejectsEmptyDataAndExistingUploaderZeroCrcLimitation() {
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(1,
                List.of(new Dataset("tag", 3, "data", Map.of()))));
        assertThrows(IllegalArgumentException.class, () -> HuaweiAgpsArchive.generate(1,
                List.of(dataset("file", new byte[0]))));
    }

    private static Dataset dataset(final String name, final byte[] data) {
        return new Dataset("tag", 3, "data", Map.of(name, data));
    }

    private static Map<String, byte[]> unzip(final byte[] archive) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
