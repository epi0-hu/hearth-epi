package app.hearth.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.hearth.core.Json
import app.hearth.core.JObj
import app.hearth.core.objList
import app.hearth.core.str
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater for sideloaded builds: reads the "latest" GitHub release that the CI
 * workflow publishes, downloads hearth.apk and hands it to Android's package installer.
 * Builds share one signing key, so updates install over the current app and keep its data.
 */
object Updater {
    data class Release(val sha: String, val apiAssetUrl: String, val downloadUrl: String, val size: Long, val publishedAt: String)

    private const val UA = "Hearth-Updater"

    /** Fetches the "latest" release. [repo] is "owner/name"; [token] is only needed for private repos. */
    fun latest(repo: String, token: String?): Release {
        val r = repo.trim().removePrefix("https://github.com/").trim('/')
        require(r.count { it == '/' } == 1) { "Set the repository as owner/name" }
        val body = get("https://api.github.com/repos/$r/releases/tags/latest", token, "application/vnd.github+json")
        val o = Json.parseObj(body)
        val asset = o.objList("assets").firstOrNull { it.str("name").endsWith(".apk", true) }
            ?: throw IOException("The latest release has no APK yet")
        val sha = Regex("Built from ([0-9a-f]{7,40})").find(o.str("body"))?.groupValues?.get(1)
            ?: o.str("published_at")
        return Release(
            sha = sha.take(7),
            apiAssetUrl = asset.str("url"),
            downloadUrl = asset.str("browser_download_url"),
            size = (asset["size"] as? Number)?.toLong() ?: -1L,
            publishedAt = o.str("published_at"),
        )
    }

    /** True when [release] is a different build than the one running. */
    fun isNewer(release: Release, currentSha: String): Boolean =
        currentSha == "dev" || !release.sha.startsWith(currentSha.take(7))

    /** Downloads the APK to the app cache, reporting progress 0..1 (or -1 when unknown). */
    fun download(ctx: Context, release: Release, token: String?, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "hearth-${release.sha}.apk")
        // Private repos must go through the API asset URL; public ones can use the direct link.
        val (url, accept) = if (!token.isNullOrBlank()) release.apiAssetUrl to "application/octet-stream"
        else release.downloadUrl to "application/octet-stream"
        val conn = open(url, token, accept)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 128 * 1024) {
                            lastReport = done
                            progress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (out.length() < 100_000) throw IOException("Download looks incomplete")
        progress(1f)
        return out
    }

    /** Opens the system installer. Returns false if Hearth first needs permission to install apps. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    // ------------------------------------------------------------ HTTP

    private fun get(url: String, token: String?, accept: String): String {
        val c = open(url, token, accept)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Opens a connection, following redirects by hand: GitHub redirects downloads to a
     * signed storage URL that must NOT receive the Authorization header.
     */
    private fun open(start: String, token: String?, accept: String): HttpURLConnection {
        var url = start
        var auth = !token.isNullOrBlank()
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", accept)
            if (auth) c.setRequestProperty("Authorization", "Bearer " + token!!.trim())
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                c.disconnect()
                val next = URL(URL(url), loc)
                if (next.host != URL(url).host) auth = false
                url = next.toString()
                return@repeat
            }
            if (code !in 200..299) {
                val msg = c.errorStream?.bufferedReader()?.use { it.readText() }?.let { body ->
                    runCatching { (Json.parse(body) as? JObj)?.str("message") }.getOrNull()
                } ?: c.responseMessage
                c.disconnect()
                throw IOException(
                    when (code) {
                        404 -> "No \"latest\" release found (is the repo private? add a token)"
                        401, 403 -> "GitHub refused: $msg"
                        else -> "HTTP $code: $msg"
                    },
                )
            }
            return c
        }
        throw IOException("Too many redirects")
    }
}
