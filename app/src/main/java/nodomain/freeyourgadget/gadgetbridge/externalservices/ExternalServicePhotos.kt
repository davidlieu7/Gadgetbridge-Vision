/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.externalservices

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import org.slf4j.LoggerFactory
import java.io.File

/** Header photos as a service will take them. */
object ExternalServicePhotos {
    private val LOG = LoggerFactory.getLogger(ExternalServicePhotos::class.java)

    /**
     * Runs [block] with [photo] when its extension is among [allowedExtensions] (lowercase), or
     * with a JPEG copy of it otherwise, deleting the copy afterwards. [block] gets null when the
     * photo cannot be decoded, so there is nothing the service would accept.
     */
    fun <T> withAcceptedFormat(photo: File, allowedExtensions: Set<String>, block: (File?) -> T): T {
        if (photo.extension.lowercase() in allowedExtensions) {
            return block(photo)
        }
        val converted = convertToJpeg(photo)
        try {
            return block(converted)
        } finally {
            converted?.delete()
        }
    }

    /** Re-encodes [file] as a JPEG in the cache directory, or answers null when it cannot be decoded. */
    private fun convertToJpeg(file: File): File? {
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) {
            LOG.warn("Cannot decode photo {} to convert it", file.name)
            return null
        }
        return try {
            val jpeg = File.createTempFile("upload_photo", ".jpg", GBApplication.getContext().cacheDir)
            val written = jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!written) {
                LOG.warn("Cannot encode photo {} as JPEG", file.name)
                jpeg.delete()
                return null
            }
            LOG.debug("Converted photo {} to JPEG", file.name)
            jpeg
        } finally {
            bitmap.recycle()
        }
    }
}
