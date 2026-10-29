package com.nath.aion.downloads

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadService.createChannels(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Allow file access so downloads can be saved to Download/Aion", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
        }
        if (Repository.get(this).all().any { it.state == State.QUEUED }) DownloadService.kick(this)
        if (Prefs(this).smartClipboard) runCatching { ClipboardService.start(this) }
        enableEdgeToEdge()
        setContent {
            AionTheme {
                // Keep everything inside the visible screen area (no buttons under bars or off the edge).
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    DownloadsScreen()
                }
            }
        }
    }

    private var lastOfferedClip: String? = null

    /**
     * Android 10+ only lets the focused app read the clipboard, so like 1DM we also check it when
     * the window gains focus and offer any new download link found there.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val prefs = Prefs(this)
        if (!prefs.smartClipboard) return
        val text = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        }.getOrNull() ?: return
        if (text == lastOfferedClip || text == prefs.lastClipboardOffer) return
        val url = Links.extract(text).firstOrNull() ?: return
        if (!prefs.captureAllUrls && !Links.looksDownloadable(url)) return
        if (Repository.get(this).all().any { it.url == url }) return
        lastOfferedClip = text
        prefs.lastClipboardOffer = text
        startActivity(Intent(this, AddActivity::class.java).setData(Uri.parse(url)))
    }
}

@Composable
fun AionTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9E7BFF))
        else -> lightColorScheme(primary = Color(0xFF6A3DF0))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String) { ALL("All"), ACTIVE("Active"), FINISHED("Finished"), SCHEDULED("Scheduled"), TORRENTS("Torrents") }
private enum class Sort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), NAME("Name"), SIZE("Size"), CUSTOM("Queue order") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val items by repo.items.collectAsState()
    var tab by remember { mutableStateOf(Tab.ALL) }
    var category by remember { mutableStateOf<Category?>(null) }
    var sort by remember { mutableStateOf(Sort.NEWEST) }
    var query by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Long?>(null) }

    if (showSettings) {
        SettingsScreen { showSettings = false }
        return
    }

    val visible = items.filter {
        when (tab) {
            Tab.ALL -> true
            Tab.ACTIVE -> it.active || it.state == State.PAUSED || it.state == State.ERROR
            Tab.FINISHED -> it.state == State.FINISHED || it.state == State.SEEDING
            Tab.SCHEDULED -> it.scheduled
            Tab.TORRENTS -> it.kind == Kind.TORRENT
        }
    }.filter { category == null || it.category == category }
        .filter { query.isNullOrBlank() || it.fileName.contains(query!!, true) || it.url.contains(query!!, true) }
        .let { list ->
            when (sort) {
                Sort.NEWEST -> list.sortedByDescending { it.addedAt }
                Sort.OLDEST -> list.sortedBy { it.addedAt }
                Sort.NAME -> list.sortedBy { it.fileName.lowercase() }
                Sort.SIZE -> list.sortedByDescending { it.totalBytes }
                Sort.CUSTOM -> list.sortedBy { it.order }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (query != null) {
                        OutlinedTextField(query!!, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        Column {
                            Text("Aion Downloads", fontWeight = FontWeight.Bold)
                            val speed = items.sumOf { it.speed }
                            val running = items.count { it.state == State.DOWNLOADING }
                            if (running > 0) Text("$running running · ${Format.speed(speed)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    IconButton({ query = if (query == null) "" else null }) { Icon(if (query == null) Icons.Default.Search else Icons.Default.Close, "Search") }
                    IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Start all") }, {
                                menu = false
                                items.filter { it.state == State.PAUSED || it.state == State.ERROR }.forEach { repo.get(it.id)?.apply { state = State.QUEUED; retries = 0 } }
                                repo.changed(true); DownloadService.kick(context)
                            })
                            DropdownMenuItem({ Text("Pause all") }, { menu = false; DownloadService.kick(context, DownloadService.ACTION_PAUSE_ALL) })
                            DropdownMenuItem({ Text("Start scheduled now") }, {
                                menu = false
                                items.filter { it.scheduled && it.state != State.FINISHED }.forEach { repo.get(it.id)?.apply { scheduled = false; state = State.QUEUED } }
                                repo.changed(true); DownloadService.kick(context)
                            })
                            DropdownMenuItem({ Text("Remove finished from list") }, {
                                menu = false; repo.remove(items.filter { it.state == State.FINISHED }.map { it.id }, false)
                            })
                            DropdownMenuItem({ Text("Download from clipboard") }, {
                                menu = false
                                val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                val links = Links.extract(text)
                                if (links.isEmpty()) Toast.makeText(context, "No link in clipboard", Toast.LENGTH_SHORT).show()
                                else context.startActivity(Intent(context, AddActivity::class.java).putExtra("urls", links.joinToString("\n")))
                            })
                            DropdownMenuItem({ Text("Export links") }, {
                                menu = false
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("links", repo.exportLinks()))
                                Toast.makeText(context, "All links copied", Toast.LENGTH_SHORT).show()
                            })
                            Sort.entries.forEach { s ->
                                DropdownMenuItem({ Text("Sort: ${s.label}" + if (s == sort) "  ✓" else "") }, { sort = s; menu = false })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton({ context.startActivity(Intent(context, AddActivity::class.java).putExtra("urls", "https://")) }) {
                Icon(Icons.Default.Add, "Add download")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Tab.entries) { t -> FilterChip(t == tab, { tab = t }, { Text(t.label) }) }
            }
            LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Category.entries) { c ->
                    val count = items.count { it.category == c }
                    if (count > 0) FilterChip(c == category, { category = if (category == c) null else c }, { Text("${c.label} $count") })
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No downloads yet.\nTap + or download from Aion.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { item -> DownloadRow(item, repo, sort == Sort.CUSTOM) { detail = item.id } }
                }
            }
        }
    }
    detail?.let { id -> items.firstOrNull { it.id == id }?.let { DetailDialog(it, repo) { detail = null } } ?: run { detail = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(item: DownloadItem, repo: Repository, reorder: Boolean, onDetails: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = { if (item.state == State.FINISHED) open(context, item) else onDetails() }, onLongClick = { menu = true }),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Spacer(Modifier.size(6.dp))
                if (item.state != State.FINISHED) {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(6.dp))
                }
                Text(statusLine(item), style = MaterialTheme.typography.bodySmall, color = if (item.state == State.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            when {
                item.active || item.state == State.SEEDING -> IconButton({ DownloadService.kick(context, DownloadService.ACTION_PAUSE, longArrayOf(item.id)) }) {
                    Text("❚❚", fontWeight = FontWeight.Bold)
                }
                item.state == State.PAUSED || item.state == State.ERROR -> IconButton({ resume(context, repo, item.id) }) {
                    Icon(if (item.state == State.ERROR) Icons.Default.Refresh else Icons.Default.PlayArrow, "Resume")
                }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                ItemMenu(item, repo, menu, reorder, { menu = false }, onDetails)
            }
        }
    }
}

private fun statusLine(item: DownloadItem): String {
    val size = if (item.totalBytes > 0) "${Format.size(item.doneBytes)} / ${Format.size(item.totalBytes)}" else Format.size(item.doneBytes)
    return when (item.state) {
        State.QUEUED -> "Queued" + (item.error?.let { " · $it" } ?: "")
        State.CONNECTING -> "Connecting…" + (item.error?.let { " · $it" } ?: "")
        State.DOWNLOADING -> buildString {
            append("${(item.progress * 100).toInt()}% · $size · ${Format.speed(item.speed)}")
            Format.eta(item).takeIf { it.isNotEmpty() }?.let { append(" · $it left") }
            if (item.kind == Kind.HLS) append(" · ${item.segmentsDone}/${item.segmentsTotal} segments")
            if (item.kind == Kind.TORRENT) append(" · ${item.peers} peers")
            if (item.kind == Kind.HTTP && item.parts.size > 1) append(" · ${item.parts.size} parts")
        }
        State.PAUSED -> "Paused · $size" + if (item.scheduled) " · scheduled" else ""
        State.MERGING -> "Merging video…"
        State.FINISHED -> "${Format.size(item.totalBytes)} · ${java.text.DateFormat.getDateTimeInstance().format(item.finishedAt)}"
        State.SEEDING -> "Seeding · ↑ ${Format.speed(item.uploadSpeed)} · uploaded ${Format.size(item.uploaded)}"
        State.ERROR -> "Error: ${item.error}"
    }
}

private fun resume(context: android.content.Context, repo: Repository, id: Long) {
    repo.get(id)?.apply {
        state = State.QUEUED
        retries = 0
        error = null
        scheduled = false
    }
    repo.changed(true)
    DownloadService.kick(context)
}

@Composable
private fun ItemMenu(item: DownloadItem, repo: Repository, expanded: Boolean, reorder: Boolean, dismiss: () -> Unit, onDetails: () -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    DropdownMenu(expanded, dismiss) {
        if (item.state == State.FINISHED) {
            DropdownMenuItem({ Text("Open") }, { dismiss(); open(context, item) })
            DropdownMenuItem({ Text("Share") }, { dismiss(); share(context, item) })
        }
        if (item.kind != Kind.TORRENT && (item.state == State.FINISHED || PlayerActivity.looksPlayable(item.url, item.mime) ||
                PlayerActivity.looksPlayable(item.fileName, item.mime))
        ) {
            DropdownMenuItem({ Text(if (item.state == State.FINISHED) "Play in Aion player" else "Preview (stream)") }, {
                dismiss(); context.startActivity(PlayerActivity.forItem(context, item))
            })
        }
        DropdownMenuItem({ Text("Details") }, { dismiss(); onDetails() })
        if (item.state != State.FINISHED) {
            DropdownMenuItem({ Text(if (item.scheduled) "Remove from scheduler" else "Add to scheduler") }, {
                dismiss(); repo.get(item.id)?.let { it.scheduled = !it.scheduled }; repo.changed(true)
            })
        }
        if (item.kind != Kind.TORRENT && item.state != State.FINISHED) {
            DropdownMenuItem({ Text("Refresh link (paste new URL)") }, {
                dismiss()
                val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                val link = text?.let { Links.extract(it).firstOrNull() }
                if (link == null) {
                    Toast.makeText(context, "Copy the new download link first, then use this", Toast.LENGTH_LONG).show()
                } else {
                    repo.get(item.id)?.let { it.url = link; it.error = null }
                    repo.changed(true)
                    Toast.makeText(context, "Link updated", Toast.LENGTH_SHORT).show()
                }
            })
            DropdownMenuItem({ Text("Refresh link in browser") }, {
                dismiss()
                val page = item.referer ?: item.url
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).setPackage("com.nath.aion").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
        if (item.kind == Kind.TORRENT) {
            DropdownMenuItem({ Text(if (item.seed) "Stop seeding" else "Seed") }, {
                dismiss(); repo.get(item.id)?.let { it.seed = !it.seed; if (it.seed && it.state == State.FINISHED) it.state = State.QUEUED }
                repo.changed(true); DownloadService.kick(context)
            })
            DropdownMenuItem({ Text("Force recheck") }, { dismiss(); DownloadService.torrent(context).recheck(item) })
            DropdownMenuItem({ Text("Force reannounce") }, { dismiss(); DownloadService.torrent(context).reannounce(item) })
            DropdownMenuItem({ Text("Copy magnet link") }, {
                dismiss(); copy(context, DownloadService.torrent(context).magnetOf(item))
            })
        }
        DropdownMenuItem({ Text("Copy download link") }, { dismiss(); copy(context, item.url) })
        DropdownMenuItem({ Text("Redownload") }, {
            dismiss()
            repo.get(item.id)?.apply { state = State.QUEUED; parts.clear(); doneBytes = 0; segmentsDone = 0; error = null; retries = 0 }
            item.file.delete()
            repo.changed(true); DownloadService.kick(context)
        })
        if (reorder) {
            DropdownMenuItem({ Text("Move up") }, { dismiss(); repo.move(item.id, true) })
            DropdownMenuItem({ Text("Move down") }, { dismiss(); repo.move(item.id, false) })
        }
        DropdownMenuItem({ Text("Remove") }, { dismiss(); confirmDelete = true })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove download?") },
            text = { Text(item.fileName) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    if (item.kind == Kind.TORRENT) DownloadService.torrent(context).remove(item, true)
                    DownloadService.kick(context, DownloadService.ACTION_PAUSE, longArrayOf(item.id))
                    repo.remove(listOf(item.id), deleteFiles = true)
                }) { Text("Remove and delete file") }
            },
            dismissButton = {
                TextButton({
                    confirmDelete = false
                    if (item.kind == Kind.TORRENT) DownloadService.torrent(context).remove(item, false)
                    DownloadService.kick(context, DownloadService.ACTION_PAUSE, longArrayOf(item.id))
                    repo.remove(listOf(item.id), deleteFiles = false)
                }) { Text("Remove from list") }
            },
        )
    }
}

@Composable
private fun DetailDialog(item: DownloadItem, repo: Repository, dismiss: () -> Unit) {
    val context = LocalContext.current
    var limit by remember { mutableStateOf(if (item.speedLimit > 0) (item.speedLimit / 1024).toString() else "") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(item.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status: ${statusLine(item)}")
                Text("Type: ${item.kind} · ${item.category.label}")
                Text("Saved in: ${item.dir}")
                Text("Link: ${item.url}", maxLines = 4, overflow = TextOverflow.Ellipsis)
                item.referer?.let { Text("Page: $it", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (item.kind == Kind.HTTP) Text("Resumable: ${if (item.resumable) "yes" else "no"} · parts: ${item.parts.size.coerceAtLeast(1)}")
                if (item.kind == Kind.TORRENT) Text("Peers ${item.peers} · seeds ${item.seeds} · uploaded ${Format.size(item.uploaded)}")
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text("Speed limit for this file (KB/s, empty = none)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                repo.get(item.id)?.speedLimit = (limit.toLongOrNull() ?: 0) * 1024
                repo.changed(true)
                dismiss()
            }) { Text("Save") }
        },
        dismissButton = { if (item.state == State.FINISHED) TextButton({ open(context, item); dismiss() }) { Text("Open") } },
    )
}

private fun uriFor(context: android.content.Context, item: DownloadItem) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", item.file)

private fun mimeOf(item: DownloadItem) =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.fileName.substringAfterLast('.', "").lowercase()) ?: item.mime ?: "*/*"

fun open(context: android.content.Context, item: DownloadItem) {
    val target = if (item.kind == Kind.TORRENT && File(item.dir, item.fileName).isDirectory) null else item
    if (target == null || !item.file.exists()) {
        Toast.makeText(context, "Saved in ${item.dir}", Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, item), mimeOf(item))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

private fun share(context: android.content.Context, item: DownloadItem) {
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mimeOf(item)).putExtra(Intent.EXTRA_STREAM, uriFor(context, item))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                null,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun copy(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
