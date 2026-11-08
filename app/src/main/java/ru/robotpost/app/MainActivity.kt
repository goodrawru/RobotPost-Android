package ru.robotpost.app

import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF0C1019)
private val Panel = Color(0xFF171D2A)
private val Accent = Color(0xFFB49BFF)
private val Muted = Color(0xFFA4AEC1)
private val Mint = Color(0xFF78E1BF)
private val Shape = RoundedCornerShape(20.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("robotpost", Context.MODE_PRIVATE)
        val requestNotifications = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* Downloads continue even if notifications are denied. */ }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        var pendingText = ""
        val saveText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(pendingText.toByteArray(Charsets.UTF_8)) }
                    android.widget.Toast.makeText(this, "Описание сохранено", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Ошибка сохранения", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) try {
                val payload = BackupStore.export(prefs)
                contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    ?: error("Не удалось открыть файл")
                android.widget.Toast.makeText(this, "Резервная копия сохранена", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Ошибка экспорта: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val buffer = CharArray(8192)
                    val out = StringBuilder()
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        out.append(buffer, 0, n)
                        require(out.length <= 2_000_000) { "Файл слишком большой" }
                    }
                    out.toString()
                } ?: error("Не удалось прочитать файл")
                val (channelsCount, linksCount) = BackupStore.import(prefs, text)
                android.widget.Toast.makeText(this, "Восстановлено: каналов $channelsCount, ссылок $linksCount", android.widget.Toast.LENGTH_LONG).show()
                recreate()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Ошибка восстановления: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        var chosenRoot by mutableStateOf(prefs.getString("archiveRoot", "").orEmpty())
        var chosenVideo by mutableStateOf("")
        var chosenAudio by mutableStateOf("")
        val chooseRoot = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                chosenRoot = uri.toString()
                prefs.edit().putString("archiveRoot", chosenRoot).apply()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Ошибка выбора папки: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val chooseVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                chosenVideo = uri.toString()
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        val chooseAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                chosenAudio = uri.toString()
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        window.statusBarColor = android.graphics.Color.rgb(12, 16, 25)
        window.navigationBarColor = android.graphics.Color.rgb(23, 29, 42)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        setContent {
            val colors = darkColorScheme(
                background = Ink, surface = Ink, surfaceContainer = Panel,
                primary = Accent, onPrimary = Ink, onSurface = Color(0xFFF4F5FA),
                onBackground = Color(0xFFF4F5FA), secondary = Mint
            )
            MaterialTheme(colorScheme = colors) {
                var page by remember { mutableIntStateOf(if (intent?.action == Intent.ACTION_SEND) 2 else prefs.getInt("lastPage", 0).coerceIn(0, 4)) }
                LaunchedEffect(page) { prefs.edit().putInt("lastPage", page).apply() }
                var savedLinks by remember { mutableStateOf(prefs.getString("savedLinks", "")!!.lines().filter { it.isNotBlank() }) }
                var pendingQueue by remember { mutableStateOf(prefs.getString("pendingQueue", "")!!.lines().filter { it.isNotBlank() }) }
                var queueRunning by remember { mutableStateOf(false) }
                var queueStopRequested by remember { mutableStateOf(false) }
                var queueSelected by remember { mutableStateOf(setOf<String>()) }
                var failedQueue by remember { mutableStateOf(prefs.getString("failedQueue", "")!!.lines().filter { it.isNotBlank() }) }
                var failureReasons by remember { mutableStateOf(
                    runCatching {
                        val json = JSONObject(prefs.getString("failureReasons", "{}") ?: "{}")
                        json.keys().asSequence().associateWith { key -> json.optString(key) }
                    }.getOrDefault(emptyMap<String, String>())
                ) }
                var queueStatus by remember { mutableStateOf(
                    if (prefs.getBoolean("queueWasRunning", false)) "Предыдущая загрузка прервана. Очередь сохранена для продолжения."
                    else ""
                ) }
                var playlistUrl by remember { mutableStateOf("") }
                var playlistBusy by remember { mutableStateOf(false) }
                var playlistStatus by remember { mutableStateOf("") }
                var playlistPreview by remember { mutableStateOf(listOf<Pair<String, String>>()) }
                var playlistSelected by remember { mutableStateOf(setOf<String>()) }
                var playlistPreviewTitle by remember { mutableStateOf("") }
                var showPlaylistPreview by remember { mutableStateOf(false) }
                var queueQuality by remember { mutableStateOf(prefs.getString("queueQuality", "720") ?: "720") }
                var linkInput by remember { mutableStateOf(if (intent?.action == Intent.ACTION_SEND) (intent.getStringExtra(Intent.EXTRA_TEXT) ?: "") else "") }
                var linkError by remember { mutableStateOf("") }
                var downloadBusy by remember { mutableStateOf(false) }
                var activeDownloadDirectory by remember { mutableStateOf<java.io.File?>(null) }
                var history by remember { mutableStateOf(DownloadHistory.list(this@MainActivity)) }
                LaunchedEffect(Unit) {
                    while (true) {
                        val running = prefs.getBoolean("queueWasRunning", false)
                        queueRunning = running
                        if (running || prefs.getString("queueServiceStatus", "").orEmpty().isNotBlank()) {
                            queueStatus = prefs.getString("queueServiceStatus", "").orEmpty()
                        }
                        pendingQueue = prefs.getString("pendingQueue", "").orEmpty().lines().filter { it.isNotBlank() }
                        failedQueue = prefs.getString("failedQueue", "").orEmpty().lines().filter { it.isNotBlank() }
                        failureReasons = runCatching {
                            val json = JSONObject(prefs.getString("failureReasons", "{}") ?: "{}")
                            json.keys().asSequence().associateWith { key -> json.optString(key) }
                        }.getOrDefault(emptyMap<String, String>())
                        history = DownloadHistory.list(this@MainActivity)
                        delay(1000)
                    }
                }

                var clearHistoryConfirm by remember { mutableStateOf(false) }
                var downloadStatus by remember { mutableStateOf("") }
                var inspectStatus by remember { mutableStateOf("") }
                var formatChoices by remember { mutableStateOf(listOf<Pair<String, String>>()) }
                var selectedFormat by remember { mutableStateOf("") }
                var showFormatPicker by remember { mutableStateOf(false) }
                var inspectedTitle by remember { mutableStateOf("") }
                var diagnostics by remember { mutableStateOf("") }
                var showDiagnostics by remember { mutableStateOf(false) }
                var inspecting by remember { mutableStateOf(false) }
                val downloadScope = rememberCoroutineScope()
                LaunchedEffect(linkInput) {
                    formatChoices = emptyList()
                    selectedFormat = ""
                    inspectedTitle = ""
                    diagnostics = ""
                    inspectStatus = ""
                    if (!linkInput.trim().startsWith("https://")) return@LaunchedEffect
                    delay(700)
                    inspecting = true
                    inspectStatus = "Проверка..."
                    try {
                        val info = withContext(Dispatchers.IO) { DownloadEngine.inspect(this@MainActivity, linkInput.trim()) }
                        inspectedTitle = info.optString("title")
                        diagnostics = buildString {
                            append("yt-dlp ").append(info.optString("yt_dlp_version")).append("\n")
                            val reports = info.optJSONArray("reports")
                            if (reports != null) for (i in 0 until reports.length()) {
                                val report = reports.getJSONObject(i)
                                append("Профиль: ").append(report.optString("profile"))
                                append(" · потоков: ").append(report.optInt("formats"))
                                append(" · максимум: ").append(report.optInt("max_video")).append("p\n")
                                if (report.has("error")) append("Ошибка: ").append(report.optString("error")).append("\n")
                                val warnings = report.optJSONArray("warnings")
                                if (warnings != null) for (j in 0 until warnings.length()) {
                                    append("Предупреждение: ").append(warnings.optString(j)).append("\n")
                                }
                            }
                            append("\n")
                            val data = info.optJSONArray("diagnostics")
                            if (data != null) for (i in 0 until data.length()) {
                                val item = data.getJSONObject(i)
                                append(item.optString("id")).append(" · ")
                                append(item.optInt("height")).append("p · ")
                                append(item.optString("ext")).append(" · ")
                                append(item.optString("video")).append(" / ")
                                append(item.optString("audio"))
                                if (!item.optBoolean("url")) append(" · без URL")
                                append("\n")
                            }
                        }
                        val formats = info.optJSONArray("formats")
                        formatChoices = if (formats == null) emptyList() else (0 until formats.length()).map { i ->
                            val fmt = formats.getJSONObject(i)
                            fmt.getString("id") to fmt.getString("label")
                        }
                        selectedFormat = formatChoices.firstOrNull()?.first.orEmpty()
                        showFormatPicker = false
                        inspectStatus = if (formatChoices.isEmpty()) {
                            "MP4 со звуком не найден. Для раздельных потоков потребуется объединение видео и аудио."
                        } else "Найдено форматов: " + formatChoices.size
                    } catch (e: Exception) {
                        inspectStatus = "Ошибка поиска: " + (e.localizedMessage ?: "Нет доступа к видео")
                    } finally { inspecting = false }
                }

                var linkSearch by remember { mutableStateOf("") }
                var newestFirst by remember { mutableStateOf(true) }
                var confirmClear by remember { mutableStateOf(false) }
                var channels by remember { mutableStateOf(prefs.getString("channels", "")!!.split("\n").filter { it.isNotBlank() }) }
                var quality by remember { mutableStateOf(prefs.getString("quality", "Лучшее доступное") ?: "Лучшее доступное") }
                var autoCheck by remember { mutableStateOf(prefs.getBoolean("autoCheck", false)) }
                var description by remember { mutableStateOf(prefs.getString("description", "") ?: "") }
                val tabs = listOf("Главная", "Каналы", "Загрузки", "Архив", "Настройки")
                val glyphs = listOf("⌂", "▶", "↓", "▤", "⚙")
                Scaffold(
                    containerColor = Ink,
                    bottomBar = {
                        NavigationBar(containerColor = Panel) {
                            tabs.forEachIndexed { i, name ->
                                NavigationBarItem(
                                    selected = page == i,
                                    onClick = { page = i },
                                    icon = { Text(glyphs[i], style = MaterialTheme.typography.titleLarge) },
                                    label = { Text(name, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Accent, selectedTextColor = Accent,
                                        indicatorColor = Color(0xFF343049),
                                        unselectedIconColor = Muted, unselectedTextColor = Muted
                                    )
                                )
                            }
                        }
                    }
                ) { insets ->
                    Column(Modifier.fillMaxSize().padding(insets)) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("ROBOTPOST  •  ANDROID", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(tabs[page], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                            Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                                Icon(painter = painterResource(R.drawable.robotpost_icon), contentDescription = "RobotPost", modifier = Modifier.size(48.dp), tint = Color.Unspecified)
                            }
                        }
                        when(page) {
                            0 -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                item {
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Твоя медиатека. На телефоне.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                            Text("Сохраняй каналы, подготавливай описания и управляй локальным архивом.", color = Muted)
                                            Text("● Локальная медиатека доступна", color = Mint, style = MaterialTheme.typography.bodySmall)
                                            Button(onClick = { page = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Добавить ссылку на видео") }
                                        }
                                    }
                                }
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Metric("Каналы", channels.size.toString(), Modifier.weight(1f))
                                        Metric("Ссылки", savedLinks.size.toString(), Modifier.weight(1f))
                                    }
                                }
                                item { SectionTitle("Быстрые действия") }
                                item { ActionTile("▣", "Мои каналы", "Добавить или удалить источник") { page = 1 } }
                                item { ActionTile("✎", "Очистка описания", "Удалить рекламные строки из текста") { page = 3 } }
                                item { ActionTile("⚙", "Параметры", "Качество и будущие фоновые проверки") { page = 4 } }
                            }
                            1 -> {
                                var input by remember { mutableStateOf("") }
                                var error by remember { mutableStateOf("") }
                                var channelSearch by remember { mutableStateOf("") }
                                var channelToDelete by remember { mutableStateOf<String?>(null) }
                                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Источники видео", color = Muted)
                                    OutlinedTextField(
                                        value = input, onValueChange = { input = it; error = "" },
                                        label = { Text("Ссылка на YouTube-канал") },
                                        placeholder = { Text("https://youtube.com/@channel") },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true
                                    )
                                    if(error.isNotEmpty()) Text(error, color = Color(0xFFFF9999))
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            val v = input.trim()
                                            if(!v.startsWith("https://youtube.com/") && !v.startsWith("https://www.youtube.com/") && !v.startsWith("https://m.youtube.com/")) {
                                                error = "Укажи ссылку на канал youtube.com"
                                            } else if(channels.contains(v)) {
                                                error = "Этот канал уже добавлен"
                                            } else {
                                                channels = channels + v
                                                prefs.edit().putString("channels", channels.joinToString("\n")).apply()
                                                input = ""
                                            }
                                        }
                                    ) { Text("Добавить канал") }
                                    Text("Сохранено: ${channels.size}", color = Muted)
                                    OutlinedTextField(
                                        value = channelSearch,
                                        onValueChange = { channelSearch = it },
                                        label = { Text("Поиск канала") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (channelToDelete != null) {
                                        AlertDialog(
                                            onDismissRequest = { channelToDelete = null },
                                            title = { Text("Удалить канал?") },
                                            text = { Text("Канал исчезнет из списка RobotPost. Файлы не затрагиваются.") },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    channels = channels - channelToDelete!!
                                                    prefs.edit().putString("channels", channels.joinToString("\n")).apply()
                                                    channelToDelete = null
                                                }) { Text("Удалить") }
                                            },
                                            dismissButton = { TextButton(onClick = { channelToDelete = null }) { Text("Отмена") } }
                                        )
                                    }
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(channels.filter { it.contains(channelSearch.trim(), ignoreCase = true) }) { channel ->
                                            var expanded by remember(channel) { mutableStateOf(false) }
                                            var loadingVideos by remember(channel) { mutableStateOf(false) }
                                            var videos by remember(channel) { mutableStateOf(listOf<Pair<String, String>>()) }
                                            var videoError by remember(channel) { mutableStateOf("") }
                                            val channelScope = rememberCoroutineScope()
                                            Surface(color = Panel, shape = Shape) {
                                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Text(channel.substringAfterLast("/").ifBlank { "YouTube-канал" }, fontWeight = FontWeight.SemiBold)
                                                    Text(channel, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                                        OutlinedButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(channel))) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Открыть", style = MaterialTheme.typography.labelSmall) }
                                                        OutlinedButton(onClick = {
                                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("YouTube channel", channel))
                                                        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Копия", style = MaterialTheme.typography.labelSmall) }
                                                        OutlinedButton(onClick = {
                                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                                type = "text/plain"
                                                                putExtra(Intent.EXTRA_TEXT, channel)
                                                            }
                                                            startActivity(Intent.createChooser(share, "Поделиться каналом"))
                                                        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Поделиться", style = MaterialTheme.typography.labelSmall) }
                                                        OutlinedButton(onClick = { channelToDelete = channel }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Удалить", style = MaterialTheme.typography.labelSmall) }
                                                    }
                                                    OutlinedButton(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        onClick = {
                                                            expanded = !expanded
                                                            if (expanded && videos.isEmpty() && !loadingVideos) {
                                                                loadingVideos = true
                                                                videoError = ""
                                                                channelScope.launch {
                                                                    try {
                                                                        videos = withContext(Dispatchers.IO) {
                                                                            DownloadEngine.latest(this@MainActivity, channel)
                                                                        }
                                                                        if (videos.isEmpty()) videoError = "Новые видео не найдены"
                                                                    } catch (e: Exception) {
                                                                        videoError = e.localizedMessage ?: "Не удалось загрузить список"
                                                                    } finally { loadingVideos = false }
                                                                }
                                                            }
                                                        }
                                                    ) { Text(if (expanded) "Скрыть последние видео" else "Последние 5 видео ▾") }
                                                    if (expanded) {
                                                        if (loadingVideos) {
                                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                                            Text("Получаю последние видео...", color = Muted)
                                                        }
                                                        if (videoError.isNotBlank()) Text(videoError, color = Color(0xFFFF9999), style = MaterialTheme.typography.bodySmall)
                                                        videos.forEach { video ->
                                                            HorizontalDivider(color = Muted.copy(alpha = 0.2f))
                                                            Text(video.first, fontWeight = FontWeight.Medium)
                                                            Button(onClick = {
                                                                linkInput = video.second
                                                                page = 2
                                                            }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать для скачивания") }
                                                        }
                                                        if (!loadingVideos) TextButton(onClick = {
                                                            loadingVideos = true
                                                            videoError = ""
                                                            channelScope.launch {
                                                                try {
                                                                    videos = withContext(Dispatchers.IO) {
                                                                        DownloadEngine.latest(this@MainActivity, channel)
                                                                    }
                                                                } catch (e: Exception) {
                                                                    videoError = e.localizedMessage ?: "Не удалось обновить"
                                                                } finally { loadingVideos = false }
                                                            }
                                                        }) { Text("Обновить список") }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            2 -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item { Text("Список видео", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                                item { Text("Вставь ссылку: приложение проверит доступные форматы MP4 со звуком.", color = Muted) }
                                item { OutlinedTextField(value = linkInput, onValueChange = { linkInput = it; linkError = "" }, label = { Text("Ссылка YouTube") }, modifier = Modifier.fillMaxWidth()) }
                                item {
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("Доступные форматы", fontWeight = FontWeight.Bold)
                                            if (inspecting) {
                                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                                Text("Ищу форматы YouTube...", color = Muted)
                                            } else if (inspectStatus.isNotBlank()) Text(inspectStatus, color = Muted)
                                            if (inspectedTitle.isNotBlank()) Text(inspectedTitle, fontWeight = FontWeight.SemiBold)
                                            if (diagnostics.isNotBlank()) {
                                                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                                                    Text(if (showDiagnostics) "Скрыть диагностику форматов" else "Показать диагностику форматов")
                                                }
                                                if (showDiagnostics) {
                                                    Text(diagnostics, color = Muted, style = MaterialTheme.typography.bodySmall)
                                                    OutlinedButton(onClick = {
                                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RobotPost formats", diagnostics))
                                                    }) { Text("Копировать диагностику") }
                                                }
                                            }
                                            if (formatChoices.isNotEmpty()) {
                                                OutlinedButton(
                                                    onClick = { showFormatPicker = true },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("Качество: " + (formatChoices.firstOrNull { it.first == selectedFormat }?.second ?: "Выбрать") + " ▾")
                                                }
                                                if (showFormatPicker) {
                                                    AlertDialog(
                                                        onDismissRequest = { showFormatPicker = false },
                                                        title = { Text("Выбери формат (до 720p)") },
                                                        text = {
                                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                formatChoices.forEach { choice ->
                                                                    Row(
                                                                        verticalAlignment = Alignment.CenterVertically,
                                                                        modifier = Modifier.fillMaxWidth().clickable {
                                                                            selectedFormat = choice.first
                                                                            showFormatPicker = false
                                                                        }
                                                                    ) {
                                                                        RadioButton(
                                                                            selected = selectedFormat == choice.first,
                                                                            onClick = {
                                                                                selectedFormat = choice.first
                                                                                showFormatPicker = false
                                                                            }
                                                                        )
                                                                        Text(choice.second, modifier = Modifier.weight(1f))
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        confirmButton = {
                                                            TextButton(onClick = { showFormatPicker = false }) { Text("Закрыть") }
                                                        }
                                                    )
                                                }
                                            }
                                            OutlinedButton(onClick = { chooseRoot.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                                                Text(if (chosenRoot.isBlank()) "Выбрать папку архива" else "Изменить папку архива")
                                            }
                                            Button(
                                                enabled = !downloadBusy && chosenRoot.isNotBlank() && selectedFormat.isNotBlank() && !inspecting,
                                                onClick = {
                                                    downloadBusy = true
                                                    downloadStatus = "Подготовка скачивания... Оставь приложение открытым."
                                                    val url = linkInput.trim()
                                                    val format = selectedFormat
                                                    downloadScope.launch {
                                                        downloadStatus = try {
                                                            val result = withContext(Dispatchers.IO) {
                                                                DownloadEngine.download(this@MainActivity, url, Uri.parse(chosenRoot), format,
                                                                    onTemp = { activeDownloadDirectory = it },
                                                                    onProgress = { progress -> runOnUiThread { downloadStatus = progress } }
                                                                )
                                                            }
                                                            DownloadHistory.add(this@MainActivity, result, url)
                                                            history = DownloadHistory.list(this@MainActivity)
                                                            "Сохранено: " + result
                                                        } catch (e: Exception) {
                                                            "Ошибка: " + DownloadEngine.readableError(e)
                                                        }
                                                        downloadBusy = false
                                                        activeDownloadDirectory = null
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text(if (downloadBusy) "Скачивается..." else "Скачать выбранный формат") }
                                            if (downloadBusy) {
                                                OutlinedButton(onClick = {
                                                    activeDownloadDirectory?.let { directory ->
                                                        java.io.File(directory, "cancel.flag").writeText("cancel")
                                                        downloadStatus = "Отмена загрузки..."
                                                    }
                                                }, enabled = activeDownloadDirectory != null, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Отменить загрузку")
                                                }
                                            }
                                            if (downloadStatus.isNotBlank()) Text(downloadStatus, color = if (downloadStatus.startsWith("Ошибка")) Color(0xFFFF9999) else Mint)
                                        }
                                    }
                                }

                                if (linkError.isNotEmpty()) item { Text(linkError, color = Color(0xFFFF9999)) }
                                item { OutlinedTextField(value = linkSearch, onValueChange = { linkSearch = it }, label = { Text("Поиск по ссылкам") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                                item {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { newestFirst = !newestFirst }) { Text(if (newestFirst) "Сначала новые" else "Сначала старые") }
                                        TextButton(onClick = { confirmClear = true }, enabled = savedLinks.isNotEmpty()) { Text("Очистить") }
                                    }
                                }
                                if (confirmClear) item {
                                    AlertDialog(
                                        onDismissRequest = { confirmClear = false },
                                        title = { Text("Очистить список?") },
                                        text = { Text("Удаляются только сохранённые ссылки, не файлы.") },
                                        confirmButton = { TextButton(onClick = {
                                            savedLinks = emptyList()
                                            prefs.edit().remove("savedLinks").apply()
                                            confirmClear = false
                                        }) { Text("Удалить") } },
                                        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
                                    )
                                }
                                item { Button(onClick = {
                                    val link = linkInput.trim().split(" ").firstOrNull { it.contains("youtu.be/") || it.contains("youtube.com/") }?.trim()
                                    if (link == null || !link.startsWith("https://")) linkError = "Укажи HTTPS-ссылку YouTube"
                                    else if (link in savedLinks) linkError = "Ссылка уже добавлена"
                                    else {
                                        savedLinks = listOf(link) + savedLinks
                                        prefs.edit().putString("savedLinks", savedLinks.joinToString("\n")).apply()
                                        linkInput = ""
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Добавить в список") } }
                                item {
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Импорт плейлиста YouTube", fontWeight = FontWeight.Bold)
                                            Text("Добавь до 100 видео одним действием. Качество и папка архива задаются один раз для всей очереди.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                            OutlinedTextField(
                                                value = playlistUrl, onValueChange = { playlistUrl = it },
                                                label = { Text("Ссылка на плейлист") },
                                                singleLine = true, modifier = Modifier.fillMaxWidth()
                                            )
                                            Button(
                                                enabled = !playlistBusy && !queueRunning && playlistUrl.startsWith("https://"),
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = {
                                                    playlistBusy = true
                                                    playlistStatus = "Получаю список роликов..."
                                                    downloadScope.launch {
                                                        try {
                                                            val result = withContext(Dispatchers.IO) {
                                                                DownloadEngine.playlist(this@MainActivity, playlistUrl.trim())
                                                            }
                                                            val entries = result.getJSONArray("entries")
                                                            playlistPreview = (0 until entries.length()).map { index ->
                                                                val entry = entries.getJSONObject(index)
                                                                entry.optString("title", "Видео") to entry.getString("url")
                                                            }
                                                            playlistSelected = playlistPreview.map { it.second }.toSet()
                                                            playlistPreviewTitle = result.optString("title", "Плейлист")
                                                            showPlaylistPreview = true
                                                            playlistStatus = "Найдено: ${playlistPreview.size}" +
                                                                if (result.optBoolean("limited")) " (первые 100)" else ""
                                                        } catch (e: Exception) {
                                                            playlistStatus = "Ошибка плейлиста: " + (e.localizedMessage ?: "Не удалось прочитать")
                                                        } finally {
                                                            playlistBusy = false
                                                        }
                                                    }
                                                }
                                            ) { Text(if (playlistBusy) "Читаю плейлист..." else "Посмотреть видео плейлиста") }
                                            if (showPlaylistPreview) {
                                                AlertDialog(
                                                    onDismissRequest = { showPlaylistPreview = false },
                                                    title = { Text(playlistPreviewTitle) },
                                                    text = {
                                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            Text("Выбрано: ${playlistSelected.size} из ${playlistPreview.size}")
                                                            Row {
                                                                TextButton(onClick = { playlistSelected = playlistPreview.map { it.second }.toSet() }) { Text("Все") }
                                                                TextButton(onClick = { playlistSelected = emptySet() }) { Text("Снять выбор") }
                                                            }
                                                            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                                                                items(playlistPreview) { entry ->
                                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                                        Checkbox(
                                                                            checked = entry.second in playlistSelected,
                                                                            onCheckedChange = { checked ->
                                                                                playlistSelected = if (checked) playlistSelected + entry.second
                                                                                    else playlistSelected - entry.second
                                                                            }
                                                                        )
                                                                        Text(entry.first, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    },
                                                    confirmButton = {
                                                        TextButton(onClick = {
                                                            val existing = pendingQueue.toMutableSet()
                                                            val before = existing.size
                                                            playlistPreview.forEach { entry ->
                                                                if (entry.second in playlistSelected) existing.add(entry.second)
                                                            }
                                                            pendingQueue = existing.toList()
                                                            prefs.edit().putString("pendingQueue", pendingQueue.joinToString("\n")).apply()
                                                            playlistStatus = "Добавлено в очередь: ${existing.size - before}"
                                                            showPlaylistPreview = false
                                                        }, enabled = playlistSelected.isNotEmpty()) { Text("Добавить выбранные") }
                                                    },
                                                    dismissButton = { TextButton(onClick = { showPlaylistPreview = false }) { Text("Отмена") } }
                                                )
                                            }
                                            if (playlistStatus.isNotBlank()) Text(playlistStatus)
                                        }
                                    }
                                }
                                item {
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Очередь подготовки (${pendingQueue.size})", fontWeight = FontWeight.Bold)
                                            Text("Единое качество для всех роликов очереди (максимум 720p). При ошибке ролик пропускается и сохраняется для повторной попытки.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                listOf("720", "480", "360", "audio").forEach { quality ->
                                                    OutlinedButton(
                                                        onClick = {
                                                            queueQuality = quality
                                                            prefs.edit().putString("queueQuality", quality).apply()
                                                        },
                                                        enabled = !queueRunning && queueQuality != quality,
                                                        modifier = Modifier.weight(1f),
                                                        contentPadding = PaddingValues(horizontal = 2.dp)
                                                    ) { Text(if (quality == "audio") "M4A" else quality + "p") }
                                                }
                                            }
                                            Button(
                                                enabled = pendingQueue.isNotEmpty() && chosenRoot.isNotBlank() && !downloadBusy && !queueRunning,
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = {
                                                    try {
                                                        androidx.core.content.ContextCompat.startForegroundService(
                                                            this@MainActivity,
                                                            Intent(this@MainActivity, DownloadForegroundService::class.java)
                                                                .setAction(DownloadForegroundService.ACTION_START)
                                                        )
                                                        queueRunning = true
                                                        queueStatus = "Очередь запущена в фоновом режиме"
                                                    } catch (e: Exception) {
                                                        queueStatus = "Не удалось запустить службу: " + (e.localizedMessage ?: "ошибка")
                                                    }
                                                }
                                            ) { Text(if (queueRunning) "Очередь выполняется..." else "Скачать всю очередь") }
                                            if (queueStatus.isNotBlank()) Text(queueStatus, color = if (queueStatus.startsWith("Очередь остановлена")) Color(0xFFFF9999) else Mint)
                                            if (queueRunning) OutlinedButton(
                                                onClick = {
                                                    startService(Intent(this@MainActivity, DownloadForegroundService::class.java)
                                                        .setAction(DownloadForegroundService.ACTION_STOP))
                                                    queueStatus = "Останавливаю очередь..."
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("Остановить очередь") }
                                            if (failedQueue.isNotEmpty()) {
                                                Text("Неудачные загрузки (${failedQueue.size})", fontWeight = FontWeight.Bold)
                                                OutlinedButton(
                                                    enabled = !queueRunning && !downloadBusy,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    onClick = {
                                                        pendingQueue = (pendingQueue + failedQueue).distinct()
                                                        failedQueue = emptyList()
                                                        failureReasons = emptyMap()
                                                        prefs.edit().putString("failureReasons", "{}").apply()
                                                        prefs.edit().putString("pendingQueue", pendingQueue.joinToString("\n"))
                                                            .putString("failedQueue", "").apply()
                                                    }
                                                ) { Text("Повторить неудачные") }
                                                failedQueue.take(10).forEach { failed ->
                                                    Text(failed.substringBefore("?"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(failureReasons[failed] ?: "Ошибка загрузки", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                                }
                                            }
                                            if (pendingQueue.isNotEmpty()) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Выбрано: ${queueSelected.count { it in pendingQueue }}", modifier = Modifier.weight(1f))
                                                    TextButton(onClick = { queueSelected = pendingQueue.toSet() }, enabled = !queueRunning) { Text("Все") }
                                                    TextButton(onClick = { queueSelected = emptySet() }, enabled = !queueRunning) { Text("Сброс") }
                                                }
                                                OutlinedButton(
                                                    enabled = !queueRunning && !downloadBusy && queueSelected.any { it in pendingQueue },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    onClick = {
                                                        pendingQueue = pendingQueue.filterNot { it in queueSelected }
                                                        queueSelected = emptySet()
                                                        prefs.edit().putString("pendingQueue", pendingQueue.joinToString("\n")).apply()
                                                    }
                                                ) { Text("Удалить выбранные из очереди") }
                                            }
                                            pendingQueue.forEachIndexed { index, queued ->
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Checkbox(
                                                        checked = queued in queueSelected,
                                                        onCheckedChange = { checked ->
                                                            queueSelected = if (checked) queueSelected + queued else queueSelected - queued
                                                        },
                                                        enabled = !queueRunning && !downloadBusy
                                                    )
                                                    Text("${index + 1}. " + queued.substringBefore("?"),
                                                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    TextButton(
                                                        onClick = {
                                                            pendingQueue = pendingQueue.toMutableList().apply {
                                                                add(index - 1, removeAt(index))
                                                            }
                                                            prefs.edit().putString("pendingQueue", pendingQueue.joinToString("\n")).apply()
                                                        },
                                                        enabled = index > 0 && !queueRunning && !downloadBusy,
                                                        contentPadding = PaddingValues(horizontal = 2.dp)
                                                    ) { Text("↑") }
                                                    TextButton(
                                                        onClick = {
                                                            pendingQueue = pendingQueue.toMutableList().apply {
                                                                add(index + 1, removeAt(index))
                                                            }
                                                            prefs.edit().putString("pendingQueue", pendingQueue.joinToString("\n")).apply()
                                                        },
                                                        enabled = index < pendingQueue.lastIndex && !queueRunning && !downloadBusy,
                                                        contentPadding = PaddingValues(horizontal = 2.dp)
                                                    ) { Text("↓") }
                                                    TextButton(onClick = {
                                                        linkInput = queued
                                                        pendingQueue = pendingQueue - queued
                                                        queueSelected = queueSelected - queued
                                                        prefs.edit().putString("pendingQueue", pendingQueue.joinToString("\n")).apply()
                                                    }, enabled = !downloadBusy) { Text("Выбрать") }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (savedLinks.isEmpty()) item { EmptyPanel("↓", "Пока пусто", "Поделись ссылкой из YouTube или вставь её здесь.") }
                                items((if (newestFirst) savedLinks else savedLinks.reversed()).filter { it.contains(linkSearch.trim(), ignoreCase = true) }) { link ->
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(link, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            OutlinedButton(onClick = {
                                                if (link !in pendingQueue) {
                                                    pendingQueue = pendingQueue + link
                                                    prefs.edit().putString("pendingQueue", pendingQueue.joinToString("\n")).apply()
                                                }
                                            }, enabled = link !in pendingQueue, modifier = Modifier.fillMaxWidth()) {
                                                Text(if (link in pendingQueue) "В очереди" else "Добавить в очередь")
                                            }
                                            Row {
                                                TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }) { Text("Открыть") }
                                                TextButton(onClick = {
                                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RobotPost link", link))
                                                }) { Text("Копировать") }
                                                TextButton(onClick = {
                                                    savedLinks = savedLinks - link
                                                    prefs.edit().putString("savedLinks", savedLinks.joinToString("\n")).apply()
                                                }) { Text("Удалить") }
                                            }
                                        }
                                    }
                                }
                            }
                            3 -> {
                                var packageChannel by remember { mutableStateOf("") }
                                var packageTitle by remember { mutableStateOf("") }
                                var packageDate by remember { mutableStateOf(SimpleDateFormat("dd.MM.yy", Locale.ROOT).format(Date())) }
                                var packageDescription by remember { mutableStateOf(description) }
                                var busy by remember { mutableStateOf(false) }
                                var result by remember { mutableStateOf("") }
                                val scope = rememberCoroutineScope()
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    item { SectionTitle("Архив материалов") }
                                    item { Text("Комплект сохраняется в RobotPost / Имя канала / ДД.ММ.ГГ Название видео.", color = Muted) }
                                    item {
                                        Surface(color = Panel, shape = Shape) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text("Папка для сохранения", fontWeight = FontWeight.Bold)
                                                Text(if (chosenRoot.isBlank()) "Папка не выбрана" else "Папка выбрана. Доступ сохранён.", color = Muted)
                                                OutlinedButton(onClick = { chooseRoot.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать папку") }
                                            }
                                        }
                                    }
                                    item { OutlinedTextField(value = packageChannel, onValueChange = { packageChannel = it }, label = { Text("Имя канала") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                                    item { OutlinedTextField(value = packageTitle, onValueChange = { packageTitle = it }, label = { Text("Название видео") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                                    item { OutlinedTextField(value = packageDate, onValueChange = { packageDate = it }, label = { Text("Дата публикации ДД.ММ.ГГ") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                                    item {
                                        Surface(color = Panel, shape = Shape) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text("Медиафайлы", fontWeight = FontWeight.Bold)
                                                Text(if (chosenVideo.isBlank()) "Видео не выбрано" else "Видео выбрано", color = Muted)
                                                OutlinedButton(onClick = { chooseVideo.launch(arrayOf("video/mp4")) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать MP4") }
                                                Text(if (chosenAudio.isBlank()) "Аудио не выбрано" else "Аудио выбрано", color = Muted)
                                                OutlinedButton(onClick = { chooseAudio.launch(arrayOf("audio/mpeg", "audio/mp3")) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать MP3") }
                                            }
                                        }
                                    }
                                    item {
                                        OutlinedTextField(value = packageDescription, onValueChange = { packageDescription = it },
                                            label = { Text("Описание видео") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
                                    }
                                    item {
                                        Button(
                                            enabled = !busy && chosenRoot.isNotBlank() && packageChannel.isNotBlank() &&
                                                packageTitle.isNotBlank() && ArchiveExporter.validDate(packageDate) &&
                                                (chosenVideo.isNotBlank() || chosenAudio.isNotBlank()),
                                            onClick = {
                                                busy = true
                                                result = "Сохранение..."
                                                scope.launch {
                                                    result = try {
                                                        val saved = withContext(Dispatchers.IO) {
                                                            ArchiveExporter.export(
                                                                this@MainActivity, Uri.parse(chosenRoot),
                                                                packageChannel, packageTitle, packageDate,
                                                                cleanDescription(packageDescription),
                                                                chosenVideo.takeIf { it.isNotBlank() }?.let(Uri::parse),
                                                                chosenAudio.takeIf { it.isNotBlank() }?.let(Uri::parse)
                                                            )
                                                        }
                                                        DownloadHistory.add(this@MainActivity, saved, "Ручное добавление")
                                                        history = DownloadHistory.list(this@MainActivity)
                                                        "Сохранено: $saved"
                                                    } catch (e: Exception) { "Ошибка: ${e.localizedMessage}" }
                                                    busy = false
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(if (busy) "Сохраняем..." else "Сохранить комплект") }
                                    }
                                    if (result.isNotBlank()) item { Text(result, color = if (result.startsWith("Ошибка")) Color(0xFFFF9999) else Mint) }
                                    item { Text("Здесь можно также вручную собрать комплект из файлов телефона. Автоматическое скачивание находится на вкладке «Загрузки».", color = Muted) }
                                    item {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            Text("История сохранений (${history.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                            TextButton(enabled = history.isNotEmpty(), onClick = { clearHistoryConfirm = true }) { Text("Очистить") }
                                        }
                                    }
                                    if (history.isEmpty()) item { Text("Сохранений пока нет. Новые комплекты появятся здесь.", color = Muted) }
                                    items(history) { entry ->
                                        Surface(color = Panel, shape = Shape) {
                                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(entry.optString("name", "Комплект"), fontWeight = FontWeight.SemiBold)
                                                Text(entry.optString("source", ""), color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(java.text.SimpleDateFormat("dd.MM.yy HH:mm", Locale.ROOT).format(Date(entry.optLong("savedAt"))), color = Mint, style = MaterialTheme.typography.bodySmall)
                                                if (entry.optString("source").startsWith("https://")) {
                                                    TextButton(onClick = {
                                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.optString("source"))))
                                                    }) { Text("Открыть оригинал") }
                                                }
                                            }
                                        }
                                    }
                                    if (clearHistoryConfirm) item {
                                        AlertDialog(
                                            onDismissRequest = { clearHistoryConfirm = false },
                                            title = { Text("Очистить историю?") },
                                            text = { Text("Удалятся только записи в приложении. Сохранённые MP4, M4A и TXT останутся на телефоне.") },
                                            confirmButton = { TextButton(onClick = {
                                                DownloadHistory.clear(this@MainActivity)
                                                history = emptyList()
                                                clearHistoryConfirm = false
                                            }) { Text("Очистить") } },
                                            dismissButton = { TextButton(onClick = { clearHistoryConfirm = false }) { Text("Отмена") } }
                                        )
                                    }
                                }
                            }
                            4 -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                item { SectionTitle("Параметры загрузки") }
                                item {
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("Предпочитаемое качество", fontWeight = FontWeight.SemiBold)
                                            listOf("Лучшее доступное", "1080p", "720p", "480p").forEach { option ->
                                                Row(
                                                    Modifier.fillMaxWidth().clickable {
                                                        quality = option
                                                        prefs.edit().putString("quality", option).apply()
                                                    }, verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    RadioButton(selected = quality == option, onClick = {
                                                        quality = option
                                                        prefs.edit().putString("quality", option).apply()
                                                    })
                                                    Text(option)
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.padding(16.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("Автопроверка каналов", fontWeight = FontWeight.SemiBold)
                                                    Text("Будет доступна после подключения загрузчика", color = Muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                Switch(checked = false, onCheckedChange = null)
                                            }
                                        }
                                    }
                                }
                                item { SectionTitle("Резервное копирование") }
                                item {
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Сохраняются каналы, ссылки, описание и качество. Скачанные файлы в архив не входят.", color = Muted)
                                            Button(onClick = { exportBackup.launch("robotpost-backup.json") }, modifier = Modifier.fillMaxWidth()) {
                                                Text("Создать резервную копию")
                                            }
                                            OutlinedButton(onClick = { importBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                                Text("Восстановить из файла")
                                            }
                                            Text("Восстановление заменит текущие списки и настройки.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                item { Text("VPN управляется отдельно. RobotPost не проверяет VPN.", color = Muted) }
                                item { Text("RobotPost Android · UI alpha 0.2", color = Muted, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Panel, shape = Shape) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Accent)
            Text(label, color = Muted)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun ActionTile(icon: String, title: String, subtitle: String, action: () -> Unit) {
    Surface(color = Panel, shape = Shape, modifier = Modifier.fillMaxWidth().clickable(onClick = action)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(icon, color = Accent, style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = Muted, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun EmptyPanel(symbol: String, title: String, subtitle: String) {
    Surface(color = Panel, shape = Shape, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(symbol, color = Accent, style = MaterialTheme.typography.displayMedium)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted)
        }
    }
}

private fun cleanDescription(text: String): String {
    val patterns = listOf(
        Regex("(?i)(реклама|спонсор|промокод|скидка по ссылке|партн[её]рская ссылка|advertisement|sponsored|promo code|affiliate link)"),
        Regex("(?i)(подписывайтесь на канал|ставьте лайк|нажмите на колокольчик|subscribe to my channel|hit the bell)")
    )
    return text.lines().filterNot { line -> patterns.any { it.containsMatchIn(line) } }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
