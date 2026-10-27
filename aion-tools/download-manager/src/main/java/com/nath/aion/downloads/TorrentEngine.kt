package com.nath.aion.downloads

import android.content.Context
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.settings_pack
import java.io.File

/** One libtorrent session for all torrents: magnet links, .torrent files, file selection, seeding. */
class TorrentEngine(private val context: Context, private val prefs: Prefs) {
    private var session: SessionManager? = null
    private val torrentDir get() = File(context.filesDir, "torrents").apply { mkdirs() }

    @Synchronized
    fun ensureStarted(): SessionManager {
        session?.let { if (it.isRunning) return it }
        val pack = SettingsPack()
            .connectionsLimit(prefs.maxConnections)
            .downloadRateLimit(if (prefs.speedLimitEnabled) prefs.speedLimitKb * 1024 else 0)
            .uploadRateLimit(prefs.uploadLimitKb * 1024)
            .activeDownloads(prefs.concurrent)
            .activeSeeds(if (prefs.seedFinished) 10 else 0)
        pack.setEnableDht(prefs.dht)
        pack.setEnableLsd(prefs.lsd)
        pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), prefs.upnp)
        pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), prefs.natPmp)
        val policy = when (prefs.encryption) { 1 -> 1; 2 -> 2; else -> 0 } // 0 enabled, 1 forced, 2 disabled
        pack.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), policy)
        pack.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), policy)
        if (!prefs.randomPort) pack.listenInterfaces("0.0.0.0:${prefs.port},[::]:${prefs.port}")
        val manager = SessionManager(false)
        manager.start(SessionParams(pack))
        manager.maxPeers(prefs.maxPeers)
        session = manager
        return manager
    }

    fun stop() {
        session?.stop()
        session = null
    }

    /** Fetches metadata for a magnet link (blocking, up to [timeoutSec]). Returns the .torrent bytes. */
    fun fetchMetadata(magnet: String, timeoutSec: Int = 60): ByteArray? =
        ensureStarted().fetchMagnet(magnet, timeoutSec, torrentDir)

    fun infoFrom(bytes: ByteArray) = TorrentInfo(bytes)

    fun saveTorrentFile(hash: String, bytes: ByteArray) = File(torrentDir, "$hash.torrent").writeBytes(bytes)

    fun torrentFile(hash: String) = File(torrentDir, "$hash.torrent")

    /** Starts (or resumes) a torrent for [item]. Needs the .torrent saved under its hash. */
    fun start(item: DownloadItem) {
        val manager = ensureStarted()
        val hash = item.torrentHash ?: error("No torrent hash")
        handle(item)?.let { h ->
            h.resume()
            applyOptions(item, h)
            return
        }
        val file = torrentFile(hash)
        val dir = File(item.dir).apply { mkdirs() }
        if (file.exists()) {
            val info = TorrentInfo(file)
            val priorities = Array(info.numFiles()) { i ->
                if (item.selectedFiles == null || i in item.selectedFiles!!) Priority.DEFAULT else Priority.IGNORE
            }
            manager.download(info, dir, null, priorities, null, TorrentFlags.AUTO_MANAGED)
        } else {
            manager.download(item.url, dir, TorrentFlags.AUTO_MANAGED)
        }
        handle(item)?.let { applyOptions(item, it) }
    }

    private fun applyOptions(item: DownloadItem, h: TorrentHandle) {
        if (item.sequential) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        h.setDownloadLimit(if (item.speedLimit > 0) item.speedLimit.toInt() else -1)
        extraTrackers().forEach { h.addTracker(AnnounceEntry(it)) }
    }

    private fun extraTrackers() = prefs.extraTrackers.lines().map { it.trim() }.filter { it.startsWith("udp://") || it.startsWith("http") }

    fun handle(item: DownloadItem): TorrentHandle? {
        val hash = item.torrentHash ?: return null
        return session?.find(Sha1Hash.parseHex(hash))?.takeIf { it.isValid }
    }

    fun pause(item: DownloadItem) {
        handle(item)?.let {
            it.pause()
            it.saveResumeData()
        }
    }

    fun remove(item: DownloadItem, deleteFiles: Boolean) {
        val h = handle(item) ?: return
        if (deleteFiles) {
            session?.remove(h, org.libtorrent4j.SessionHandle.DELETE_FILES)
        } else {
            session?.remove(h)
        }
    }

    fun recheck(item: DownloadItem) = handle(item)?.forceRecheck()
    fun reannounce(item: DownloadItem) = handle(item)?.forceReannounce()
    fun magnetOf(item: DownloadItem) = handle(item)?.makeMagnetUri() ?: item.url

    /** Copies libtorrent status into [item]. Returns true once the wanted files are complete. */
    fun update(item: DownloadItem): Boolean {
        val h = handle(item) ?: return false
        val s = h.status()
        item.totalBytes = s.totalWanted()
        item.doneBytes = s.totalDone()
        item.speed = s.downloadRate().toLong()
        item.uploadSpeed = s.uploadRate().toLong()
        item.peers = s.numPeers()
        item.seeds = s.numSeeds()
        item.uploaded = s.allTimeUpload()
        if (s.hasMetadata() && (item.fileName.isBlank() || item.fileName.startsWith("magnet"))) item.fileName = s.name()
        if (item.torrentHash == null) item.torrentHash = h.infoHash().toHex()
        s.errorCode()?.let { if (it.isError) item.error = it.message }
        val finished = s.isFinished || s.isSeeding || s.state() == TorrentStatus.State.SEEDING ||
            s.state() == TorrentStatus.State.FINISHED
        if (finished && !item.seed) h.pause()
        return finished
    }

    data class FileEntry(val index: Int, val path: String, val size: Long)

    fun files(info: TorrentInfo): List<FileEntry> {
        val fs = info.files()
        return (0 until fs.numFiles()).map { FileEntry(it, fs.filePath(it), fs.fileSize(it)) }
    }

    companion object {
        fun hashFromMagnet(magnet: String): String? =
            Regex("xt=urn:btih:([0-9a-fA-F]{40})").find(magnet)?.groupValues?.get(1)?.lowercase()
    }
}
