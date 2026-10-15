package com.gmailorg.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.security.MessageDigest

/**
 * Exact launcher art included in the six standalone builds.
 * The original three chunk files per app are committed as assets, so the Main
 * tile can show the correct art even before that standalone APK is installed.
 */
internal object OfficialFamilyArtwork {
    private data class Artwork(val prefix: String, val checksum: String)
    private val byPackage = mapOf(
        "com.theone.cinema" to Artwork("cinema", "99cdfac91335cd1abada5d896ab777b419e4861e4d38c7f79f9469c563ab0807"),
        "com.theone.series" to Artwork("series", "1d9035b0bf27692c2a3cb8630af7f36fc600cd72c837198620a7ad57e008e69f"),
        "com.theone.tv.phone" to Artwork("tv", "a752c2df2c05dc6c242d91a2c48b08b59709a69d389495c3feef47ca7ce4b0c9")
    )
    private val bitmapCache = mutableMapOf<String, Bitmap?>()
    @Synchronized
    fun drawable(context: Context, packageName: String): Drawable? {
        val info = byPackage[packageName] ?: return null
        val image = if (bitmapCache.containsKey(packageName)) bitmapCache[packageName] else {
            val decoded = runCatching {
                val encoded = buildString {
                    for (index in 1..3) {
                        context.assets.open("family-branding/${info.prefix}-logo-${index}.base64")
                            .bufferedReader(Charsets.UTF_8).use { append(it.readText().trim()) }
                    }
                }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(digest == info.checksum) { "Original Family logo has been changed" }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            bitmapCache[packageName] = decoded
            decoded
        }
        return image?.let { BitmapDrawable(context.resources, it) }
    }
}
