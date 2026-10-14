package com.offlinewiki.simple.ui.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.offlinewiki.simple.zim.ZimRepository
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream

/**
 * Serves every WebView request from the ZIM file. Articles live under a
 * made-up https origin so relative links and images resolve normally; nothing
 * is ever fetched from the network.
 */
class ZimWebViewClient(
    private val zim: ZimRepository,
    private val onExternalLink: (String) -> Unit,
    private val onPage: (url: String?, title: String?, canGoBack: Boolean, finished: Boolean) -> Unit,
) : WebViewClient() {

    private val mainPath by lazy { zim.mainPagePath() }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val path = pathOf(request.url) ?: return blocked()
        val content = zim.read(path) ?: return notFound()

        if (content.redirectTo != null && request.isForMainFrame) {
            // WebResourceResponse cannot carry a 3xx status, so redirects are done by the page itself.
            // The target needs its own URL so that its relative links resolve correctly.
            val target = urlFor(content.redirectTo)
            val html = "<!doctype html><script>location.replace(${jsString(target)})</script>"
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
        }

        val mime = content.mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        if (mime == "text/html" && path == mainPath) {
            return WebResourceResponse(mime, "utf-8", SequenceInputStream(ByteArrayInputStream(content.data), ByteArrayInputStream(MAIN_PAGE_STYLE)))
        }
        val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(content.mimeType)?.groupValues?.get(1)
            ?: if (mime.startsWith("text/")) "utf-8" else null
        return WebResourceResponse(mime, charset, ByteArrayInputStream(content.data))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (pathOf(request.url) != null) return false
        onExternalLink(request.url.toString())
        return true
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        onPage(url, view.title, view.canGoBack(), false)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPage(url, view.title, view.canGoBack(), true)
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Offline", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun notFound() = WebResourceResponse(
        "text/html", "utf-8", 404, "Not Found", emptyMap(),
        ByteArrayInputStream("<!doctype html><meta name=viewport content='width=device-width'><p style='font:16px sans-serif;padding:24px'>This page is not in the offline copy.</p>".toByteArray()),
    )

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\x3c") + "\""

    companion object {
        const val HOST = "zim.offline"

        /**
         * The main page is laid out as side-by-side table columns sized for a
         * desktop, which run off the edge of a phone. This is appended to it so
         * the columns stack and everything wraps within the screen width. The
         * script renames the page and removes its footer note.
         */
        private val MAIN_PAGE_STYLE = """
            <style>
            html, body { max-width: 100%; overflow-x: hidden; }
            table, tbody, tr, td, th { display: block !important; width: auto !important; max-width: 100% !important; box-sizing: border-box; float: none !important; }
            div { max-width: 100% !important; box-sizing: border-box; }
            td, th { height: auto !important; }
            img { max-width: 100%; height: auto; }
            body * { overflow-wrap: anywhere; }
            </style>
            <script>
            (function () {
              // The app's own name for its start page, in the page heading and the title bar.
              document.title = 'Wiki Offline';
              var heading = document.querySelector('h1');
              if (heading) heading.textContent = 'Wiki Offline';
              // Hide the licence note at the bottom of this page (articles keep theirs).
              var note = null;
              document.querySelectorAll('body *').forEach(function (el) {
                if (el.textContent.trim().indexOf('This page is issued from') === 0) note = el;
              });
              while (note && note.parentElement && note.parentElement !== document.body &&
                     note.parentElement.textContent.trim() === note.textContent.trim()) note = note.parentElement;
              if (note) note.style.display = 'none';
            })();
            </script>
        """.trimIndent().toByteArray()

        fun urlFor(path: String): String = "https://$HOST/" + Uri.encode(path, "/")

        /** The ZIM entry path for one of our URLs, or null if the URL points somewhere else. */
        fun pathOf(uri: Uri): String? {
            if (uri.scheme != "https" || uri.host != HOST) return null
            return uri.path?.removePrefix("/")
        }
    }
}
