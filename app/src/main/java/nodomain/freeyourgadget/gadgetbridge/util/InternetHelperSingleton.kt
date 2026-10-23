/*  Copyright (C) 2025 Arjan Schrijver

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
package nodomain.freeyourgadget.gadgetbridge.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.webkit.WebResourceResponse
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.internethelper.aidl.http.HttpRequest
import nodomain.freeyourgadget.internethelper.aidl.http.HttpResponse
import nodomain.freeyourgadget.internethelper.aidl.http.IHttpCallback
import nodomain.freeyourgadget.internethelper.aidl.http.IHttpService
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object InternetHelperSingleton {
    private val LOG: Logger = LoggerFactory.getLogger(InternetHelperSingleton::class.java)
    @Volatile private var internetHelperBound = false
    @Volatile private var internetHelper: IHttpService? = null
    @Volatile private var lastBindFailure = 0L
    private const val BIND_RETRY_BACKOFF_MS = 5_000L
    private const val RESPONSE_TIMEOUT_S = 30L

    // Signalled when the (asynchronous) service connection completes, so callers can wait for it.
    @Volatile
    private var bindLatch: CountDownLatch? = null

    private val internetHelperConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            LOG.info("Internet helper service successfully bound")
            internetHelper = IHttpService.Stub.asInterface(service)
            internetHelperBound = true
            lastBindFailure = 0L
            bindLatch?.countDown()
        }

        override fun onServiceDisconnected(className: ComponentName?) {
            LOG.info("Internet helper service unbound")
            internetHelper = null
            internetHelperBound = false
        }

        override fun onBindingDied(name: ComponentName?) {
            LOG.warn("Internet helper binding died, resetting")
            internetHelper = null
            internetHelperBound = false
            try {
                GBApplication.getContext().unbindService(this)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun getHttpService(): IHttpService? {
        return internetHelper
    }

    fun ensureInternetHelperBound(): Boolean {
        val context = GBApplication.getContext()
        if (!internetHelperBound) {
            val internetHelperPkg = "nodomain.freeyourgadget.internethelper"
            val internetHelperCls = "$internetHelperPkg.HttpService"
            try {
                context.packageManager.getApplicationInfo(internetHelperPkg, 0)
                val intent = Intent()
                intent.component = ComponentName(internetHelperPkg, internetHelperCls)

                val intent1 = Intent("nodomain.freeyourgadget.internethelper.HttpService")
                intent1.setPackage("nodomain.freeyourgadget.internethelper")
                context.bindService(intent1, internetHelperConnection, Context.BIND_AUTO_CREATE)
                LOG.info("Internet helper binding initiated")
            } catch (_: PackageManager.NameNotFoundException) {
                LOG.info("Internet helper not installed, only mimicked HTTP requests will work")
            } catch (_: SecurityException) {
                LOG.info("Permission for internet helper not granted, only mimicked HTTP requests will work")
            }
        }
        return internetHelperBound
    }

    /**
     * Ensures the helper service is bound and waits (up to [timeoutMs]) for the asynchronous
     * connection to complete. Without this, the first request after process start races the bind
     * and fails even though the helper is installed and permitted. Returns true once usable.
     */
    @Synchronized
    fun ensureInternetHelperBoundBlocking(timeoutMs: Long = 3000): Boolean {
        if (internetHelper != null) return true
        if (System.currentTimeMillis() - lastBindFailure < BIND_RETRY_BACKOFF_MS) return false
        if (!AndroidUtils.isPackageInstalled(PermissionsUtils.PACKAGE_INTERNET_HELPER)
            || !PermissionsUtils.checkPermission(GBApplication.getContext(), PermissionsUtils.CUSTOM_PERM_INTERNET_HELPER)
        ) return false

        val latch = CountDownLatch(1)
        bindLatch = latch
        ensureInternetHelperBound()
        if (internetHelper == null) {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (internetHelper == null) {
            lastBindFailure = System.currentTimeMillis()
            return false
        }
        return true
    }

    @Throws(RemoteException::class, InterruptedException::class)
    fun send(
        webRequest: Uri,
        method: HttpRequest.Method,
        requestHeaders: Map<String, String> = emptyMap(),
        body: ByteArray?,
        allowInsecure: Boolean = false,
    ): WebResourceResponse? {
        if (internetHelper == null) {
            ensureInternetHelperBoundBlocking()
        }
        val helper = internetHelper
        if (helper == null) {
            LOG.error("Internet helper is not available")
            return null
        }

        val latch = CountDownLatch(1)
        val responseRef = AtomicReference<HttpResponse?>(null)
        val abandoned = AtomicBoolean(false)

        // Create pipe to stream request body
        val pipeRead: ParcelFileDescriptor?
        val pipeWrite: ParcelFileDescriptor?
        if (body != null) {
            val pipes = ParcelFileDescriptor.createPipe()
            pipeRead = pipes[0]
            pipeWrite = pipes[1]
        } else {
            pipeRead = null
            pipeWrite = null
        }

        val request = HttpRequest(webRequest.toString(), method, requestHeaders, pipeRead, allowInsecure)
        LOG.debug("Forwarding {} request to {} to internet helper app", method.name, webRequest)

        try {
            helper.send(request, object : IHttpCallback.Stub() {
                override fun onResponse(response: HttpResponse) {
                    responseRef.set(response)
                    // If the caller already gave up, nobody will read this pipe: close it ourselves.
                    if (abandoned.get()) {
                        responseRef.getAndSet(null)?.let { closeQuietly(it.body) }
                    }
                    latch.countDown()
                }

                override fun onException(message: String?) {
                    LOG.error("Request to {} failed: {}", webRequest, message)
                    latch.countDown()
                }
            })

            if (pipeWrite != null) {
                ParcelFileDescriptor.AutoCloseOutputStream(pipeWrite).use { it.write(body) }
            }
        } catch (e: RemoteException) {
            LOG.error("Error sending request to InternetHelper", e)
            return null
        } finally {
            closeQuietly(pipeRead)
            closeQuietly(pipeWrite)
        }

        if (!latch.await(RESPONSE_TIMEOUT_S, TimeUnit.SECONDS)) {
            LOG.error("Timed out after {}s waiting for internet helper response for {}", RESPONSE_TIMEOUT_S, webRequest)
            abandoned.set(true)
            responseRef.getAndSet(null)?.let { closeQuietly(it.body) }
            return null
        }

        val response = responseRef.get() ?: return null
        return try {
            toWebResourceResponse(response)
        } catch (e: Exception) {
            LOG.error("Error processing HTTP response for {}", webRequest, e)
            closeQuietly(response.body)
            null
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (e: IOException) {
            LOG.debug("Failed to close file descriptor", e)
        }
    }

    private fun toWebResourceResponse(response: HttpResponse): WebResourceResponse? {
        val status = response.status
        if (status !in 100..599 || status in 300..399) {
            LOG.error("Cannot represent HTTP status {} as a WebResourceResponse", status)
            closeQuietly(response.body)
            return null
        }

        val headers = response.headers.toMap()
        val lower = headers.entries.associate { it.key.lowercase() to it.value }
        val contentTypeHeader = lower["content-type"]
        val mimeType = contentTypeHeader?.substringBefore(";")?.trim()?.ifEmpty { null }
            ?: "application/octet-stream"
        val charsetName = contentTypeHeader
            ?.let { Regex("charset=\\s*\"?([^;\"\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?: "UTF-8"
        val charset = try { Charset.forName(charsetName) } catch (_: Exception) { Charsets.UTF_8 }
        val reason = if (status in 200..299) "OK" else "Error"
        val inputStream = ParcelFileDescriptor.AutoCloseInputStream(response.body)

        return if (mimeType.equals("text/html", ignoreCase = true)) {
            val rawHtml = inputStream.bufferedReader(charset).use { it.readText() }
            val cleanedHtml = Jsoup.parse(rawHtml).html()
            val htmlHeaders = headers.filterKeys {
                !it.equals("content-length", true) && !it.equals("content-encoding", true)
            }
            WebResourceResponse(mimeType, "UTF-8", status, reason, htmlHeaders,
                cleanedHtml.byteInputStream(Charsets.UTF_8))
        } else {
            val bytes = inputStream.use { it.readBytes() }   // always drains and closes the pipe
            WebResourceResponse(mimeType, charset.name(), status, reason, headers,
                java.io.ByteArrayInputStream(bytes))
        }
    }
}
