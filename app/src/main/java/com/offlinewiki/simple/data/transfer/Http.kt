package com.offlinewiki.simple.data.transfer

import com.offlinewiki.simple.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object Http {
    /**
     * Opens [url], following redirects by hand: Kiwix redirects to mirrors on
     * other hosts, and the Range header must be re-sent on every hop.
     */
    fun open(url: String, rangeFrom: Long = 0): HttpURLConnection {
        var current = url
        repeat(8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "SimpleWikiOffline/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            val code = conn.responseCode
            if (code !in 300..399) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            location ?: throw IOException("Redirect without a location")
            // Cleartext traffic is blocked on Android, so plain-http mirrors are tried over https.
            current = URL(URL(current), location).toString().replaceFirst("http://", "https://")
        }
        throw IOException("Too many redirects")
    }

    fun readText(url: String, maxBytes: Int): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (out.size() < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }
}
