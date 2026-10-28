package com.nath.aion.downloads

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the download queue in the foreground: starts up to N downloads (per-site limits too),
 * retries with a delay, honours Wi-Fi only / battery limit / speed limit, shows notifications and
 * stops itself when nothing is left.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<Long, Job>()
    private lateinit var repo: Repository
    private lateinit var prefs: Prefs
    private lateinit var http: HttpEngine
    private lateinit var hls: HlsEngine
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val globalLimiter = SpeedLimiter { if (prefs.speedLimitEnabled) prefs.speedLimitKb * 1024L else 0 }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = Repository.get(this)
        prefs = Prefs(this)
        http = HttpEngine(prefs, globalLimiter)
        hls = HlsEngine(prefs, globalLimiter)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_PAUSE -> intent.getLongArrayExtra(EXTRA_IDS)?.forEach { pause(it) }
            ACTION_PAUSE_ALL -> repo.all().filter { it.active || it.state == State.SEEDING }.forEach { pause(it.id) }
            ACTION_STOP_SEEDING -> repo.all().filter { it.state == State.SEEDING }.forEach { pause(it.id) }
        }
        if (loop?.isActive != true) loop = scope.launch { runQueue() }
        return START_STICKY
    }

    private fun pause(id: Long) {
        running.remove(id)?.cancel()
        repo.get(id)?.let { item ->
            if (item.kind == Kind.TORRENT) torrent(this).pause(item)
            item.state = State.PAUSED
            item.speed = 0
        }
        repo.changed(force = true)
    }

    private suspend fun runQueue() {
        acquireLocks()
        try {
            while (scope.isActive) {
                val items = repo.all()
                val blocked = blockReason()
                if (blocked != null) {
                    running.values.forEach { it.cancel() }
                    items.filter { it.state == State.DOWNLOADING || it.state == State.CONNECTING }.forEach {
                        it.state = State.QUEUED
                        it.error = blocked
                    }
                } else {
                    val active = items.count { running.containsKey(it.id) }
                    val waiting = items.filter { it.state == State.QUEUED && !running.containsKey(it.id) && (!it.scheduled || Scheduler.inWindow(prefs)) }
                        .sortedBy { it.order }
                    var slots = prefs.concurrent - active
                    for (item in waiting) {
                        if (slots <= 0) break
                        val host = host(item.url)
                        val perSite = prefs.concurrentFor(host)
                        if (perSite != null && items.count { running.containsKey(it.id) && host(it.url) == host } >= perSite) continue
                        item.error = null
                        running[item.id] = scope.launch { execute(item) }
                        slots--
                    }
                }
                // Seeding torrents are tracked here too.
                items.filter { it.state == State.SEEDING }.forEach { torrent(this).update(it) }
                repo.changed()
                updateNotification()
                val anything = running.isNotEmpty() || items.any { it.state == State.SEEDING } ||
                    items.any { it.state == State.QUEUED && (!it.scheduled || Scheduler.inWindow(prefs)) && blocked == null }
                if (!anything) break
                delay(1000)
            }
        } finally {
            releaseLocks()
        }
        if (prefs.removeFinishedLinks) repo.remove(repo.all().filter { it.state == State.FINISHED }.map { it.id }, deleteFiles = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun execute(item: DownloadItem) {
        try {
            item.state = State.CONNECTING
            repo.changed()
            when (item.kind) {
                Kind.HTTP -> {
                    item.state = State.DOWNLOADING
                    http.run(item) { repo.changed() }
                    item.audioUrl?.let { mergeAudio(item, it) }
                }
                Kind.HLS -> {
                    item.state = State.DOWNLOADING
                    hls.run(item) { repo.changed() }
                }
                Kind.TORRENT -> runTorrent(item)
            }
            if (item.state != State.SEEDING) finish(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("AionDL", "download ${item.id} failed", e)
            item.speed = 0
            val fatal = e is FatalDownloadError && !prefs.alwaysRetry
            val limit = prefs.retryCount
            if (!fatal && prefs.retry && (limit == 0 || item.retries < limit)) {
                item.retries++
                item.error = "${e.message ?: e.javaClass.simpleName} (retry ${item.retries})"
                item.state = State.CONNECTING
                repo.changed(force = true)
                delay(prefs.retryIntervalSec * 1000L)
                if (!item.resumable && item.kind == Kind.HTTP && prefs.autoRestartNonResumable) {
                    item.parts.clear()
                    item.doneBytes = 0
                }
                item.state = State.QUEUED
            } else {
                item.state = State.ERROR
                item.error = e.message ?: e.javaClass.simpleName
                notifyEvent(item, success = false)
            }
        } finally {
            running.remove(item.id)
            repo.changed(force = true)
        }
    }

    /**
     * YouTube serves video and audio separately. After the video part is done, fetch the audio
     * stream and merge both into the final file without re-encoding. If the formats can't be
     * merged, the audio is kept next to the video as its own file.
     */
    private suspend fun mergeAudio(item: DownloadItem, audioUrl: String) {
        item.state = State.MERGING
        item.error = "Downloading audio…"
        repo.changed(force = true)
        val work = File(cacheDir, "merge").apply { mkdirs() }
        val audio = DownloadItem(
            id = -item.id, url = audioUrl, fileName = "audio-${item.id}", dir = work.absolutePath, kind = Kind.HTTP,
            threads = item.threads, referer = item.referer, userAgent = item.userAgent, cookies = item.cookies,
            headers = item.headers,
        )
        audio.file.delete()
        http.run(audio) {}
        item.error = "Merging video and audio…"
        repo.changed(force = true)
        val webm = item.fileName.endsWith(".webm", true)
        val merged = File(work, "merged-${item.id}")
        if (HlsEngine.merge(item.file, audio.file, merged, webm)) {
            item.file.delete()
            if (!merged.renameTo(item.file)) {
                merged.copyTo(item.file, overwrite = true)
                merged.delete()
            }
            audio.file.delete()
        } else {
            val ext = if (webm) "webm" else "m4a"
            val target = File(item.dir, item.fileName.substringBeforeLast('.') + " (audio).$ext")
            audio.file.copyTo(target, overwrite = true)
            audio.file.delete()
            MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        }
        item.totalBytes = item.file.length()
        item.doneBytes = item.totalBytes
        item.audioUrl = null
        item.error = null
    }

    private suspend fun runTorrent(item: DownloadItem) {
        val engine = torrent(this)
        withContext(Dispatchers.IO) { engine.start(item) }
        item.state = State.DOWNLOADING
        while (true) {
            delay(1000)
            val done = engine.update(item)
            repo.changed()
            if (done) {
                if (item.seed) {
                    item.state = State.SEEDING
                    notifyEvent(item, success = true)
                }
                return
            }
        }
    }

    private fun finish(item: DownloadItem) {
        item.state = State.FINISHED
        item.finishedAt = System.currentTimeMillis()
        item.speed = 0
        item.retries = 0
        item.parts.clear()
        if (prefs.moveOnFinishDir.isNotBlank()) {
            val target = File(prefs.moveOnFinishDir, item.fileName)
            target.parentFile?.mkdirs()
            if (item.file.renameTo(target)) item.dir = prefs.moveOnFinishDir
        }
        MediaScannerConnection.scanFile(this, arrayOf(item.file.absolutePath), null, null)
        notifyEvent(item, success = true)
    }

    private fun blockReason(): String? {
        if (prefs.wifiOnly && !onWifi()) return "Waiting for Wi-Fi"
        if (prefs.batteryLimit > 0) {
            val bm = getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (!bm.isCharging && level in 1 until prefs.batteryLimit) return "Battery below ${prefs.batteryLimit}%"
        }
        return null
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (prefs.wakeLock) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aion:downloads").apply { acquire() }
        }
        if (prefs.wifiLock) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "aion:downloads").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    private fun startInForeground() {
        val notification = progressNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private var lastNotify = 0L

    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, progressNotification())
    }

    private fun progressNotification(): Notification {
        val items = repo.all()
        val active = items.filter { running.containsKey(it.id) }
        val seeding = items.count { it.state == State.SEEDING }
        val speed = active.sumOf { it.speed }
        val title = when {
            active.size == 1 -> active[0].fileName
            active.isNotEmpty() -> "${active.size} downloads"
            seeding > 0 -> "Seeding $seeding torrent(s)"
            else -> "Aion Downloads"
        }
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0) }
        val done = active.sumOf { it.doneBytes }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pauseAll = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (active.isEmpty()) "Waiting" else "${Format.speed(speed)} · ${Format.size(done)} / ${Format.size(total)}")
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0 && active.isNotEmpty())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(if (prefs.hideOnLockScreen) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Pause all", pauseAll).build())
            .build()
    }

    private fun notifyEvent(item: DownloadItem, success: Boolean) {
        if (success && prefs.toastOnComplete || !success && prefs.toastOnError) {
            android.os.Handler(mainLooper).post {
                Toast.makeText(this, if (success) "Downloaded ${item.fileName}" else "Failed: ${item.fileName}", Toast.LENGTH_SHORT).show()
            }
        }
        if (success && prefs.notifyCompleteSound) runCatching {
            RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play()
        }
        if (success && prefs.vibrateComplete) {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        if (!prefs.notifyEach) return
        val open = PendingIntent.getActivity(
            this, item.id.toInt(), Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_ID, item.id), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "Download complete" else "Download failed")
            .setContentText(item.fileName + (item.error?.let { " · $it" } ?: ""))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(1000 + item.id.toInt(), n)
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull()

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
        const val NOTIFY_ID = 1
        const val ACTION_PAUSE = "pause"
        const val ACTION_PAUSE_ALL = "pause_all"
        const val ACTION_STOP_SEEDING = "stop_seeding"
        const val EXTRA_IDS = "ids"
        const val EXTRA_OPEN_ID = "open_id"

        @Volatile private var torrentEngine: TorrentEngine? = null
        fun torrent(context: Context) = torrentEngine ?: synchronized(this) {
            torrentEngine ?: TorrentEngine(context.applicationContext, Prefs(context)).also { torrentEngine = it }
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** Wakes the queue runner. Call after adding, resuming or changing downloads. */
        fun kick(context: Context, action: String? = null, ids: LongArray? = null) {
            val intent = Intent(context, DownloadService::class.java).setAction(action).putExtra(EXTRA_IDS, ids)
            context.startForegroundService(intent)
        }
    }
}

object Format {
    fun sizeSuffix(bytes: Long) = if (bytes > 0) " · " + size(bytes) else ""

    fun size(bytes: Long): String {
        if (bytes < 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.size - 1) {
            v /= 1024
            u++
        }
        return if (u == 0) "$bytes B" else String.format("%.1f %s", v, units[u])
    }

    fun speed(bytesPerSec: Long) = size(bytesPerSec) + "/s"

    fun eta(item: DownloadItem): String {
        if (item.speed <= 0 || item.totalBytes <= 0) return ""
        val s = (item.totalBytes - item.doneBytes) / item.speed
        return when {
            s >= 3600 -> "${s / 3600}h ${s % 3600 / 60}m"
            s >= 60 -> "${s / 60}m ${s % 60}s"
            else -> "${s}s"
        }
    }
}
