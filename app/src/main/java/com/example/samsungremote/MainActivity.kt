package com.example.samsungremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
    private lateinit var wifiScanner: WifiTvScanner
    private lateinit var btScanner: BluetoothTvScanner

    private fun btPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasAll(perms: Array<String>) =
        perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("tv", Context.MODE_PRIVATE)
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        var status by mutableStateOf("Не подключено")

        remote = SamsungRemote(
            onStatus = { status = it },
            onToken = { prefs.edit().putString("token", it).apply() }
        )

        wifiScanner = WifiTvScanner(this)
        btScanner = BluetoothTvScanner(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }

                    val wifiTvs = remember { mutableStateListOf<WifiTv>() }
                    val btDevices = remember { mutableStateListOf<BtTv>() }
                    var wifiBusy by remember { mutableStateOf(false) }
                    var btBusy by remember { mutableStateOf(false) }
                    var scanInfo by remember { mutableStateOf("") }

                    fun connectTo(tvIp: String) {
                        ip = tvIp
                        prefs.edit().putString("ip", tvIp).apply()
                        remote.connect(tvIp, prefs.getString("token", null))
                    }

                    fun startWifiScan(then: (() -> Unit)? = null) {
                        if (wifiBusy) return
                        wifiBusy = true
                        scanInfo = "Поиск ТВ в Wi-Fi сети…"
                        wifiTvs.clear()
                        wifiScanner.scan(
                            onFound = { tv -> wifiTvs.add(tv) },
                            onDone = { msg -> wifiBusy = false; scanInfo = msg; then?.invoke() }
                        )
                    }

                    fun startBtScan() {
                        if (btBusy) return
                        btBusy = true
                        scanInfo = "Поиск по Bluetooth…"
                        btDevices.clear()
                        btScanner.scan(
                            onFound = { d ->
                                if (d.isSamsung) btDevices.add(0, d) else btDevices.add(d)
                            },
                            onDone = { msg -> btBusy = false; scanInfo = msg }
                        )
                    }

                    // ТВ управляется только по сети, поэтому Bluetooth-устройство ищем в Wi-Fi по имени
                    fun pickBt(d: BtTv) {
                        val match = matchByName(d, wifiTvs)
                        if (match != null) {
                            connectTo(match.ip)
                            return
                        }
                        startWifiScan {
                            val m = matchByName(d, wifiTvs)
                            if (m != null) connectTo(m.ip)
                            else scanInfo = "«${d.name}» найден по Bluetooth, но не найден в Wi-Fi. " +
                                "Управление возможно только по сети: подключите ТВ к той же Wi-Fi."
                        }
                    }

                    val btPermLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { res ->
                        if (res.values.all { it }) startBtScan()
                        else scanInfo = "Нет разрешения на Bluetooth"
                    }

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

                        // Поиск ТВ
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { if (wifiBusy) wifiScanner.cancel() else startWifiScan() },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (wifiBusy) "Стоп" else "Поиск Wi-Fi") }
                            OutlinedButton(
                                onClick = {
                                    when {
                                        btBusy -> { btScanner.stop(); btBusy = false; scanInfo = "Поиск остановлен" }
                                        !btScanner.isAvailable -> scanInfo = "Bluetooth не поддерживается"
                                        hasAll(btPermissions()) -> startBtScan()
                                        else -> btPermLauncher.launch(btPermissions())
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (btBusy) "Стоп" else "Поиск Bluetooth") }
                        }
                        if (wifiBusy || btBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (scanInfo.isNotEmpty()) Text(scanInfo, style = MaterialTheme.typography.bodySmall)

                        wifiTvs.forEach { tv ->
                            DeviceRow("📶 ${tv.name}", listOf(tv.model, tv.ip).filter { it.isNotBlank() }.joinToString(" · ")) {
                                connectTo(tv.ip)
                            }
                        }
                        btDevices.forEach { d ->
                            DeviceRow("🔵 ${d.name}", if (d.isSamsung) "Samsung · ${d.address}" else d.address) {
                                pickBt(d)
                            }
                        }

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
        wifiScanner.cancel()
        btScanner.stop()
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

@Composable
fun DeviceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
