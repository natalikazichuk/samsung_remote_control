package com.example.samsungremote

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.*
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Клиент пульта для Samsung Smart TV (Tizen, 2016+).
 * Протокол: wss://<IP>:8002/api/v2/channels/samsung.remote.control
 *
 * Соединение держится само:
 *  - каждые 10 с отправляется ping, чтобы ТВ и роутер не закрывали «молчащее» соединение;
 *  - при обрыве — автоматическое переподключение (1 → 2 → 4 … до 15 с);
 *  - нажатие кнопки без связи ставит команду в очередь и сразу переподключается.
 */
class SamsungRemote(
    private val onStatus: (String) -> Unit,
    private val onToken: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var wanted = false      // пользователь хочет быть подключён
    private var ip: String? = null
    private var token: String? = null
    private var attempt = 0
    private val pending = ArrayDeque<String>() // команды, нажатые без связи

    // У ТВ самоподписанный сертификат, поэтому доверяем ему (только для локальной сети!)
    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun connect(ip: String, token: String?) {
        main.post {
            this.ip = ip
            this.token = token
            wanted = true
            attempt = 0
            open()
        }
    }

    /** Вызывать при возврате в приложение: если связь пропала — сразу переподключиться. */
    fun ensureConnected() {
        main.post { if (wanted && !connected) { attempt = 0; open() } }
    }

    private fun open() {
        val host = ip ?: return
        main.removeCallbacksAndMessages(RECONNECT)
        ws?.cancel()
        connected = false

        val name = Base64.encodeToString("AndroidRemote".toByteArray(), Base64.NO_WRAP)
        var url = "wss://$host:8002/api/v2/channels/samsung.remote.control?name=$name"
        if (!token.isNullOrBlank()) url += "&token=$token"
        onStatus(if (attempt == 0) "Подключаюсь…" else "Переподключаюсь…")

        lateinit var socket: WebSocket
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (attempt == 0 && token.isNullOrBlank()) post(socket) { onStatus("Подтвердите подключение на экране ТВ") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (_: Exception) { return }
                when (json.optString("event")) {
                    "ms.channel.connect" -> post(socket) {
                        json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let {
                            token = it
                            onToken(it)
                        }
                        connected = true
                        attempt = 0
                        onStatus("Подключено ✓")
                        while (pending.isNotEmpty()) socket.send(pending.removeFirst())
                    }
                    "ms.channel.unauthorized" -> post(socket) {
                        wanted = false
                        onStatus("ТВ отклонил подключение. Разрешите доступ на ТВ и нажмите OK")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post(socket) { lost("Связь потеряна") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post(socket) { lost("Отключено") }
            }
        })
        ws = socket
    }

    /** Выполнить в главном потоке, только если событие от текущего сокета (а не от старого). */
    private fun post(socket: WebSocket, block: () -> Unit) {
        main.post { if (ws === socket) block() }
    }

    private fun lost(reason: String) {
        connected = false
        ws = null
        if (!wanted) { onStatus(reason); return }
        val delay = (1000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L)
        attempt++
        onStatus("$reason. Переподключение через ${delay / 1000} с…")
        main.postAtTime({ if (wanted && !connected) open() }, RECONNECT, android.os.SystemClock.uptimeMillis() + delay)
    }

    fun sendKey(key: String) {
        val msg = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", key)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }.toString()
        main.post {
            val socket = ws
            if (connected && socket != null && socket.send(msg)) return@post
            // Нет связи — запоминаем нажатие (только последние несколько) и переподключаемся
            if (ip == null) { onStatus("Сначала выберите телевизор"); return@post }
            pending.addLast(msg)
            while (pending.size > 5) pending.removeFirst()
            // Если подключение уже идёт — просто ждём его, иначе подключаемся сразу
            if (ws == null) { wanted = true; attempt = 0; open() }
        }
    }

    fun disconnect() {
        main.post {
            wanted = false
            connected = false
            pending.clear()
            main.removeCallbacksAndMessages(RECONNECT)
            ws?.close(1000, null)
            ws = null
        }
    }

    private companion object {
        val RECONNECT = Any()
    }
}
