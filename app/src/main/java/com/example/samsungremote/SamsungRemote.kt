package com.example.samsungremote

import android.util.Base64
import okhttp3.*
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Клиент пульта для Samsung Smart TV (Tizen, 2016+).
 * Протокол: wss://<IP>:8002/api/v2/channels/samsung.remote.control
 */
class SamsungRemote(
    private val onStatus: (String) -> Unit,
    private val onToken: (String) -> Unit
) {
    private var ws: WebSocket? = null

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
            .build()
    }

    fun connect(ip: String, token: String?) {
        disconnect()
        val name = Base64.encodeToString("AndroidRemote".toByteArray(), Base64.NO_WRAP)
        var url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name"
        if (!token.isNullOrBlank()) url += "&token=$token"

        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("Подключаюсь… подтвердите на экране ТВ")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("event") == "ms.channel.connect") {
                    json.optJSONObject("data")?.optString("token")
                        ?.takeIf { it.isNotBlank() }?.let(onToken)
                    onStatus("Подключено ✓")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Ошибка: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("Отключено")
            }
        })
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
        }
        ws?.send(msg.toString())
    }

    fun disconnect() {
        ws?.close(1000, null)
        ws = null
    }
}
