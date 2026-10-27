package com.nath.aion.downloads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/** In-memory list of downloads, persisted to a JSON file. The UI observes [items]. */
class Repository private constructor(private val context: Context) {
    private val file = File(context.filesDir, "downloads.json")
    private val list = mutableListOf<DownloadItem>()
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items
    @Volatile private var lastSave = 0L

    init {
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) list.add(DownloadItem.fromJson(array.getJSONObject(i)))
        }
        // Anything that was running when the process died is paused, then the service resumes it.
        list.forEach {
            if (it.state == State.CONNECTING || it.state == State.DOWNLOADING || it.state == State.MERGING) it.state = State.QUEUED
            if (it.state == State.SEEDING) it.state = State.QUEUED
        }
        publish()
    }

    fun all(): List<DownloadItem> = synchronized(list) { list.toList() }

    fun get(id: Long) = synchronized(list) { list.firstOrNull { it.id == id } }

    fun add(item: DownloadItem) {
        synchronized(list) { list.add(item) }
        changed(force = true)
    }

    fun remove(ids: Collection<Long>, deleteFiles: Boolean) {
        val removed = synchronized(list) { list.filter { it.id in ids }.also { list.removeAll(it) } }
        if (deleteFiles) removed.forEach { item ->
            item.file.delete()
            File(item.dir, ".${item.fileName}.parts").deleteRecursively()
        }
        changed(force = true)
    }

    fun move(id: Long, up: Boolean) {
        synchronized(list) {
            val sorted = list.sortedBy { it.order }
            val i = sorted.indexOfFirst { it.id == id }
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j !in sorted.indices) return
            val a = sorted[i].order
            sorted[i].order = sorted[j].order
            sorted[j].order = a
        }
        changed(force = true)
    }

    /** Publishes to the UI on every call; writes to disk at most every 2 s unless [force]. */
    fun changed(force: Boolean = false) {
        publish()
        val now = System.currentTimeMillis()
        if (force || now - lastSave > 2000) {
            lastSave = now
            save()
        }
    }

    private fun publish() {
        _items.value = synchronized(list) { list.map { it.copySnapshot() } }
    }

    @Synchronized
    private fun save() {
        val array = JSONArray()
        synchronized(list) { list.forEach { array.put(it.toJson()) } }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    fun exportLinks(): String = all().joinToString("\n") { it.url }

    private fun DownloadItem.copySnapshot() = copy(parts = parts.map { it.copy() }.toMutableList()).also {
        it.speed = speed
        it.uploadSpeed = uploadSpeed
    }

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Repository(context.applicationContext).also { instance = it }
        }
    }
}
