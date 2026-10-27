package com.nath.aion.downloads

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/** All download-manager settings, mirroring 1DM's settings screens. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("aion_downloads", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun long(key: String, def: Long) = sp.getLong(key, def)

    // General
    var downloadDir: String
        get() = str("dir", File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Aion").absolutePath)
        set(v) = sp.edit().putString("dir", v).apply()
    var smartClipboard by BoolPref("smart_clipboard", false)
    var lastClipboardOffer by StrPref("last_clip_offer", "")
    var captureAllUrls by BoolPref("capture_all_urls", false)
    var excludeExtensions by StrPref("exclude_ext", "")
    var skipEditor by BoolPref("skip_editor", false)
    var autoStart by BoolPref("auto_start", true)
    var autoRename by BoolPref("auto_rename", true)
    var wifiOnly by BoolPref("wifi_only", false)

    // Advanced
    var parts by IntPref("parts", 8)                 // 1..32
    var concurrent by IntPref("concurrent", 3)       // 1..30
    var timeoutSec by IntPref("timeout", 60)
    var userAgent by StrPref("user_agent", DEFAULT_UA)
    var batteryLimit by IntPref("battery_limit", 0)  // stop below %, 0 = off
    var speedLimitEnabled by BoolPref("speed_limit_on", false)
    var speedLimitKb by IntPref("speed_limit_kb", 10240)
    var uploadLimitKb by IntPref("upload_limit_kb", 0)
    var wakeLock by BoolPref("wake_lock", true)
    var wifiLock by BoolPref("wifi_lock", true)
    var convertTs by BoolPref("convert_ts", true)
    var autoRestartNonResumable by BoolPref("auto_restart_nonresumable", false)
    var domainPartLimits by StrPref("domain_part_limits", "")    // "host=parts" per line
    var domainConcurrentLimits by StrPref("domain_concurrent", "") // "host=n" per line

    // Retry
    var retry by BoolPref("retry", true)
    var retryCount by IntPref("retry_count", 0) // 0 = unlimited
    var retryIntervalSec by IntPref("retry_interval", 5)
    var alwaysRetry by BoolPref("always_retry", false)

    // Proxy
    var proxyEnabled by BoolPref("proxy", false)
    var proxyType by StrPref("proxy_type", "HTTP") // HTTP or SOCKS
    var proxyHost by StrPref("proxy_host", "")
    var proxyPort by IntPref("proxy_port", 0)
    var proxyUser by StrPref("proxy_user", "")
    var proxyPass by StrPref("proxy_pass", "")

    // Automation
    var removeFinishedLinks by BoolPref("remove_finished", false)
    var moveOnFinishDir by StrPref("move_finished_dir", "")
    var deleteLinksAfterDays by IntPref("delete_links_days", 0)
    var deleteOnlyFinished by BoolPref("delete_only_finished", true)
    var exitWhenQueueEnds by BoolPref("exit_queue_end", false)

    // Scheduler
    var schedulerEnabled by BoolPref("scheduler", false)
    var scheduleStart by IntPref("sched_start", 1 * 60)      // minutes after midnight
    var scheduleStop by IntPref("sched_stop", 7 * 60)        // -1 = no stop
    var scheduleDays by IntPref("sched_days", 0b1111111)     // bit0 = Monday
    var scheduleNewLinks by BoolPref("sched_new_links", false)

    // Notifications
    var notifyEach by BoolPref("notify_each", true)
    var combineNotifications by BoolPref("combine_notifications", false)
    var notifyCompleteSound by BoolPref("sound_complete", false)
    var vibrateComplete by BoolPref("vibrate_complete", false)
    var toastOnComplete by BoolPref("toast_complete", false)
    var toastOnError by BoolPref("toast_error", false)
    var hideOnLockScreen by BoolPref("hide_lock", false)

    // Torrent
    var torrentEnabled by BoolPref("torrent", true)
    var dht by BoolPref("dht", true)
    var lsd by BoolPref("lsd", true)
    var pex by BoolPref("pex", true)
    var upnp by BoolPref("upnp", true)
    var natPmp by BoolPref("natpmp", true)
    var seedFinished by BoolPref("seed_finished", false)
    var sequentialDefault by BoolPref("sequential", false)
    var maxConnections by IntPref("max_connections", 500)
    var maxPeers by IntPref("max_peers", 500)
    var randomPort by BoolPref("random_port", true)
    var port by IntPref("port", 6881)
    var encryption by IntPref("encryption", 0) // 0 = enabled, 1 = forced, 2 = disabled
    var extraTrackers by StrPref("extra_trackers", "")
    var trackerListUrl by StrPref("tracker_list_url", "")
    var fetchMagnetMetadata by BoolPref("fetch_metadata", true)

    val nextId: Long get() = long("next_id", 1).also { sp.edit().putLong("next_id", it + 1).apply() }

    fun partsFor(host: String?): Int {
        host ?: return parts
        return limitFor(domainPartLimits, host) ?: parts
    }

    fun concurrentFor(host: String?): Int? = host?.let { limitFor(domainConcurrentLimits, it) }

    private fun limitFor(table: String, host: String): Int? = table.lines().mapNotNull { line ->
        val (h, v) = line.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
        if (host == h.trim() || host.endsWith("." + h.trim())) v.trim().toIntOrNull() else null
    }.firstOrNull()

    inner class BoolPref(private val key: String, private val def: Boolean) {
        operator fun getValue(r: Any?, p: Any?) = bool(key, def)
        operator fun setValue(r: Any?, p: Any?, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    }

    inner class IntPref(private val key: String, private val def: Int) {
        operator fun getValue(r: Any?, p: Any?) = int(key, def)
        operator fun setValue(r: Any?, p: Any?, v: Int) = sp.edit().putInt(key, v).apply()
    }

    inner class StrPref(private val key: String, private val def: String) {
        operator fun getValue(r: Any?, p: Any?) = str(key, def)
        operator fun setValue(r: Any?, p: Any?, v: String) = sp.edit().putString(key, v).apply()
    }

    companion object {
        const val PLAIN_UA = "AionDownloads/0.1 (Android)"
        const val DEFAULT_UA =
            "Mozilla/5.0 (Android 15; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0"
    }
}
