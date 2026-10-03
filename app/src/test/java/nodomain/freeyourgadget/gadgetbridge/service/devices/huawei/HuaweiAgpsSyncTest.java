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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendEphemerisDataRequestResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendEphemerisFileConsultResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendEphemerisFileListResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendEphemerisFileUploadChunk;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendEphemerisOperatorResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendEphemerisParameterConsultRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendEphemerisSingleFileInfoResponse;
import nodomain.freeyourgadget.gadgetbridge.util.CheckSums;

public class HuaweiAgpsSyncTest {
    private static final long MINUTE = 60_000L;
    // Synthetic framed payload. Validation belongs to HuaweiAgpsDownloaderTest.
    private static final byte[] RTCM = {(byte) 0xd3, 0, 4, 0x3f, (byte) 0xb0, 0, 1, (byte) 0xdf, 0x2f, (byte) 0xc6};

    private static final Map<String, byte[]> AGNSS = Map.of(HuaweiAgpsDownloader.FILE_NAME, RTCM);
    private static final Map<String, byte[]> PGNSS = pgnssFiles();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_000);
    private final List<HuaweiAgpsDownloader.DatasetCallback> downloads = new ArrayList<>();
    private final List<String> requestedTags = new ArrayList<>();
    private HuaweiAgpsDownloader downloader;
    private TestManager manager;
    private File archive;

    @Before
    public void setUp() {
        final Context context = mock(Context.class);
        when(context.getExternalFilesDir(null)).thenReturn(folder.getRoot());
        final HuaweiSupportProvider support = mock(HuaweiSupportProvider.class);
        when(support.getContext()).thenReturn(context);
        support.deviceMac = "00:00:00:00:00:00";
        downloader = mock(HuaweiAgpsDownloader.class);
        doAnswer(invocation -> {
            requestedTags.add(invocation.getArgument(0));
            downloads.add(invocation.getArgument(1));
            return null;
        }).when(downloader).download(anyString(), any());
        manager = new TestManager(support, downloader, clock::get);
        archive = new File(folder.getRoot(), "ephemeris.zip");
    }

    @Test
    public void disabledAndUnsupportedRequestsNeverDownload() {
        manager.enabled = false;
        consult();
        manager.enabled = true;
        manager.handleParameterConsultResponse(0, (byte) 2, HuaweiAgpsDownloader.TAG);
        manager.handleParameterConsultResponse(0, (byte) 3, HuaweiAgpsDownloader.PGNSS_TAG.replace(";", ""));
        manager.handleParameterConsultResponse(0, (byte) 3, "https://untrusted.invalid/gnss");
        assertTrue(downloads.isEmpty());
        assertEquals(List.of((byte) 5, (byte) 5, (byte) 5, (byte) 5), manager.statuses);
    }

    @Test
    public void operatorConsultsWithoutArchiveAndWaitsForDownloadBeforeReady() throws Exception {
        try (MockedConstruction<SendEphemerisOperatorResponse> operators = mockConstruction(SendEphemerisOperatorResponse.class);
             MockedConstruction<SendEphemerisParameterConsultRequest> consults = mockConstruction(SendEphemerisParameterConsultRequest.class)) {
            manager.handleOperatorRequest((byte) 1, 0);
            assertEquals(1, operators.constructed().size());
            assertEquals(1, consults.constructed().size());
            verify(operators.constructed().get(0)).doPerform();
            verify(consults.constructed().get(0)).doPerform();
        }
        assertTrue(downloads.isEmpty());
        consult();
        assertEquals(1, downloads.size());
        assertTrue(manager.statuses.isEmpty());
        downloads.get(0).onSuccess(AGNSS);
        assertEquals(List.of((byte) 3), manager.statuses);
        assertOfferedFile(HuaweiAgpsDownloader.FILE_NAME);
    }

    @Test
    public void cacheReuseDoesNotRenewItsThirtyMinuteAge() {
        consult();
        downloads.get(0).onSuccess(AGNSS);
        clock.addAndGet(29 * MINUTE);
        consult();
        assertEquals(1, downloads.size());
        assertEquals(List.of((byte) 3, (byte) 3), manager.statuses);
        clock.addAndGet(MINUTE);
        consult();
        assertEquals(2, downloads.size());
        assertEquals(2, manager.statuses.size());
        downloads.get(1).onSuccess(AGNSS);
        assertEquals(List.of((byte) 3, (byte) 3, (byte) 3), manager.statuses);
    }

    @Test
    public void failedDownloadReportsFailureAndOffersNoFile() {
        consult();
        downloads.get(0).onFailure(new IOException("Synthetic network failure"));
        assertEquals(List.of((byte) 5), manager.statuses);
        try (MockedConstruction<SendEphemerisFileListResponse> responses = mockConstruction(SendEphemerisFileListResponse.class)) {
            manager.handleFileSendRequest((byte) 1, "test");
            assertTrue(responses.constructed().isEmpty());
        }
    }

    @Test
    public void replacementRejectsBothOldSuccessAndOldFailure() {
        consult();
        consultPgnss();
        assertEquals(List.of(HuaweiAgpsDownloader.TAG, HuaweiAgpsDownloader.PGNSS_TAG), requestedTags);
        completeStaleDownload(downloads.get(0));
        assertTrue(manager.statuses.isEmpty());
        downloads.get(1).onSuccess(PGNSS);
        assertEquals(List.of((byte) 3), manager.statuses);
        assertOfferedFile(String.join(";", PGNSS.keySet()));
    }

    @Test
    public void newOperatorRequestInvalidatesPendingDownload() {
        consult();
        try (MockedConstruction<SendEphemerisOperatorResponse> ignoredOperator = mockConstruction(SendEphemerisOperatorResponse.class);
             MockedConstruction<SendEphemerisParameterConsultRequest> ignoredConsult = mockConstruction(SendEphemerisParameterConsultRequest.class)) {
            manager.handleOperatorRequest((byte) 1, 0);
        }
        completeStaleDownload(downloads.get(0));
        assertTrue(manager.statuses.isEmpty());
        consult();
        assertEquals(2, downloads.size());
    }

    @Test
    public void disablingRejectsPendingCallbackAndClearsCache() {
        consult();
        manager.enabled = false;
        manager.onAutomaticUpdatesChanged();
        completeStaleDownload(downloads.get(0));
        assertTrue(manager.statuses.isEmpty());
        manager.enabled = true;
        consult();
        assertEquals(2, downloads.size());
        downloads.get(1).onSuccess(AGNSS);
        manager.enabled = false;
        manager.onAutomaticUpdatesChanged();
        manager.enabled = true;
        consult();
        assertEquals(3, downloads.size());
        assertEquals(List.of((byte) 3), manager.statuses);
    }

    @Test
    public void disposalRejectsPendingCallbackAndNewConsult() {
        consult();
        manager.dispose();
        verify(downloader).dispose();
        completeStaleDownload(downloads.get(0));
        consult();
        assertTrue(manager.statuses.isEmpty());
        assertEquals(1, downloads.size());
    }

    @Test
    public void automaticAgnssPreservesManualPgnssAndAcceptsZeroFileCrc() throws Exception {
        final String filename = "HW_PGNSS_GPS";
        final byte[] payload = {0};
        assertEquals(0, CheckSums.getCRC16(payload, 0));
        final byte[] originalArchive = writeArchive(HuaweiAgpsDownloader.PGNSS_TAG, filename, payload);
        loadArchive();
        consult();
        downloads.get(0).onSuccess(AGNSS);
        manager.handleParameterConsultResponse(0, (byte) 3, HuaweiAgpsDownloader.PGNSS_TAG);
        assertEquals(1, downloads.size());
        assertEquals(List.of((byte) 3, (byte) 3), manager.statuses);
        assertOfferedFile(filename);
        assertArrayEquals(originalArchive, Files.readAllBytes(archive.toPath()));
        assertFileMetadata(filename, payload.length, (short) 0);
    }

    @Test
    public void matchingManualAgnssTakesPriorityOverDownload() throws Exception {
        final byte[] originalArchive = writeArchive(HuaweiAgpsDownloader.TAG, HuaweiAgpsDownloader.FILE_NAME, RTCM);
        loadArchive();
        consult();
        assertTrue(downloads.isEmpty());
        assertEquals(List.of((byte) 3), manager.statuses);
        assertOfferedFile(HuaweiAgpsDownloader.FILE_NAME);
        assertArrayEquals(originalArchive, Files.readAllBytes(archive.toPath()));
    }

    @Test
    public void pgnssOffersTheCompleteDatasetAndSelectsEachExactFilename() {
        consultPgnss();
        assertEquals(List.of(HuaweiAgpsDownloader.PGNSS_TAG), requestedTags);
        assertTrue(manager.statuses.isEmpty());
        downloads.get(0).onSuccess(PGNSS);
        assertEquals(List.of((byte) 3), manager.statuses);
        assertOfferedFile(String.join(";", PGNSS.keySet()));
        for (Map.Entry<String, byte[]> file : PGNSS.entrySet()) {
            assertFileMetadata(file.getKey(), file.getValue().length, (short) CheckSums.getCRC16(file.getValue(), 0));
        }
        final List<List<?>> arguments = new ArrayList<>();
        try (MockedConstruction<SendEphemerisSingleFileInfoResponse> ignored = mockConstruction(SendEphemerisSingleFileInfoResponse.class,
                (mock, context) -> arguments.add(context.arguments()))) {
            manager.handleSingleFileIncomingRequest("HW_PGNSS_UNKNOWN");
            assertEquals(100001, arguments.get(0).get(1));
            assertEquals(0, arguments.get(0).get(2));
        }
    }

    @Test
    public void agnssAndPgnssCacheAgesStayIndependent() {
        consult();
        downloads.get(0).onSuccess(AGNSS);
        clock.addAndGet(29 * MINUTE);
        consultPgnss();
        downloads.get(1).onSuccess(PGNSS);
        clock.addAndGet(MINUTE);
        consult();
        assertEquals(3, downloads.size());
        downloads.get(2).onSuccess(AGNSS);
        consultPgnss();
        assertEquals(3, downloads.size());
        assertOfferedFile(String.join(";", PGNSS.keySet()));
        clock.addAndGet(29 * MINUTE);
        consultPgnss();
        assertEquals(4, downloads.size());
        assertEquals(List.of(HuaweiAgpsDownloader.TAG, HuaweiAgpsDownloader.PGNSS_TAG,
                HuaweiAgpsDownloader.TAG, HuaweiAgpsDownloader.PGNSS_TAG), requestedTags);
    }

    @Test
    public void disablingDropsAutomaticTransfersButRetainsManualTransfers() throws Exception {
        consultPgnss();
        downloads.get(0).onSuccess(PGNSS);
        manager.enabled = false;
        manager.onAutomaticUpdatesChanged();
        try (MockedConstruction<SendEphemerisFileListResponse> responses = mockConstruction(SendEphemerisFileListResponse.class)) {
            manager.handleFileSendRequest((byte) 1, "test");
            assertTrue(responses.constructed().isEmpty());
        }
        writeArchive(HuaweiAgpsDownloader.PGNSS_TAG, "HW_PGNSS_GPS", new byte[]{1});
        loadArchive();
        consultPgnss();
        manager.onAutomaticUpdatesChanged();
        assertOfferedFile("HW_PGNSS_GPS");
        assertEquals(1, downloads.size());
    }

    @Test
    public void invalidTransferStatesNeverAcknowledgeOrCopyData() {
        consult();
        downloads.get(0).onSuccess(AGNSS);
        // Each row gives transfer size, maximum chunk size, file selection, and requested offset.
        for (int[] values : new int[][]{
                {0, 1024, 0, 0}, {-1, 1024, 0, 0}, {128, 0, 0, 0}, {128, -1, 0, 0},
                {128, 1024, 0, 0}, {128, 1024, 1, -1}, {128, 1024, 1, -2},
                {128, 1024, 1, RTCM.length + 1}, {128, 1024, 1, Integer.MAX_VALUE}
        }) {
            consult();
            try (MockedConstruction<SendEphemerisFileConsultResponse> consults = mockConstruction(SendEphemerisFileConsultResponse.class);
                 MockedConstruction<SendEphemerisSingleFileInfoResponse> ignored = mockConstruction(SendEphemerisSingleFileInfoResponse.class);
                 MockedConstruction<SendEphemerisDataRequestResponse> acknowledgements = mockConstruction(SendEphemerisDataRequestResponse.class);
                 MockedConstruction<SendEphemerisFileUploadChunk> chunks = mockConstruction(SendEphemerisFileUploadChunk.class)) {
                manager.handleFileConsultIncomingRequest(0, "1", (byte) 0, (short) values[0], values[1], (short) 10, (byte) 1);
                assertEquals(values[0] > 0 && values[1] > 0 ? 1 : 0, consults.constructed().size());
                if (values[2] != 0) {
                    manager.handleSingleFileIncomingRequest(HuaweiAgpsDownloader.FILE_NAME);
                }
                manager.handleDataRequestIncomingRequest(0, HuaweiAgpsDownloader.FILE_NAME, values[3], 1, (byte) 0);
                assertTrue(acknowledgements.constructed().isEmpty());
                assertTrue(chunks.constructed().isEmpty());
            }
        }
    }

    @Test
    public void offsetAtEndOfFileRetainsTheExistingEmptyChunkResponse() {
        consult();
        downloads.get(0).onSuccess(AGNSS);
        assertFileMetadata(HuaweiAgpsDownloader.FILE_NAME, RTCM.length, (short) CheckSums.getCRC16(RTCM, 0));
        final List<List<?>> arguments = new ArrayList<>();
        try (MockedConstruction<SendEphemerisDataRequestResponse> acknowledgements = mockConstruction(SendEphemerisDataRequestResponse.class);
             MockedConstruction<SendEphemerisFileUploadChunk> chunks = mockConstruction(SendEphemerisFileUploadChunk.class,
                     (mock, context) -> arguments.add(context.arguments()))) {
            manager.handleDataRequestIncomingRequest(0, HuaweiAgpsDownloader.FILE_NAME, RTCM.length, 1, (byte) 0);
            assertEquals(1, acknowledgements.constructed().size());
            assertEquals(1, chunks.constructed().size());
            assertArrayEquals(new byte[0], (byte[]) arguments.get(0).get(1));
            assertEquals(0, arguments.get(0).get(3));
        }
    }

    private static Map<String, byte[]> pgnssFiles() {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : List.of("GPS", "BDS", "GLONASS", "GALILEO", "QZS", "EXTRA")) {
            final byte[] bytes = new byte[files.size() + 1];
            bytes[0] = (byte) bytes.length;
            files.put("HW_PGNSS_" + name, bytes);
        }
        return files;
    }

    private void consultPgnss() {
        manager.handleParameterConsultResponse(0, (byte) 3, HuaweiAgpsDownloader.PGNSS_TAG);
    }

    private void consult() {
        manager.handleParameterConsultResponse(0, (byte) 3, HuaweiAgpsDownloader.TAG);
    }

    private static void completeStaleDownload(HuaweiAgpsDownloader.DatasetCallback callback) {
        callback.onSuccess(AGNSS);
        callback.onFailure(new IOException("Late failure"));
    }

    private byte[] writeArchive(String tag, String filename, byte[] data) throws IOException {
        final byte[] bytes = HuaweiAgpsArchive.generate(System.currentTimeMillis(), List.of(
                new HuaweiAgpsArchive.Dataset(tag, 3, "manual", Map.of(filename, data))));
        Files.write(archive.toPath(), bytes);
        return bytes;
    }

    private void loadArchive() {
        try (MockedConstruction<SendEphemerisOperatorResponse> ignoredOperator = mockConstruction(SendEphemerisOperatorResponse.class);
             MockedConstruction<SendEphemerisParameterConsultRequest> ignoredConsult = mockConstruction(SendEphemerisParameterConsultRequest.class)) {
            manager.handleOperatorRequest((byte) 1, 0);
        }
    }

    private void assertOfferedFile(String expectedFilename) {
        final List<List<?>> arguments = new ArrayList<>();
        try (MockedConstruction<SendEphemerisFileListResponse> responses = mockConstruction(SendEphemerisFileListResponse.class,
                (mock, context) -> arguments.add(context.arguments()))) {
            manager.handleFileSendRequest((byte) 1, "test");
            assertEquals(1, responses.constructed().size());
            assertEquals(0, arguments.get(0).get(1));
            assertEquals(expectedFilename, arguments.get(0).get(2));
        }
    }

    private void assertFileMetadata(String filename, int expectedSize, short expectedCrc) {
        final List<List<?>> arguments = new ArrayList<>();
        try (MockedConstruction<SendEphemerisFileConsultResponse> ignored = mockConstruction(SendEphemerisFileConsultResponse.class);
             MockedConstruction<SendEphemerisSingleFileInfoResponse> responses = mockConstruction(SendEphemerisSingleFileInfoResponse.class,
                     (mock, context) -> arguments.add(context.arguments()))) {
            manager.handleFileConsultIncomingRequest(0, "1", (byte) 0, (short) 128, 1024, (short) 10, (byte) 1);
            manager.handleSingleFileIncomingRequest(filename);
            assertEquals(1, responses.constructed().size());
            assertEquals(0, arguments.get(0).get(1));
            assertEquals(expectedSize, arguments.get(0).get(2));
            assertEquals(expectedCrc, arguments.get(0).get(3));
        }
    }

    private static final class TestManager extends HuaweiEphemerisManager {
        boolean enabled = true;
        final List<Byte> statuses = new ArrayList<>();

        TestManager(HuaweiSupportProvider support, HuaweiAgpsDownloader downloader, LongSupplier clock) {
            super(support, downloader, clock);
        }

        @Override
        boolean automaticUpdatesEnabled() {
            return enabled;
        }

        @Override
        void sendFileStatus(byte status) {
            statuses.add(status);
        }
    }
}
