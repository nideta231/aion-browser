package com.nath.aion.downloads

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** All settings in one scrolling page, grouped like 1DM's settings sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var refresh by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        @Suppress("UNUSED_EXPRESSION") refresh
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Section("General")
            TextPref("Download folder", prefs.downloadDir) { prefs.downloadDir = it }
            SwitchPref("Wi-Fi only", prefs.wifiOnly) { prefs.wifiOnly = it }
            SwitchPref("Smart download (watch clipboard for links)", prefs.smartClipboard) {
                prefs.smartClipboard = it
                if (it) ClipboardService.start(context) else ClipboardService.stop(context)
            }
            SwitchPref("Capture all copied URLs, not only files", prefs.captureAllUrls) { prefs.captureAllUrls = it }
            TextPref("Exclude extensions (comma separated)", prefs.excludeExtensions) { prefs.excludeExtensions = it }
            SwitchPref("Skip download editor", prefs.skipEditor) { prefs.skipEditor = it }
            SwitchPref("Auto-start new downloads", prefs.autoStart) { prefs.autoStart = it }
            SwitchPref("Auto-rename if the file exists", prefs.autoRename) { prefs.autoRename = it }

            Section("Speed & connections")
            SliderPref("Parts per download", prefs.parts, 1..32) { prefs.parts = it }
            SliderPref("Simultaneous downloads", prefs.concurrent, 1..30) { prefs.concurrent = it }
            SwitchPref("Speed limit", prefs.speedLimitEnabled) { prefs.speedLimitEnabled = it }
            NumberPref("Download limit (KB/s)", prefs.speedLimitKb) { prefs.speedLimitKb = it }
            NumberPref("Upload limit for torrents (KB/s, 0 = none)", prefs.uploadLimitKb) { prefs.uploadLimitKb = it }
            NumberPref("Connection timeout (seconds)", prefs.timeoutSec) { prefs.timeoutSec = it }
            TextPref("User agent", prefs.userAgent) { prefs.userAgent = it }
            TextPref("Parts per site (host=parts, one per line)", prefs.domainPartLimits, lines = 3) { prefs.domainPartLimits = it }
            TextPref("Simultaneous downloads per site (host=n)", prefs.domainConcurrentLimits, lines = 3) { prefs.domainConcurrentLimits = it }

            Section("Retry")
            SwitchPref("Retry failed downloads", prefs.retry) { prefs.retry = it }
            NumberPref("Retry count (0 = unlimited)", prefs.retryCount) { prefs.retryCount = it }
            NumberPref("Retry interval (seconds)", prefs.retryIntervalSec) { prefs.retryIntervalSec = it }
            SwitchPref("Retry even on unrecoverable errors (404, 403…)", prefs.alwaysRetry) { prefs.alwaysRetry = it }
            SwitchPref("Auto-restart links that can't resume", prefs.autoRestartNonResumable) { prefs.autoRestartNonResumable = it }

            Section("Power")
            SliderPref("Stop below battery % (0 = off, ignored while charging)", prefs.batteryLimit, 0..90) { prefs.batteryLimit = it }
            SwitchPref("Keep CPU awake while downloading", prefs.wakeLock) { prefs.wakeLock = it }
            SwitchPref("Keep Wi-Fi awake while downloading", prefs.wifiLock) { prefs.wifiLock = it }
            TextButton({
                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
            }) { Text("Disable battery optimization (fixes background downloads)") }

            Section("Scheduler")
            SwitchPref("Enable scheduler", prefs.schedulerEnabled) {
                prefs.schedulerEnabled = it
                if (it && Build.VERSION.SDK_INT >= 31) {
                    val am = context.getSystemService(android.app.AlarmManager::class.java)
                    if (!am.canScheduleExactAlarms()) runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }
                Scheduler.reschedule(context)
            }
            TimePref("Start time", prefs.scheduleStart) { prefs.scheduleStart = it; Scheduler.reschedule(context) }
            TimePref("Stop time (empty = no stop)", prefs.scheduleStop) { prefs.scheduleStop = it; Scheduler.reschedule(context) }
            Text("Days")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                    var on by remember { mutableStateOf(prefs.scheduleDays and (1 shl i) != 0) }
                    FilterChip(on, {
                        on = !on
                        prefs.scheduleDays = if (on) prefs.scheduleDays or (1 shl i) else prefs.scheduleDays and (1 shl i).inv()
                        Scheduler.reschedule(context)
                    }, { Text(d) })
                }
            }
            SwitchPref("Add new links to the scheduled list", prefs.scheduleNewLinks) { prefs.scheduleNewLinks = it }

            Section("Video")
            Text("When a video or audio link is opened")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ask" to "Ask (editor with Play)", "play" to "Play", "download" to "Download").forEach { (key, label) ->
                    FilterChip(prefs.videoLinkAction == key, { prefs.videoLinkAction = key; refresh++ }, { Text(label) })
                }
            }
            SwitchPref("Convert HLS (.ts) to .mp4 after download", prefs.convertTs) { prefs.convertTs = it }

            Section("Automation")
            SwitchPref("Remove finished downloads from the list", prefs.removeFinishedLinks) { prefs.removeFinishedLinks = it }
            TextPref("Move finished files to (empty = off)", prefs.moveOnFinishDir) { prefs.moveOnFinishDir = it }

            Section("Notifications")
            SwitchPref("Notify when each download finishes or fails", prefs.notifyEach) { prefs.notifyEach = it }
            SwitchPref("Play sound on completion", prefs.notifyCompleteSound) { prefs.notifyCompleteSound = it }
            SwitchPref("Vibrate on completion", prefs.vibrateComplete) { prefs.vibrateComplete = it }
            SwitchPref("Toast on completion", prefs.toastOnComplete) { prefs.toastOnComplete = it }
            SwitchPref("Toast on error", prefs.toastOnError) { prefs.toastOnError = it }
            SwitchPref("Hide download notifications on the lock screen", prefs.hideOnLockScreen) { prefs.hideOnLockScreen = it }

            Section("Proxy")
            SwitchPref("Use proxy", prefs.proxyEnabled) { prefs.proxyEnabled = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("HTTP", "SOCKS").forEach { t ->
                    FilterChip(prefs.proxyType == t, { prefs.proxyType = t; refresh++ }, { Text(t) })
                }
            }
            TextPref("Proxy host", prefs.proxyHost) { prefs.proxyHost = it }
            NumberPref("Proxy port", prefs.proxyPort) { prefs.proxyPort = it }
            TextPref("Proxy username", prefs.proxyUser) { prefs.proxyUser = it }
            TextPref("Proxy password", prefs.proxyPass) { prefs.proxyPass = it }

            Section("Torrent (applies after restarting the app)")
            SwitchPref("Fetch file list from magnet links first", prefs.fetchMagnetMetadata) { prefs.fetchMagnetMetadata = it }
            SwitchPref("Seed finished torrents", prefs.seedFinished) { prefs.seedFinished = it }
            SwitchPref("Sequential download by default", prefs.sequentialDefault) { prefs.sequentialDefault = it }
            SwitchPref("DHT", prefs.dht) { prefs.dht = it }
            SwitchPref("Local peer discovery (LSD)", prefs.lsd) { prefs.lsd = it }
            SwitchPref("Peer exchange (PeX)", prefs.pex) { prefs.pex = it }
            SwitchPref("UPnP", prefs.upnp) { prefs.upnp = it }
            SwitchPref("NAT-PMP", prefs.natPmp) { prefs.natPmp = it }
            SwitchPref("Random port", prefs.randomPort) { prefs.randomPort = it }
            NumberPref("Port", prefs.port) { prefs.port = it }
            NumberPref("Max connections", prefs.maxConnections) { prefs.maxConnections = it }
            NumberPref("Max peers", prefs.maxPeers) { prefs.maxPeers = it }
            Text("Encryption")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Enabled", "Forced", "Disabled").forEachIndexed { i, t ->
                    FilterChip(prefs.encryption == i, { prefs.encryption = i; refresh++ }, { Text(t) })
                }
            }
            TextPref("Extra trackers (one per line)", prefs.extraTrackers, lines = 4) { prefs.extraTrackers = it }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SwitchPref(label: String, value: Boolean, set: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    LabeledSwitch(label, v) { v = it; set(it) }
}

@Composable
private fun TextPref(label: String, value: String, lines: Int = 1, set: (String) -> Unit) {
    var v by remember { mutableStateOf(value) }
    OutlinedTextField(v, { v = it; set(it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = lines == 1, minLines = lines)
}

@Composable
private fun NumberPref(label: String, value: Int, set: (Int) -> Unit) {
    var v by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(v, { s -> v = s.filter(Char::isDigit); v.toIntOrNull()?.let(set) }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun SliderPref(label: String, value: Int, range: IntRange, set: (Int) -> Unit) {
    var v by remember { mutableStateOf(value.toFloat()) }
    Text("$label: ${v.toInt()}")
    Slider(v, { v = it; set(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0))
}

@Composable
private fun TimePref(label: String, minutes: Int, set: (Int) -> Unit) {
    var v by remember { mutableStateOf(if (minutes < 0) "" else "%02d:%02d".format(minutes / 60, minutes % 60)) }
    OutlinedTextField(v, { s ->
        v = s
        if (s.isBlank()) set(-1) else Regex("(\\d{1,2}):(\\d{2})").matchEntire(s.trim())?.let { m ->
            val (h, mm) = m.destructured
            if (h.toInt() < 24 && mm.toInt() < 60) set(h.toInt() * 60 + mm.toInt())
        }
    }, label = { Text("$label (HH:MM)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}
