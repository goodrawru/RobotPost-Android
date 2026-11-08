package ru.robotpost.app

import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
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
                var linkInput by remember { mutableStateOf(if (intent?.action == Intent.ACTION_SEND) (intent.getStringExtra(Intent.EXTRA_TEXT) ?: "") else "") }
                var linkError by remember { mutableStateOf("") }
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
                                            Surface(color = Panel, shape = Shape) {
                                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(channel.substringAfterLast("/").ifBlank { "YouTube-канал" }, fontWeight = FontWeight.SemiBold)
                                                        Text(channel, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                    Column {
                                                        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(channel))) }) { Text("Открыть") }
                                                        TextButton(onClick = {
                                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("YouTube channel", channel))
                                                        }) { Text("Копировать") }
                                                        TextButton(onClick = {
                                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                                type = "text/plain"
                                                                putExtra(Intent.EXTRA_TEXT, channel)
                                                            }
                                                            startActivity(Intent.createChooser(share, "Поделиться каналом"))
                                                        }) { Text("Поделиться") }
                                                        TextButton(onClick = { channelToDelete = channel }) { Text("Удалить") }
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
                                item { Text("Ссылки для последующей загрузки. Видео пока не скачиваются.", color = Muted) }
                                item { OutlinedTextField(value = linkInput, onValueChange = { linkInput = it; linkError = "" }, label = { Text("Ссылка YouTube") }, modifier = Modifier.fillMaxWidth()) }
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
                                if (savedLinks.isEmpty()) item { EmptyPanel("↓", "Пока пусто", "Поделись ссылкой из YouTube или вставь её здесь.") }
                                items((if (newestFirst) savedLinks else savedLinks.reversed()).filter { it.contains(linkSearch.trim(), ignoreCase = true) }) { link ->
                                    Surface(color = Panel, shape = Shape) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(link, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                                                        "Сохранено: $saved"
                                                    } catch (e: Exception) { "Ошибка: ${e.localizedMessage}" }
                                                    busy = false
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(if (busy) "Сохраняем..." else "Сохранить комплект") }
                                    }
                                    if (result.isNotBlank()) item { Text(result, color = if (result.startsWith("Ошибка")) Color(0xFFFF9999) else Mint) }
                                    item { Text("Для текущей версии файлы выбираются вручную. Автоматическая загрузка с YouTube ещё не подключена.", color = Muted) }
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
