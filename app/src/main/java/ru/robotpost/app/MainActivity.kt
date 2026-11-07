package ru.robotpost.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var selected by remember { mutableIntStateOf(0) }
                val tabs = listOf("Главная", "Каналы", "Загрузки", "Архив", "Настройки")
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEachIndexed { index, tab ->
                                NavigationBarItem(
                                    selected = selected == index,
                                    onClick = { selected = index },
                                    icon = { Text((index + 1).toString()) },
                                    label = { Text(tab) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("RobotPost", style = MaterialTheme.typography.headlineMedium)
                        Text(tabs[selected], style = MaterialTheme.typography.titleLarge)
                        Text("Начальная версия для проверки облачной сборки APK.")
                        Text("Загрузка YouTube пока не реализована.")
                    }
                }
            }
        }
    }
}
