package eu.kanade.tachiyomi.extension.en.comix

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The site's live cipher material: three 256-byte S-boxes and the three keys
 * (24/24/32 bytes). Fetched from the proxy server, cached in preferences, and
 * re-fetched when the site rotates it -- it is never embedded in source.
 */
internal class ComixMaterial(
    val sboxes: List<IntArray>,
    val keys: List<IntArray>,
) {

    fun toCipher(): ComixCipher = ComixCipher(
        CipherMaterial(
            sboxes = sboxes.map { it.toList() },
            keys = keys.map { it.toList() },
        ),
    )

    fun toJsonString(): String = JSONObject().apply {
        put("s", JSONArray().apply { sboxes.forEach { put(bytesToBase64(it)) } })
        put("k", JSONArray().apply { keys.forEach { put(bytesToBase64(it)) } })
    }.toString()

    companion object {
        private const val COUNT = 3

        fun tryFromJson(json: String?): ComixMaterial? {
            if (json.isNullOrBlank()) return null
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val sboxes = decodeBlobs(root.optJSONArray("s")) ?: return null
            val keys = decodeBlobs(root.optJSONArray("k")) ?: return null
            if (sboxes.size != COUNT || keys.size != COUNT) return null
            if (sboxes.any { it.size != 256 || !it.isPermutation256() }) return null
            if (keys.any { it.size != 24 && it.size != 32 }) return null
            return ComixMaterial(sboxes, keys)
        }

        private fun decodeBlobs(array: JSONArray?): List<IntArray>? = buildList {
            if (array == null) return null
            for (i in 0 until array.length()) {
                val bytes = runCatching { Base64.decode(array.getString(i), Base64.DEFAULT) }.getOrNull()
                    ?: return null
                add(IntArray(bytes.size) { bytes[it].toInt() and 0xFF })
            }
        }

        private fun bytesToBase64(bytes: IntArray): String = Base64.encodeToString(ByteArray(bytes.size) { bytes[it].toByte() }, Base64.NO_WRAP)
    }
}

internal fun IntArray.isPermutation256(): Boolean {
    if (size != 256) return false
    val seen = BooleanArray(256)
    for (v in this) {
        if (v < 0 || v > 255 || seen[v]) return false
        seen[v] = true
    }
    return true
}

/**
 * Pure-Kotlin port of the site's request-signing cipher: three CBC-ish S-box
 * rounds over [0,255] bytes. The S-boxes and keys are the site's cipher
 * material -- fetched from the proxy server (see [ComixMaterial]), never
 * embedded in source. Only the per-round chaining seeds are literal site
 * constants.
 */
internal object ComixCrypto {

    /**
     * Builds the canonical, sorted, indexed query string exactly like the
     * site's `Z0` serializer: keys sorted, list values expanded to
     * `key[i]=value`, single values emitted as `key=value`, values
     * URL-encoded like JS `encodeURIComponent`.
     */
    fun canonicalizes(params: Map<String, List<String>>): String {
        val parts = buildList {
            for (key in params.keys.sorted()) {
                val entry = params.getValue(key)
                if (entry.size == 1) {
                    add("$key=${encodeURIComponent(entry[0])}")
                } else {
                    entry.forEachIndexed { i, v -> add("$key[$i]=${encodeURIComponent(v)}") }
                }
            }
        }
        return parts.joinToString("&")
    }

    /** JS `encodeURIComponent`: unreserved chars pass through, everything else %-escaped UTF-8. */
    fun encodeURIComponent(value: String): String {
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (
                (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '!'.code ||
                c == '~'.code || c == '*'.code || c == '\''.code || c == '('.code || c == ')'.code
            ) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[(c ushr 4) and 0xF]).append(HEX[c and 0xF])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF"
}
