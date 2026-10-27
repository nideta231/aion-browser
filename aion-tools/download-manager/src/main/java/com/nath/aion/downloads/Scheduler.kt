package com.nath.aion.downloads

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.Calendar

/**
 * Download scheduler: at the start time it queues every "scheduled" download, at the stop time it
 * pauses them. Days are a bitmask (bit 0 = Monday). Uses exact alarms when allowed.
 */
object Scheduler {
    private const val ACTION_START = "com.nath.aion.downloads.SCHEDULE_START"
    private const val ACTION_STOP = "com.nath.aion.downloads.SCHEDULE_STOP"

    fun inWindow(prefs: Prefs, now: Calendar = Calendar.getInstance()): Boolean {
        if (!prefs.schedulerEnabled) return true
        val day = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
        if (prefs.scheduleDays and (1 shl day) == 0) return false
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = prefs.scheduleStart
        val stop = prefs.scheduleStop
        if (stop < 0) return minute >= start
        return if (start <= stop) minute in start until stop else minute >= start || minute < stop
    }

    fun reschedule(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val start = pending(context, ACTION_START)
        val stop = pending(context, ACTION_STOP)
        am.cancel(start)
        am.cancel(stop)
        if (!prefs.schedulerEnabled) return
        set(am, next(prefs.scheduleStart), start)
        if (prefs.scheduleStop >= 0) set(am, next(prefs.scheduleStop), stop)
    }

    private fun set(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun next(minuteOfDay: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        c.set(Calendar.MINUTE, minuteOfDay % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun pending(context: Context, action: String) = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, SchedulerReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun onAlarm(context: Context, action: String?) {
        val repo = Repository.get(context)
        when (action) {
            ACTION_START -> {
                repo.all().filter { it.scheduled && (it.state == State.PAUSED || it.state == State.ERROR) }.forEach {
                    it.state = State.QUEUED
                    it.retries = 0
                }
                repo.changed(force = true)
                DownloadService.kick(context)
            }
            ACTION_STOP -> {
                val ids = repo.all().filter { it.scheduled && it.active }.map { it.id }.toLongArray()
                if (ids.isNotEmpty()) DownloadService.kick(context, DownloadService.ACTION_PAUSE, ids)
            }
        }
        reschedule(context)
    }
}

class SchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.onAlarm(context, intent.action)
}

/** Restarts the queue, scheduler and clipboard monitor after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.reschedule(context)
        if (Repository.get(context).all().any { it.state == State.QUEUED }) DownloadService.kick(context)
        if (Prefs(context).smartClipboard) ClipboardService.start(context)
    }
}

/**
 * "Smart download": while enabled, watches the clipboard and offers a one-tap download for copied
 * links (1DM shows a persistent notification for this). Android only delivers clipboard changes to
 * a foreground service.
 */
class ClipboardService : Service() {
    private lateinit var clipboard: ClipboardManager
    private var last: String? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onClip() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadService.createChannels(this)
        val n = Notification.Builder(this, DownloadService.CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle("Watching clipboard for links")
            .setContentText("Copy a download link to add it")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(7, n)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(listener)
    }

    private fun onClip() {
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
        if (text == last) return
        last = text
        val prefs = Prefs(this)
        val url = Links.extract(text).firstOrNull() ?: return
        if (!prefs.captureAllUrls && !Links.looksDownloadable(url)) return
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext in prefs.excludeExtensions.split(',', ' ').map { it.trim().lowercase() }) return
        val intent = Intent(this, AddActivity::class.java).setData(android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, DownloadService.CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Download copied link?")
            .setContentText(url)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java).notify(8, n)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(listener)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) = context.startForegroundService(Intent(context, ClipboardService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, ClipboardService::class.java))
    }
}

object Links {
    private val urlRegex = Regex("(magnet:\\?[^\\s\"'<>]+|https?://[^\\s\"'<>]+)")
    private val downloadable = Regex(
        "\\.(zip|rar|7z|tar|gz|xz|iso|apk|apks|xapk|exe|msi|dmg|deb|mp4|mkv|webm|avi|mov|m4v|mp3|m4a|flac|wav|ogg|opus|pdf|epub|docx?|xlsx?|pptx?|torrent|m3u8|bin|img)(\\?|$)",
        RegexOption.IGNORE_CASE,
    )

    fun extract(text: String) = urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ')') }.toList()

    fun looksDownloadable(url: String) = url.startsWith("magnet:") || downloadable.containsMatchIn(url.substringBefore('#'))

    /**
     * Expands batch patterns like "https://x/img[001-120].jpg" or "file[a-z].zip" into a list.
     */
    fun expandBatch(pattern: String): List<String> {
        val m = Regex("\\[(\\w+)-(\\w+)]").find(pattern) ?: return listOf(pattern)
        val (from, to) = m.destructured
        val values: List<String> = if (from.all { it.isDigit() } && to.all { it.isDigit() }) {
            val width = if (from.startsWith("0")) from.length else 0
            (from.toInt()..to.toInt()).map { it.toString().padStart(width, '0') }
        } else if (from.length == 1 && to.length == 1) {
            (from[0]..to[0]).map { it.toString() }
        } else {
            return listOf(pattern)
        }
        return values.flatMap { expandBatch(pattern.replaceRange(m.range, it)) }.take(10000)
    }
}
