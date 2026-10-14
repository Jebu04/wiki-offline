package com.offlinewiki.simple.data.transfer

import com.offlinewiki.simple.data.ContentKind
import java.io.IOException

data class ResolvedFile(val url: String, val fileName: String, val sha256Url: String?)

class DownloadSource(
    val id: String,
    val kind: ContentKind,
    val title: String,
    val detail: String,
    val approxBytes: Long,
    val debugOnly: Boolean = false,
    val resolve: () -> ResolvedFile,
)

object Sources {
    private const val KIWIX_DIR = "https://download.kiwix.org/zim/wikipedia/"

    /**
     * Kiwix publishes a new dated file every few months and removes old ones,
     * so the newest file name is looked up in the directory listing.
     */
    private fun latestKiwix(prefix: String): ResolvedFile {
        val listing = Http.readText(KIWIX_DIR, maxBytes = 8 * 1024 * 1024)
        val name = Regex("""href="(${Regex.escape(prefix)}_\d{4}-\d{2}\.zim)"""")
            .findAll(listing).map { it.groupValues[1] }.maxOrNull()
            ?: throw IOException("No file named $prefix found on download.kiwix.org")
        return ResolvedFile(KIWIX_DIR + name, name, KIWIX_DIR + name + ".sha256")
    }

    val zim: List<DownloadSource> = listOf(
        DownloadSource(
            id = "simple-maxi",
            kind = ContentKind.ZIM,
            title = "Simple English Wikipedia",
            detail = "All articles with images",
            approxBytes = 3_100_000_000L,
            resolve = { latestKiwix("wikipedia_en-simple_all_maxi") },
        ),
        DownloadSource(
            id = "dev-ray-charles",
            kind = ContentKind.ZIM,
            title = "Tiny test file",
            detail = "A handful of articles, for development",
            approxBytes = 3_000_000L,
            debugOnly = true,
            resolve = { latestKiwix("wikipedia_en_ray-charles_maxi") },
        ),
    )

    /**
     * Gemma 3 1B needs a Hugging Face login and licence acceptance, so it cannot
     * be downloaded from inside the app; it can be imported instead. This model
     * is Apache-2.0 and downloads without an account.
     */
    val model: List<DownloadSource> = listOf(
        DownloadSource(
            id = "qwen3.5-0.8b-int8",
            kind = ContentKind.MODEL,
            title = "Qwen3.5 0.8B",
            detail = "Small general model, 8-bit",
            approxBytes = 965_000_000L,
            resolve = {
                ResolvedFile(
                    url = "https://huggingface.co/litert-community/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm",
                    fileName = "Qwen3.5-0.8B_int8.litertlm",
                    sha256Url = null,
                )
            },
        ),
    )

    fun byId(id: String): DownloadSource? = (zim + model).firstOrNull { it.id == id }
}
