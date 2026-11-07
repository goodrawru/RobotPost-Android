package ru.robotpost.app

import android.content.Context
import android.os.Bundle
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
        setContent {
            val colors = darkColorScheme(
                background = Ink, surface = Ink, surfaceContainer = Panel,
                primary = Accent, onPrimary = Ink, onSurface = Color(0xFFF4F5FA),
                onBackground = Color(0xFFF4F5FA), secondary = Mint
            )
            MaterialTheme(colorScheme = colors) {
                var page by remember { mutableIntStateOf(0) }
                var channels by remember { mutableStateOf(prefs.getString("channels", "")!!.split("\n").filter { it.isNotBlank() }) }
                var quality by remember { mutableStateOf(prefs.getString("quality", "Лучшее доступное") ?: "Лучшее доступное") }
                var autoCheck by remember { mutableStateOf(prefs.getBoolean("autoCheck", false)) }
                var description by remember { mutableStateOf(prefs.getString("description", "") ?: "") }
                val tabs = listOf("Главная", "Каналы", "Загрузки", "Архив", "Настройки")
                val glyphs = listOf("⌂", "▣", "↓", "▤", "⚙")
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
                                Text("ROBOTPOST", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(tabs[page], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                            Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                                Text("RP", modifier = Modifier.padding(13.dp), color = Mint, fontWeight = FontWeight.Bold)
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
                                            Text("● Интерфейс готов к подключению загрузчика", color = Mint, style = MaterialTheme.typography.bodySmall)
                                            Button(onClick = { page = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Открыть каналы") }
                                        }
                                    }
                                }
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Metric("Каналы", channels.size.toString(), Modifier.weight(1f))
                                        Metric("Загрузки", "0", Modifier.weight(1f))
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
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(channels) { channel ->
                                            Surface(color = Panel, shape = Shape) {
                                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(channel.substringAfterLast("/").ifBlank { "YouTube-канал" }, fontWeight = FontWeight.SemiBold)
                                                        Text(channel, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                    TextButton(onClick = {
                                                        channels = channels - channel
                                                        prefs.edit().putString("channels", channels.joinToString("\n")).apply()
                                                    }) { Text("Удалить") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            2 -> Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                EmptyPanel("↓", "Загрузок пока нет", "Скачивание MP4/MP3 будет добавлено на следующем этапе. Этот экран станет очередью с прогрессом, паузой и историей.")
                            }
                            3 -> {
                                val cleaned = remember(description) { cleanDescription(description) }
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    item { Text("Редактор описания", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                                    item { Text("Вставь описание ролика. Удаляются типовые рекламные строки; таймкоды сохраняются. Проверь результат перед публикацией.", color = Muted) }
                                    item {
                                        OutlinedTextField(
                                            value = description,
                                            onValueChange = { description = it; prefs.edit().putString("description", it).apply() },
                                            label = { Text("Исходное описание") },
                                            minLines = 5, modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    item {
                                        Surface(color = Panel, shape = Shape) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text("Результат очистки", fontWeight = FontWeight.Bold)
                                                Text(cleaned.ifBlank { "Здесь появится очищенный текст." }, color = if(cleaned.isBlank()) Muted else Color.White)
                                                Button(
                                                    enabled = cleaned.isNotBlank(),
                                                    onClick = {
                                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RobotPost", cleaned))
                                                    }, modifier = Modifier.fillMaxWidth()
                                                ) { Text("Копировать описание") }
                                                OutlinedButton(onClick = {
                                                    description = cleaned
                                                    prefs.edit().putString("description", cleaned).apply()
                                                }, enabled = cleaned.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Перенести результат в редактор") }
                                            }
                                        }
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
                                                    Text("Параметр для будущей фоновой проверки", color = Muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                Switch(checked = autoCheck, onCheckedChange = {
                                                    autoCheck = it
                                                    prefs.edit().putBoolean("autoCheck", it).apply()
                                                })
                                            }
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
