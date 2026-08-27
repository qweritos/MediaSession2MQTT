package be.digitalia.mediasession2mqtt.mqttmediaplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

private const val MAX_ARTWORK_DIMENSION = 800
private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024
private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 10_000

/**
 * Build a cheap key that changes whenever the current media or its advertised artwork changes.
 */
fun MediaMetadata?.toArtworkCacheKey(packageName: String): String {
    if (this == null) {
        return packageName
    }
    return listOf(
        packageName,
        getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty()
    ).joinToString("\u0000")
}

/**
 * Resolve the current MediaSession artwork and normalize it to JPEG bytes.
 */
suspend fun resolveArtworkJpeg(metadata: MediaMetadata?): ByteArray =
    withContext(Dispatchers.IO) {
        if (metadata == null) {
            return@withContext ByteArray(0)
        }

        metadata.firstArtworkBitmap()?.let { bitmap ->
            return@withContext bitmap.toJpeg()
        }

        metadata.firstArtworkUri()?.let { uri ->
            downloadBitmap(uri)?.let { bitmap ->
                return@withContext bitmap.toJpeg()
            }
        }

        ByteArray(0)
    }

private fun MediaMetadata.firstArtworkBitmap(): Bitmap? {
    return runCatching { getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) }.getOrNull()
        ?: runCatching { getBitmap(MediaMetadata.METADATA_KEY_ART) }.getOrNull()
        ?: runCatching { getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) }.getOrNull()
}

private fun MediaMetadata.firstArtworkUri(): String? {
    return sequenceOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
    ).mapNotNull { key -> getString(key) }
        .firstOrNull { value ->
            runCatching {
                val scheme = URI(value).scheme
                scheme == "https" || scheme == "http"
            }.getOrDefault(false)
        }
}

private fun downloadBitmap(url: String): Bitmap? {
    val bytes = httpGet(url) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun httpGet(url: String): ByteArray? {
    val connection = (URI(url).toURL().openConnection() as? HttpURLConnection) ?: return null
    return try {
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "MediaSession2MQTT")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            return null
        }
        connection.contentLengthLong.takeIf { it >= 0 }?.let { contentLength ->
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                return null
            }
        }
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                total += count
                if (total > MAX_DOWNLOAD_BYTES) {
                    return null
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun Bitmap.toJpeg(): ByteArray {
    val normalized = if (width <= MAX_ARTWORK_DIMENSION && height <= MAX_ARTWORK_DIMENSION) {
        this
    } else {
        val scale = MAX_ARTWORK_DIMENSION.toFloat() / maxOf(width, height)
        Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    return ByteArrayOutputStream().use { output ->
        normalized.compress(Bitmap.CompressFormat.JPEG, 90, output)
        if (normalized !== this) {
            normalized.recycle()
        }
        output.toByteArray()
    }
}
