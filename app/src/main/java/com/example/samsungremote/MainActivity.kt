package com.example.samsungremote

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var remote: SamsungRemote

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("tv", Context.MODE_PRIVATE)
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        var status by mutableStateOf("Не подключено")

        remote = SamsungRemote(
            onStatus = { status = it },
            onToken = { prefs.edit().putString("token", it).apply() }
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }

                    fun key(k: String) {
                        vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                        remote.sendKey(k)
                    }

                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = ip, onValueChange = { ip = it },
                                label = { Text("IP телевизора") }, singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(onClick = {
                                prefs.edit().putString("ip", ip).apply()
                                remote.connect(ip.trim(), prefs.getString("token", null))
                            }) { Text("OK") }
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            RBtn("⏻") { key("KEY_POWER") }
                            RBtn("Source") { key("KEY_SOURCE") }
                            RBtn("Mute") { key("KEY_MUTE") }
                        }

                        // Навигация
                        RBtn("▲") { key("KEY_UP") }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RBtn("◀") { key("KEY_LEFT") }
                            RBtn("OK") { key("KEY_ENTER") }
                            RBtn("▶") { key("KEY_RIGHT") }
                        }
                        RBtn("▼") { key("KEY_DOWN") }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            RBtn("Back") { key("KEY_RETURN") }
                            RBtn("Home") { key("KEY_HOME") }
                            RBtn("Menu") { key("KEY_MENU") }
                        }

                        // Громкость и каналы
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                RBtn("VOL +") { key("KEY_VOLUP") }
                                RBtn("VOL −") { key("KEY_VOLDOWN") }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                RBtn("CH +") { key("KEY_CHUP") }
                                RBtn("CH −") { key("KEY_CHDOWN") }
                            }
                        }

                        // Цифры
                        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { n -> RBtn("$n") { key("KEY_$n") } }
                            }
                        }
                        RBtn("0") { key("KEY_0") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        remote.disconnect()
        super.onDestroy()
    }
}

@Composable
fun RBtn(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.widthIn(min = 80.dp).height(52.dp)) {
        Text(label)
    }
}
