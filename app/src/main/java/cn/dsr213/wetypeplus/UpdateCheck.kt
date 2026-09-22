package cn.dsr213.wetypeplus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The outcome of asking GitHub what the newest release is.
 *
 * Three states rather than a nullable, because the two "nothing to offer" cases are not the same
 * thing and only one of them deserves a word to the user: a manual check that reports "up to date"
 * when the request never left the device would be a lie the user has no way to see through.
 */
internal sealed interface UpdateResult {
    /** A release newer than the installed build. */
    data class Available(val info: UpdateInfo) : UpdateResult

    /** GitHub answered, and the installed build is already the newest. */
    object UpToDate : UpdateResult

    /** No answer: no network, GitHub unreachable, timeout, or a payload that could not be read. */
    object Unreachable : UpdateResult
}

/**
 * A release newer than the installed build.
 *
 * [notes] is the release body exactly as written on GitHub - markdown, and for a merged release
 * several thousand characters of it. Kept whole so nothing is lost; the dialog only ever shows a
 * trimmed plain-text preview of it (see [releasePreview]).
 */
internal data class UpdateInfo(
    val tag: String,
    val version: String,
    val url: String,
    val notes: String,
    val publishedAt: String
)

/**
 * Checks this project's GitHub releases for something newer than the installed build.
 *
 * ### Why the list endpoint and not `/releases/latest`
 *
 * Every release this project publishes is a **prerelease** (alpha builds), and GitHub's `latest`
 * endpoint deliberately ignores prereleases - it answered **HTTP 404** when probed on 2026-09-22.
 * The web `/releases/latest` redirect is no better: it 302'd to the release *list* for the same
 * reason. The plain list endpoint does return prereleases, so the newest entry is picked from there.
 * Drafts are skipped - they are not public yet and must never be advertised.
 *
 * ### Why every failure collapses into [UpdateResult.Unreachable]
 *
 * github.com is routinely unreachable from mainland China without a proxy. Measured from the build
 * machine on 2026-09-22: a direct request was `Recv failure: Connection was reset` after **19.7 s**,
 * while the same request through a proxy finished in **0.22 s**. This app is offline-first and this
 * check is its only network use, so a failed check is an ordinary outcome rather than an error
 * state. Callers decide what to make of it: the automatic check at launch stays silent, the manual
 * one says so plainly.
 *
 * Blocking - call it off the main thread.
 */
internal object UpdateCheck {

    private const val RELEASES =
        "https://api.github.com/repos/213DEE/WeTypePlus/releases?per_page=10"

    /**
     * Deliberately short. Someone who tapped "check for updates" should get an answer, or a "cannot
     * reach GitHub", within a few seconds - the 20-second timeout a direct connection actually took
     * would read as a frozen app, and the manual path is the one place a slow failure is visible.
     */
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    fun check(context: Context): UpdateResult = runCatching {
        val local = installedVersion(context)
        val release = newest(JSONArray(fetch())) ?: return@runCatching UpdateResult.Unreachable

        val tag = release.optString("tag_name")
        val version = tag.removePrefix("v")
        if (!isNewer(version, local)) return@runCatching UpdateResult.UpToDate

        UpdateResult.Available(
            UpdateInfo(
                tag = tag,
                version = version,
                url = release.optString("html_url"),
                notes = release.optString("body"),
                publishedAt = release.optString("published_at")
            )
        )
    }.getOrElse { UpdateResult.Unreachable }

    private fun fetch(): String {
        val connection = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub asks for a User-Agent; the unversioned form avoids advertising an old build
            // in a header nobody reads.
            setRequestProperty("User-Agent", "WeTypePlus")
        }
        return try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The newest non-draft release, or null when the list holds nothing usable.
     *
     * `published_at` is ISO-8601 UTC to the second, so plain string ordering *is* chronological and
     * no date parsing is needed. The list endpoint already returns newest-first, but sorting here
     * costs nothing and removes the dependency on that ordering.
     */
    private fun newest(releases: JSONArray): JSONObject? {
        var best: JSONObject? = null
        var bestAt = ""
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            val at = release.optString("published_at")
            if (best == null || at > bestAt) {
                best = release
                bestAt = at
            }
        }
        return best
    }

    /**
     * `1.0.26-alpha` -> `[1, 0, 26]`.
     *
     * Everything that is not a digit is a separator, including the `-alpha` label: the numbers
     * decide whether a build is newer, the label does not.
     */
    private fun numericParts(version: String): List<Int> =
        Regex("""\d+""").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()

    /**
     * Whether [remote] is a later version than [local].
     *
     * Missing trailing components count as zero, so `1.0.26` and `1.0.26.0` are the same version.
     * A remote tag with no digits at all is treated as *not* newer - prompting someone to update to
     * something we could not read is worse than staying quiet.
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = numericParts(remote)
        val localParts = numericParts(local)
        if (remoteParts.isEmpty()) return false
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val a = remoteParts.getOrElse(i) { 0 }
            val b = localParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun installedVersion(context: Context): String =
        @Suppress("DEPRECATION")
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
}

/**
 * A one-glance plain-text preview of a release body, for the update dialog.
 *
 * The body is markdown written for a web page - headings, bold spans, code fences, links, and for a
 * merged release a table. The dialog has room for a line or two, so the markup is stripped rather
 * than rendered: a raw `**1.0.26**` or an orphaned `](https://...)` on screen reads as a bug.
 *
 * Empty when there is nothing readable left, which the caller treats as "no preview".
 */
internal fun releasePreview(notes: String, limit: Int = 160): String {
    val plain = notes
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "\$1")   // [text](url) and ![alt](url)
        // Markdown punctuation only. `~` is deliberately absent: it is a strikethrough marker in
        // markdown but a range separator in the prose these notes are written in ("v1.0.22 ~
        // v1.0.25"), and stripping it turned a range into what read like a list of separate items.
        .replace(Regex("""[`*_>#|]+"""), " ")
        .replace(Regex("""\s+"""), " ")                        // newlines and indentation
        .trim()
    if (plain.length <= limit) return plain
    return plain.take(limit).trimEnd(' ', ',', '，', '、', '。') + "…"
}
