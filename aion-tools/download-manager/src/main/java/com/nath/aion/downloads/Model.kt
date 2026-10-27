package com.nath.aion.downloads

import org.json.JSONArray
import org.json.JSONObject

enum class Kind { HTTP, HLS, TORRENT }

enum class State { QUEUED, CONNECTING, DOWNLOADING, PAUSED, MERGING, FINISHED, SEEDING, ERROR }

/** One byte range of a multipart HTTP download. [end] is inclusive; -1 means "until the end". */
data class Part(val start: Long, val end: Long, var done: Long = 0) {
    val length get() = if (end < 0) -1 else end - start + 1
    val complete get() = end >= 0 && done >= length
}

/** File categories used for the filter chips, like 1DM's cataloguing. */
enum class Category(val label: String, val extensions: Set<String>) {
    VIDEO("Video", setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m4v", "3gp", "wmv", "mpg", "mpeg")),
    MUSIC("Music", setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr")),
    IMAGE("Images", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif")),
    DOCUMENT("Docs", setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "odt", "rtf")),
    ARCHIVE("Archives", setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "zst")),
    APP("Apps", setOf("apk", "apks", "xapk", "apkm", "exe", "msi", "dmg", "deb", "appimage")),
    OTHER("Other", emptySet());

    companion object {
        fun of(name: String): Category {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }
    }
}

data class DownloadItem(
    val id: Long,
    var url: String,
    var fileName: String,
    var dir: String,
    var kind: Kind,
    var state: State = State.QUEUED,
    var totalBytes: Long = -1,
    var doneBytes: Long = 0,
    var resumable: Boolean = false,
    var threads: Int = 8,
    var parts: MutableList<Part> = mutableListOf(),
    var referer: String? = null,
    var userAgent: String? = null,
    var cookies: String? = null,
    var headers: Map<String, String> = emptyMap(),
    var mime: String? = null,
    var error: String? = null,
    var addedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long = 0,
    var scheduled: Boolean = false,
    var speedLimit: Long = 0,
    var retries: Int = 0,
    var torrentHash: String? = null,
    var selectedFiles: List<Int>? = null,
    var sequential: Boolean = false,
    var seed: Boolean = false,
    var segmentsDone: Int = 0,
    var segmentsTotal: Int = 0,
    var peers: Int = 0,
    var seeds: Int = 0,
    var uploaded: Long = 0,
    var order: Long = id,
) {
    /** Live values, not persisted. */
    @Transient var speed: Long = 0
    @Transient var uploadSpeed: Long = 0

    val category get() = if (kind == Kind.TORRENT && Category.of(fileName) == Category.OTHER) Category.OTHER else Category.of(fileName)
    val active get() = state == State.QUEUED || state == State.CONNECTING || state == State.DOWNLOADING || state == State.MERGING
    val progress: Float
        get() = when {
            state == State.FINISHED || state == State.SEEDING -> 1f
            kind == Kind.HLS && segmentsTotal > 0 -> segmentsDone.toFloat() / segmentsTotal
            totalBytes > 0 -> (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }
    val file get() = java.io.File(dir, fileName)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("fileName", fileName); put("dir", dir); put("kind", kind.name)
        put("state", state.name); put("totalBytes", totalBytes); put("doneBytes", doneBytes); put("resumable", resumable)
        put("threads", threads)
        put("parts", JSONArray().apply { parts.forEach { put(JSONArray().put(it.start).put(it.end).put(it.done)) } })
        put("referer", referer); put("userAgent", userAgent); put("cookies", cookies)
        put("headers", JSONObject(headers)); put("mime", mime); put("error", error)
        put("addedAt", addedAt); put("finishedAt", finishedAt); put("scheduled", scheduled); put("speedLimit", speedLimit)
        put("torrentHash", torrentHash)
        put("selectedFiles", selectedFiles?.let { JSONArray(it) }); put("sequential", sequential); put("seed", seed)
        put("segmentsDone", segmentsDone); put("segmentsTotal", segmentsTotal); put("uploaded", uploaded); put("order", order)
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            val parts = mutableListOf<Part>()
            o.optJSONArray("parts")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONArray(i)
                    parts.add(Part(p.getLong(0), p.getLong(1), p.getLong(2)))
                }
            }
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.getString(it) } }
            val selected = o.optJSONArray("selectedFiles")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
            fun str(key: String) = if (o.isNull(key)) null else o.optString(key)
            return DownloadItem(
                id = o.getLong("id"), url = o.getString("url"), fileName = o.getString("fileName"), dir = o.getString("dir"),
                kind = Kind.valueOf(o.getString("kind")), state = State.valueOf(o.getString("state")),
                totalBytes = o.optLong("totalBytes", -1), doneBytes = o.optLong("doneBytes"), resumable = o.optBoolean("resumable"),
                threads = o.optInt("threads", 8), parts = parts, referer = str("referer"), userAgent = str("userAgent"),
                cookies = str("cookies"), headers = headers, mime = str("mime"), error = str("error"),
                addedAt = o.optLong("addedAt"), finishedAt = o.optLong("finishedAt"), scheduled = o.optBoolean("scheduled"),
                speedLimit = o.optLong("speedLimit"), torrentHash = str("torrentHash"), selectedFiles = selected,
                sequential = o.optBoolean("sequential"), seed = o.optBoolean("seed"), segmentsDone = o.optInt("segmentsDone"),
                segmentsTotal = o.optInt("segmentsTotal"), uploaded = o.optLong("uploaded"), order = o.optLong("order", o.getLong("id")),
            )
        }
    }
}
