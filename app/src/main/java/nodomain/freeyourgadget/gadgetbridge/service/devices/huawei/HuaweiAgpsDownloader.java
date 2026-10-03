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

import android.os.ParcelFileDescriptor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.InternetHelperSingleton;
import nodomain.freeyourgadget.gadgetbridge.util.PermissionsUtils;
import nodomain.freeyourgadget.internethelper.aidl.http.HttpRequest;
import nodomain.freeyourgadget.internethelper.aidl.http.HttpResponse;
import nodomain.freeyourgadget.internethelper.aidl.http.IHttpCallback;
import nodomain.freeyourgadget.internethelper.aidl.http.IHttpService;

/**
 * Downloads Huawei AGNSS data or PGNSS seeds through Internet Helper.
 * One deadline covers all HTTP requests and PGNSS conversion.
 * AGNSS validation checks framing and checksums, not satellite health or ephemeris age.
 */
public final class HuaweiAgpsDownloader {
    public static final String TAG = "higeo/v1/gnssinfo?type=0x0004/HW_AGNSS";
    public static final String PGNSS_TAG = "com.huawei.higeo_dataModule_PGNSSConfig?type=HW_EEV2/HW_PGNSS_PRED;";
    public static final String FILE_NAME = "HW_AGNSS_RTCM_33";
    private static final String URL = "https://geo-dre.platform.dbankcloud.com/higeo/v1/gnssinfo?type=0x0004";
    static final String PGNSS_MANIFEST_URL = "https://configserver-dre.platform.hicloud.com/servicesupport/updateserver/data/com.huawei.higeo_dataModule_PGNSSConfig?type=HW_EEV2";
    // These are local resource limits. The deadline is not a measured watch timeout.
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    static final int MAX_RTCM_BYTES = 128 * 1024;
    static final int MAX_MANIFEST_BYTES = 32 * 1024;
    static final int MAX_SEED_BYTES = 1024 * 1024;
    private static final long TIMEOUT_MILLIS = 30_000;

    public interface Callback {
        void onSuccess(byte[] framedRtcm);
        void onFailure(Exception exception);
    }

    public interface DatasetCallback {
        void onSuccess(Map<String, byte[]> files);
        void onFailure(Exception exception);
    }

    interface ResponseCallback {
        void onResponse(int status, InputStream body);
        void onFailure(Exception exception);
    }

    interface Transport {
        void request(String url, Map<String, String> headers, ResponseCallback callback) throws Exception;
    }

    interface SeedConverter {
        Map<String, byte[]> generate(byte[] seed, long unixMillis) throws IOException;
    }

    private interface ResponseHandler {
        void accept(byte[] bytes) throws IOException;
    }

    private final Object lock = new Object();
    private final Transport transport;
    private final long timeoutMillis;
    private final SeedConverter seedConverter;
    private final LongSupplier unixMillis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private Request active;
    private boolean disposed;

    private static final class Request {
        final DatasetCallback callback;
        final List<Future<?>> operations = new ArrayList<>();
        Exchange exchange;
        Future<?> timeout;

        Request(final DatasetCallback callback) {
            this.callback = callback;
        }
    }

    private static final class Exchange {
        InputStream body;
        boolean responseReceived;
    }

    public HuaweiAgpsDownloader() {
        this(HuaweiAgpsDownloader::requestFromHelper, TIMEOUT_MILLIS);
    }

    HuaweiAgpsDownloader(final Transport transport, final long timeoutMillis) {
        this(transport, timeoutMillis, HuaweiPgnssGenerator::generate, System::currentTimeMillis);
    }

    HuaweiAgpsDownloader(final Transport transport, final long timeoutMillis,
                         final SeedConverter seedConverter, final LongSupplier unixMillis) {
        this.transport = Objects.requireNonNull(transport);
        this.seedConverter = Objects.requireNonNull(seedConverter);
        this.unixMillis = Objects.requireNonNull(unixMillis);
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("The download timeout must be positive");
        }
        this.timeoutMillis = timeoutMillis;
    }

    public static boolean supports(final int version, final String tag) {
        return version == 3 && (TAG.equals(tag) || PGNSS_TAG.equals(tag));
    }

    /** Downloads the AGNSS RTCM file. */
    public void download(final Callback callback) {
        Objects.requireNonNull(callback);
        download(TAG, new DatasetCallback() {
            @Override
            public void onSuccess(final Map<String, byte[]> files) {
                callback.onSuccess(files.get(FILE_NAME));
            }

            @Override
            public void onFailure(final Exception exception) {
                callback.onFailure(exception);
            }
        });
    }

    /** Starts one download. A new download cancels the pending download. Callbacks run on background threads. */
    public void download(final String tag, final DatasetCallback callback) {
        if (!supports(3, tag)) {
            throw new IllegalArgumentException("The AGPS download tag is unsupported");
        }
        final Request request = new Request(Objects.requireNonNull(callback));
        final Request previous;
        synchronized (lock) {
            if (disposed) {
                throw new IllegalStateException("The AGPS downloader is disposed");
            }
            previous = active;
            active = request;
            request.timeout = timer.schedule(() -> finish(request, null,
                    new TimeoutException("The AGPS download timed out"), true), timeoutMillis, TimeUnit.MILLISECONDS);
            request.operations.add(worker.submit(() -> start(request, tag)));
        }
        close(previous);
    }

    /**
     * Cancels the pending request without a callback. A callback already in progress can still finish.
     * Internet Helper has no cancellation API, so its dispatched HTTP request can continue.
     */
    public void cancel() {
        final Request request;
        synchronized (lock) {
            request = active;
            active = null;
        }
        close(request);
    }

    /** Cancels the pending request and releases this downloader's executors. */
    public void dispose() {
        final Request request;
        synchronized (lock) {
            disposed = true;
            request = active;
            active = null;
        }
        close(request);
        worker.shutdownNow();
        timer.shutdownNow();
    }

    private void start(final Request request, final String tag) {
        if (PGNSS_TAG.equals(tag)) {
            issue(request, PGNSS_MANIFEST_URL, Map.of("App-ID", "HealthApp", "traceId", UUID.randomUUID().toString()),
                    MAX_MANIFEST_BYTES, bytes -> {
                        final PgnssManifest manifest = parsePgnssManifest(bytes);
                        issue(request, manifest.downloadUrl(), Map.of(), MAX_SEED_BYTES, seed -> {
                            verifyPgnssSeed(seed, manifest);
                            if (!isActive(request)) {
                                return;
                            }
                            final Map<String, byte[]> files = seedConverter.generate(seed, unixMillis.getAsLong());
                            finish(request, files, null, false);
                        });
                    });
        } else {
            issue(request, URL, Map.of("Content-Type", "application/json", "X-Device-Type", "SportsHealth",
                            "X-Request-ID", UUID.randomUUID().toString()), MAX_RESPONSE_BYTES,
                    bytes -> finish(request, Map.of(FILE_NAME, decodeResponse(new ByteArrayInputStream(bytes))), null, false));
        }
    }

    private boolean isActive(final Request request) {
        synchronized (lock) {
            return active == request && !Thread.currentThread().isInterrupted();
        }
    }

    private void issue(final Request request, final String url, final Map<String, String> headers,
                       final int maximum, final ResponseHandler handler) {
        final Exchange exchange = new Exchange();
        synchronized (lock) {
            if (active != request || Thread.currentThread().isInterrupted()) {
                return;
            }
            request.exchange = exchange;
        }
        try {
            transport.request(url, headers, new ResponseCallback() {
                @Override
                public void onResponse(final int status, final InputStream body) {
                    receive(request, exchange, status, body, maximum, handler);
                }

                @Override
                public void onFailure(final Exception exception) {
                    dispatchFailure(request, exchange, exception);
                }
            });
        } catch (final Exception exception) {
            dispatchFailure(request, exchange, exception);
        }
    }

    private void receive(final Request request, final Exchange exchange, final int status, final InputStream body,
                         final int maximum, final ResponseHandler handler) {
        synchronized (lock) {
            if (active == request && request.exchange == exchange && !exchange.responseReceived) {
                exchange.responseReceived = true;
                exchange.body = body;
                try {
                    request.operations.add(worker.submit(() -> {
                        try {
                            final byte[] bytes;
                            try {
                                if (status != 200 || body == null) {
                                    throw new IOException("The AGPS server returned an invalid response (HTTP " + status + ")");
                                }
                                bytes = readBounded(body, maximum);
                            } finally {
                                close(body);
                                synchronized (lock) {
                                    exchange.body = null;
                                }
                            }
                            if (isActive(request)) {
                                handler.accept(bytes);
                            }
                        } catch (final Exception exception) {
                            finish(request, null, exception, false);
                        }
                    }));
                    return;
                } catch (final RejectedExecutionException ignored) {
                    // Disposal can race a response. Close the descriptor below.
                }
            }
        }
        close(body);
    }

    private void dispatchFailure(final Request request, final Exchange exchange, final Exception exception) {
        synchronized (lock) {
            if (active != request || request.exchange != exchange || exchange.responseReceived) {
                return;
            }
            exchange.responseReceived = true;
        }
        try {
            timer.execute(() -> finish(request, null, exception, true));
        } catch (final RejectedExecutionException ignored) {
            // Disposal suppresses pending callbacks.
        }
    }

    private void finish(final Request request, final Map<String, byte[]> result, final Exception exception,
                        final boolean interrupt) {
        synchronized (lock) {
            if (active != request) {
                return;
            }
            active = null;
        }
        close(request, interrupt);
        if (exception == null) {
            request.callback.onSuccess(result);
        } else {
            request.callback.onFailure(exception);
        }
    }

    private void close(final Request request) {
        close(request, true);
    }

    private void close(final Request request, final boolean interrupt) {
        if (request == null) {
            return;
        }
        final List<Future<?>> operations;
        final InputStream body;
        synchronized (lock) {
            operations = new ArrayList<>(request.operations);
            body = request.exchange == null ? null : request.exchange.body;
        }
        if (request.timeout != null) {
            request.timeout.cancel(false);
        }
        for (final Future<?> operation : operations) {
            operation.cancel(interrupt);
        }
        close(body);
    }

    private static void close(final InputStream body) {
        if (body != null) {
            try {
                body.close();
            } catch (final IOException ignored) {
                // Closing a cancelled or completed response is best effort.
            }
        }
    }

    private static void requestFromHelper(final String url, final Map<String, String> headers,
                                          final ResponseCallback callback) throws Exception {
        if (!AndroidUtils.isPackageInstalled(PermissionsUtils.PACKAGE_INTERNET_HELPER)
                || !PermissionsUtils.checkPermission(GBApplication.getContext(), PermissionsUtils.CUSTOM_PERM_INTERNET_HELPER)) {
            throw new IOException("Internet Helper is not installed or its permission is not granted");
        }
        if (!InternetHelperSingleton.INSTANCE.ensureInternetHelperBoundBlocking(3_000)) {
            throw new IOException("Internet Helper is not available");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("The AGPS download was cancelled");
        }
        final IHttpService service = InternetHelperSingleton.INSTANCE.getHttpService();
        if (service == null) {
            throw new IOException("Internet Helper disconnected");
        }
        final HttpRequest request = new HttpRequest(url, HttpRequest.Method.GET, headers, null, false);
        service.send(request, new IHttpCallback.Stub() {
            @Override
            public void onResponse(final HttpResponse response) {
                callback.onResponse(response == null ? 0 : response.getStatus(),
                        response == null || response.getBody() == null ? null
                                : new ParcelFileDescriptor.AutoCloseInputStream(response.getBody()));
            }

            @Override
            public void onException(final String message) {
                callback.onFailure(new IOException("Internet Helper could not download AGPS data"));
            }
        });
    }

    record PgnssManifest(String downloadUrl, String version, String signature) {
    }

    static PgnssManifest parsePgnssManifest(final byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("The PGNSS manifest has an invalid size");
        }
        try {
            final JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonArray() || root.getAsJsonArray().size() != 1
                    || !root.getAsJsonArray().get(0).isJsonObject()) {
                throw new IOException("The PGNSS manifest must contain one dataset");
            }
            final JsonObject entry = root.getAsJsonArray().get(0).getAsJsonObject();
            final String url = manifestString(entry, "downloadUrl");
            final String version = manifestString(entry, "ver");
            final String signature = manifestString(entry, "signature");
            if (!"HW_EE".equals(manifestString(entry, "fileId")) || !signature.matches("[0-9a-f]{64}")) {
                throw new IOException("The PGNSS manifest has invalid metadata");
            }
            final URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"configdownload-dre.dbankcdn.com".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getRawAuthority().contains(":")
                    || !uri.getRawPath().matches("/update/Higeo/[0-9a-f]{64}/HiEE_V2\\.dat")) {
                throw new IOException("The PGNSS download URL is not allowed");
            }
            return new PgnssManifest(url, version, signature);
        } catch (final RuntimeException exception) {
            throw new IOException("The PGNSS manifest is invalid", exception);
        }
    }

    private static String manifestString(final JsonObject entry, final String name) throws IOException {
        final JsonElement value = entry.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty()) {
            throw new IOException("The PGNSS manifest has an invalid " + name);
        }
        return value.getAsString();
    }

    static void verifyPgnssSeed(final byte[] seed, final PgnssManifest manifest) throws IOException {
        if (seed.length == 0 || seed.length > MAX_SEED_BYTES) {
            throw new IOException("The PGNSS seed has an invalid size");
        }
        try {
            // Huawei hashes the exact seed bytes followed by the UTF-8 manifest version, without a delimiter.
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed);
            final byte[] actual = digest.digest(manifest.version().getBytes(StandardCharsets.UTF_8));
            final byte[] expected = new byte[32];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) Integer.parseInt(manifest.signature().substring(i * 2, i * 2 + 2), 16);
            }
            if (!MessageDigest.isEqual(actual, expected)) {
                throw new IOException("The PGNSS seed checksum does not match the manifest");
            }
        } catch (final NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    static byte[] decodeResponse(final InputStream body) throws IOException {
        final byte[] downloaded = readBounded(body, MAX_RESPONSE_BYTES);
        final byte[] rtcm;
        if (downloaded.length >= 2 && (downloaded[0] & 0xff) == 0x1f && (downloaded[1] & 0xff) == 0x8b) {
            try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(downloaded))) {
                rtcm = readBounded(gzip, MAX_RTCM_BYTES);
            }
        } else {
            rtcm = downloaded;
        }
        validateRtcm(rtcm);
        return rtcm;
    }

    private static byte[] readBounded(final InputStream input, final int maximum) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("The AGPS download was cancelled");
            }
            if (count > maximum - output.size()) {
                throw new IOException("The AGPS response exceeds its size limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void validateRtcm(final byte[] rtcm) throws IOException {
        if (rtcm.length == 0) {
            throw new IOException("The AGPS response is empty");
        }
        int offset = 0;
        while (offset < rtcm.length) {
            if (rtcm.length - offset < 8 || (rtcm[offset] & 0xff) != 0xd3 || (rtcm[offset + 1] & 0xfc) != 0) {
                throw new IOException("The AGPS response has an invalid RTCM header");
            }
            final int length = ((rtcm[offset + 1] & 3) << 8) | (rtcm[offset + 2] & 0xff);
            if (length < 2 || length + 6 > rtcm.length - offset) {
                throw new IOException("The AGPS response has a truncated RTCM frame");
            }
            final int messageId = ((rtcm[offset + 3] & 0xff) << 4) | ((rtcm[offset + 4] & 0xff) >>> 4);
            final int expectedLength = switch (messageId) {
                case 1019 -> 61;
                case 1020 -> 45;
                case 1042 -> 64;
                case 1046 -> 63;
                default -> throw new IOException("The AGPS response has an unsupported RTCM message");
            };
            if (length != expectedLength) {
                throw new IOException("The AGPS response has an invalid ephemeris payload length");
            }
            final int crc = HuaweiAgpsFileGenerator.crc24q(rtcm, offset, length + 3);
            final int end = offset + length + 3;
            final int expected = ((rtcm[end] & 0xff) << 16) | ((rtcm[end + 1] & 0xff) << 8) | (rtcm[end + 2] & 0xff);
            if (crc != expected) {
                throw new IOException("The AGPS response has an invalid RTCM checksum");
            }
            offset = end + 3;
        }
    }
}
