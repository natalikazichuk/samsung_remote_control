package com.example.samsungremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/* ---------------------------- SmartThings API ---------------------------- */

data class StDevice(val id: String, val label: String)

data class WasherStatus(
    val power: String?,          // on / off
    val machineState: String?,   // run / pause / stop
    val jobState: String?,       // wash / rinse / spin / finish / none …
    val remainingMin: Int?,
    val progress: Int?,          // %
    val remoteEnabled: Boolean?  // «Дистанционное управление» на машине
)

class StException(message: String) : Exception(message)

/** Минимальный клиент облака SmartThings (https://developer.smartthings.com/docs/api/public). */
class SmartThingsClient(private val token: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val base = "https://api.smartthings.com/v1"

    private fun call(path: String, body: JSONObject? = null): JSONObject {
        val rb = Request.Builder().url(base + path).header("Authorization", "Bearer $token")
        if (body != null) rb.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            when (r.code) {
                in 200..299 -> return if (text.isBlank()) JSONObject() else JSONObject(text)
                401 -> throw StException("Токен недействителен или истёк. Создайте новый на account.smartthings.com/tokens")
                403 -> throw StException("У токена нет прав на устройства. При создании отметьте Devices: List, See, Control")
                404 -> throw StException("Устройство не найдено в SmartThings")
                429 -> throw StException("Слишком много запросов, подождите минуту")
                else -> throw StException("Ошибка SmartThings ${r.code}")
            }
        }
    }

    /** Все устройства со стандартной возможностью «состояние стиральной машины». */
    fun washers(): List<StDevice> {
        val items = call("/devices?capability=washerOperatingState").optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { i ->
            val d = items.getJSONObject(i)
            StDevice(d.getString("deviceId"), d.optString("label").ifBlank { d.optString("name", "Стиральная машина") })
        }
    }

    fun status(id: String): WasherStatus {
        val main = call("/devices/$id/status").optJSONObject("components")?.optJSONObject("main") ?: JSONObject()
        fun v(cap: String, attr: String): Any? =
            main.optJSONObject(cap)?.optJSONObject(attr)?.opt("value")?.takeIf { it != JSONObject.NULL }

        val remaining = (v("samsungce.washerOperatingState", "remainingTime") as? Number)?.toInt()
            ?: minutesUntil(v("washerOperatingState", "completionTime") as? String)
        return WasherStatus(
            power = v("switch", "switch") as? String,
            machineState = v("washerOperatingState", "machineState") as? String,
            jobState = (v("washerOperatingState", "washerJobState")
                ?: v("samsungce.washerOperatingState", "washerJobState")) as? String,
            remainingMin = remaining,
            progress = (v("samsungce.washerOperatingState", "progress") as? Number)?.toInt(),
            remoteEnabled = v("remoteControlStatus", "remoteControlEnabled")?.toString()?.toBooleanStrictOrNull()
        )
    }

    fun command(id: String, capability: String, command: String, vararg args: Any) {
        val cmd = JSONObject()
            .put("component", "main")
            .put("capability", capability)
            .put("command", command)
            .put("arguments", JSONArray(args.toList()))
        call("/devices/$id/commands", JSONObject().put("commands", JSONArray().put(cmd)))
    }

    fun setMachineState(id: String, state: String) = command(id, "washerOperatingState", "setMachineState", state)
    fun power(id: String, on: Boolean) = command(id, "switch", if (on) "on" else "off")

    private fun minutesUntil(iso: String?): Int? = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val t = f.parse(iso!!.take(19))!!.time
        ((t - System.currentTimeMillis()) / 60_000).toInt().coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}

/* ------------------------------- Подписи -------------------------------- */

private fun machineLabel(s: String?) = when (s) {
    "run" -> "Работает"
    "pause" -> "Пауза"
    "stop" -> "Остановлена"
    null -> "—"
    else -> s
}

private fun jobLabel(s: String?) = when (s) {
    "none" -> "Ожидание"
    "weightSensing" -> "Взвешивание"
    "wash", "preWash" -> "Стирка"
    "rinse" -> "Полоскание"
    "spin" -> "Отжим"
    "drying", "airWash" -> "Сушка"
    "delayWash" -> "Отложенный старт"
    "finish", "finished" -> "Готово"
    "wrinklePrevent" -> "Против складок"
    "aIRinse", "aISpin", "aIWash" -> "AI-стирка"
    "cooling" -> "Охлаждение"
    null -> "—"
    else -> s
}

/* ------------------------------ Уведомления ----------------------------- */

private const val CHANNEL = "washer"

@SuppressLint("MissingPermission")
private fun notifyDone(context: Context, label: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    if (Build.VERSION.SDK_INT >= 26) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Стиральная машина", NotificationManager.IMPORTANCE_HIGH)
        )
    }
    val n = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Стирка закончена")
        .setContentText("$label: можно доставать бельё")
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(1, n)
}

/* --------------------------------- Экран -------------------------------- */

@Composable
fun WasherScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("washer", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var tokenInput by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<List<StDevice>>(emptyList()) }
    var deviceId by remember { mutableStateOf(prefs.getString("device", null)) }
    var status by remember { mutableStateOf<WasherStatus?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val client = remember(token) { if (token.isBlank()) null else SmartThingsClient(token) }
    val device = devices.firstOrNull { it.id == deviceId }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Загрузить список машин после ввода токена
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        busy = true
        message = "Загружаю устройства SmartThings…"
        try {
            val list = withContext(Dispatchers.IO) { c.washers() }
            devices = list
            if (list.none { it.id == deviceId }) deviceId = list.firstOrNull()?.id
            deviceId?.let { prefs.edit().putString("device", it).apply() }
            message = if (list.isEmpty())
                "В вашем SmartThings нет стиральной машины. Добавьте её в приложении SmartThings."
            else ""
        } catch (e: Exception) {
            message = e.message ?: "Нет связи с SmartThings"
        }
        busy = false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Обновление состояния: каждые 20 с во время стирки, иначе раз в минуту
    LaunchedEffect(client, deviceId) {
        val c = client ?: return@LaunchedEffect
        val id = deviceId ?: return@LaunchedEffect
        var wasRunning = false
        while (true) {
            try {
                val s = withContext(Dispatchers.IO) { c.status(id) }
                status = s
                val running = s.machineState == "run"
                if (wasRunning && !running && s.machineState == "stop") {
                    notifyDone(context, devices.firstOrNull { it.id == id }?.label ?: "Стиральная машина")
                }
                wasRunning = running
                if (message.startsWith("Нет связи")) message = ""
            } catch (e: Exception) {
                message = e.message ?: "Нет связи с SmartThings"
            }
            delay(if (status?.machineState == "run") 20_000 else 60_000)
        }
    }

    fun send(label: String, action: SmartThingsClient.(String) -> Unit) {
        val c = client ?: return
        val id = deviceId ?: return
        scope.launch {
            busy = true
            message = "$label…"
            message = try {
                withContext(Dispatchers.IO) { c.action(id) }
                delay(2000) // машине нужно время, чтобы сменить состояние
                status = withContext(Dispatchers.IO) { c.status(id) }
                "Команда отправлена"
            } catch (e: Exception) {
                e.message ?: "Ошибка"
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (token.isBlank()) {
            Text("Подключение через Samsung SmartThings", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Стиральная машина должна быть добавлена в приложение SmartThings.\n" +
                    "2. Откройте account.smartthings.com/tokens, войдите в аккаунт Samsung и нажмите " +
                    "«Generate new token». Отметьте Devices: List all devices, See all devices, Control all devices.\n" +
                    "3. Скопируйте токен и вставьте сюда. Он хранится только на этом телефоне.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = tokenInput, onValueChange = { tokenInput = it.trim() },
                label = { Text("Токен SmartThings") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    prefs.edit().putString("token", tokenInput).apply()
                    token = tokenInput
                    tokenInput = ""
                },
                enabled = tokenInput.length > 10,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        } else {

        if (devices.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { d ->
                    FilterChip(
                        selected = d.id == deviceId,
                        onClick = { deviceId = d.id; status = null; prefs.edit().putString("device", d.id).apply() },
                        label = { Text(d.label) }
                    )
                }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (device != null) {
            val s = status
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(device.label, style = MaterialTheme.typography.titleLarge)
                    if (s == null) Text("Получаю состояние…")
                    else {
                        Text("Питание: ${if (s.power == "on") "включена" else if (s.power == "off") "выключена" else "—"}")
                        Text("Состояние: ${machineLabel(s.machineState)}")
                        Text("Этап: ${jobLabel(s.jobState)}")
                        if (s.machineState == "run" || s.machineState == "pause") {
                            s.remainingMin?.let { Text("Осталось: ${it / 60} ч ${it % 60} мин") }
                            s.progress?.let {
                                LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Text(
                            "Дистанционное управление: " + when (s.remoteEnabled) {
                                true -> "включено"; false -> "выключено"; null -> "—"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (status?.remoteEnabled == false) {
                Text(
                    "Чтобы запускать стирку с телефона, загрузите бельё, выберите программу и нажмите на машине " +
                        "кнопку «Дистанционное управление» (Smart Control). Пауза и стоп работают и без этого.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { send("Запуск") { setMachineState(it, "run") } },
                    enabled = !busy && status?.machineState != "run",
                    modifier = Modifier.weight(1f)
                ) { Text(if (status?.machineState == "pause") "Продолжить" else "Старт") }
                FilledTonalButton(
                    onClick = { send("Пауза") { setMachineState(it, "pause") } },
                    enabled = !busy && status?.machineState == "run",
                    modifier = Modifier.weight(1f)
                ) { Text("Пауза") }
                FilledTonalButton(
                    onClick = { send("Остановка") { setMachineState(it, "stop") } },
                    enabled = !busy && status?.machineState != "stop",
                    modifier = Modifier.weight(1f)
                ) { Text("Стоп") }
            }
            OutlinedButton(
                onClick = { send("Питание") { power(it, status?.power != "on") } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (status?.power == "on") "Выключить" else "Включить") }
        }

        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                prefs.edit().remove("token").apply()
                token = ""; devices = emptyList(); status = null; message = ""
            }) { Text("Сменить токен") }
        }
        }
    }
}
