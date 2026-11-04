package com.example.samsungremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Bluetooth-колонка, подключённая к телефону.
 * Музыка идёт с телефона, поэтому пульт управляет громкостью телефона (поток «Музыка»,
 * она же громкость колонки) и плеером, который сейчас играет (пауза, следующий трек и т.д.).
 */
@SuppressLint("MissingPermission")
@Composable
fun SpeakerScreen() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val btAdapter = remember { (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var vol by remember { mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var muted by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf<List<String>>(emptyList()) }
    var paired by remember { mutableStateOf<List<String>>(emptyList()) }
    var a2dp by remember { mutableStateOf<BluetoothA2dp?>(null) }

    fun hasBtPerm() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    var btPerm by remember { mutableStateOf(hasBtPerm()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { btPerm = it }

    // Профиль A2DP — чтобы узнать, какая колонка сейчас подключена
    DisposableEffect(btPerm) {
        if (btPerm && btAdapter != null) {
            btAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy as BluetoothA2dp }
                override fun onServiceDisconnected(profile: Int) { a2dp = null }
            }, BluetoothProfile.A2DP)
        }
        onDispose { a2dp?.let { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }; a2dp = null }
    }

    // Обновляем громкость и список подключённых колонок (громкость могут менять и кнопками телефона)
    LaunchedEffect(btPerm, a2dp) {
        while (true) {
            vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            if (btPerm && btAdapter != null) {
                connected = try { a2dp?.connectedDevices?.map { it.name ?: it.address } ?: emptyList() } catch (_: Exception) { emptyList() }
                paired = try {
                    btAdapter.bondedDevices
                        ?.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
                        ?.map { it.name ?: it.address } ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }
            delay(1000)
        }
    }

    fun setVol(v: Int) {
        val nv = v.coerceIn(0, maxVol)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, nv, AudioManager.FLAG_SHOW_UI)
        vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun media(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Подключение
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    btAdapter == null -> Text("Bluetooth не поддерживается")
                    !btPerm -> {
                        Text("Нужно разрешение «Устройства поблизости», чтобы видеть колонку")
                        Button(onClick = { permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Разрешить") }
                    }
                    !btAdapter.isEnabled -> Text("Bluetooth выключен")
                    connected.isNotEmpty() -> Text("🔊 Подключено: ${connected.joinToString()}", style = MaterialTheme.typography.titleMedium)
                    else -> Text("Колонка не подключена", style = MaterialTheme.typography.titleMedium)
                }
                if (btPerm && connected.isEmpty() && paired.isNotEmpty()) {
                    Text("Сопряжённые аудиоустройства: ${paired.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
                // Android не разрешает приложениям самим подключать колонку — открываем настройки Bluetooth
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (connected.isEmpty()) "Подключить колонку" else "Настройки Bluetooth") }
            }
        }

        // Громкость
        Text("Громкость: ${if (muted) "без звука" else "${vol * 100 / maxVol.coerceAtLeast(1)}%"}",
            style = MaterialTheme.typography.titleMedium)
        Slider(
            value = vol.toFloat(),
            onValueChange = { setVol(it.toInt()) },
            valueRange = 0f..maxVol.toFloat(),
            steps = (maxVol - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RBtn("VOL −") { setVol(vol - 1) }
            RBtn(if (muted) "Звук" else "Mute") {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            }
            RBtn("VOL +") { setVol(vol + 1) }
        }

        // Плеер
        Text("Музыка", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PadBtn("◀◀") { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            PadBtn("▶‖") { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            PadBtn("▶▶") { media(KeyEvent.KEYCODE_MEDIA_NEXT) }
        }
        Text(
            "Кнопки управляют приложением, которое сейчас играет музыку (YouTube Music, Spotify и др.).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
