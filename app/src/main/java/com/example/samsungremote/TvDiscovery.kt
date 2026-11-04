package com.example.samsungremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Найденный телевизор (по Wi-Fi) */
data class WifiTv(val ip: String, val name: String, val model: String)

/** Найденное Bluetooth-устройство */
data class BtTv(val address: String, val name: String, val isSamsung: Boolean)

/**
 * Поиск телевизоров Samsung в локальной сети:
 *  1) SSDP (UPnP multicast) — быстрый, ТВ отвечает сам;
 *  2) перебор подсети по порту 8001 — если роутер режет multicast.
 * Каждый кандидат проверяется запросом http://IP:8001/api/v2/ (возвращает имя и модель).
 */
class WifiTvScanner(private val context: Context) {

    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /** Запускает поиск в фоне. onFound вызывается в главном потоке для каждого нового ТВ. */
    fun scan(onFound: (WifiTv) -> Unit, onDone: (String) -> Unit) {
        cancelled = false
        val main = Handler(Looper.getMainLooper())
        val found = ConcurrentHashMap<String, WifiTv>()

        Thread {
            val pool = Executors.newFixedThreadPool(32)
            fun check(ip: String) {
                if (cancelled || found.containsKey(ip)) return
                val tv = probe(ip) ?: return
                if (found.putIfAbsent(ip, tv) == null) main.post { onFound(tv) }
            }

            // 1. SSDP
            ssdpCandidates().forEach { ip -> pool.execute { check(ip) } }

            // 2. Перебор подсети
            val subnet = localSubnet()
            if (subnet != null) {
                val (base, from, to) = subnet
                for (i in from..to) {
                    val ip = intToIp(base + i)
                    pool.execute {
                        if (!cancelled && !found.containsKey(ip) && portOpen(ip, 8001, 400)) check(ip)
                    }
                }
            }

            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
            val msg = when {
                cancelled -> "Поиск остановлен"
                found.isEmpty() && subnet == null -> "Нет Wi-Fi подключения"
                found.isEmpty() -> "Телевизор не найден. ТВ включён и в той же сети?"
                else -> "Найдено ТВ: ${found.size}"
            }
            main.post { onDone(msg) }
        }.start()
    }

    /** SSDP M-SEARCH, возвращает IP всех ответивших устройств Samsung/DIAL. */
    private fun ssdpCandidates(): Set<String> {
        val result = mutableSetOf<String>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tv-ssdp").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            MulticastSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(0))
                sock.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf(
                    "urn:samsung.com:device:RemoteControlReceiver:1",
                    "urn:dial-multiscreen-org:service:dial:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1"
                )
                repeat(2) {
                    targets.forEach { st ->
                        val msg = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $st\r\n\r\n"
                        val bytes = msg.toByteArray()
                        sock.send(DatagramPacket(bytes, bytes.size, group, 1900))
                    }
                }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 3000
                while (!cancelled && System.currentTimeMillis() < deadline) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val text = String(p.data, 0, p.length)
                        if (text.contains("samsung", ignoreCase = true) || text.contains("dial", ignoreCase = true)) {
                            p.address?.hostAddress?.let { result.add(it) }
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (lock.isHeld) lock.release()
        }
        return result
    }

    /** Проверяет, что по IP отвечает Samsung TV (Tizen API), и возвращает имя. */
    private fun probe(ip: String): WifiTv? = try {
        val conn = URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(body)
        val device = json.optJSONObject("device")
        val name = device?.optString("name")?.takeIf { it.isNotBlank() }
            ?: json.optString("name").takeIf { it.isNotBlank() }
            ?: "Samsung TV"
        val model = device?.optString("modelName").orEmpty()
        WifiTv(ip, decodeName(name), model)
    } catch (_: Exception) {
        null
    }

    private fun portOpen(ip: String, port: Int, timeout: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout); true }
    } catch (_: Exception) {
        false
    }

    /** Возвращает (адрес сети как int, первый хост, последний хост). Ограничено /24 для скорости. */
    private fun localSubnet(): Triple<Int, Int, Int>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = ipToInt(la.address as Inet4Address)
        val prefix = la.prefixLength.coerceIn(24, 30)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ip and mask
        val hosts = (1 shl (32 - prefix)) - 2
        return Triple(network, 1, hosts)
    }

    private fun ipToInt(a: Inet4Address): Int =
        a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

    private fun intToIp(v: Int) =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    // Некоторые ТВ возвращают имя в HTML-кодировке (&quot; и т.п.)
    private fun decodeName(s: String) = s.replace("&quot;", "\"").replace("&amp;", "&")
}

/**
 * Поиск телевизора по Bluetooth (классический + BLE).
 * Важно: Samsung TV не принимает команды пульта через Bluetooth от сторонних приложений,
 * поэтому найденное устройство используется для того, чтобы определить ТВ по имени,
 * а управление идёт по Wi-Fi.
 */
@SuppressLint("MissingPermission")
class BluetoothTvScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var leCallback: ScanCallback? = null
    private val seen = mutableSetOf<String>()

    val isAvailable get() = adapter != null
    val isEnabled get() = adapter?.isEnabled == true

    fun scan(onFound: (BtTv) -> Unit, onDone: (String) -> Unit) {
        val bt = adapter ?: return onDone("Bluetooth не поддерживается")
        if (!bt.isEnabled) return onDone("Включите Bluetooth")
        stop()
        seen.clear()

        fun report(address: String, name: String?, samsungMfr: Boolean) {
            val n = name?.takeIf { it.isNotBlank() } ?: return
            if (!seen.add(address)) return
            val isSamsung = samsungMfr || n.contains("samsung", true) || n.contains("[TV]", true)
            onFound(BtTv(address, n, isSamsung))
        }

        // Уже сопряжённые устройства
        bt.bondedDevices?.forEach { report(it.address, it.name, false) }

        // Классический поиск
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == BluetoothDevice.ACTION_FOUND) {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: d?.name
                    d?.let { report(it.address, name, false) }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        bt.startDiscovery()

        // BLE (ТВ Samsung рекламируют себя по BLE, производитель 0x0075)
        bt.bluetoothLeScanner?.let { scanner ->
            leCallback = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val mfr = r.scanRecord?.getManufacturerSpecificData(0x0075) != null
                    report(r.device.address, r.scanRecord?.deviceName ?: r.device.name, mfr)
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try { scanner.startScan(null, settings, leCallback) } catch (_: Exception) {}
        }

        main.postDelayed({
            stop()
            onDone(if (seen.isEmpty()) "Bluetooth-устройства не найдены" else "Bluetooth: найдено ${seen.size}")
        }, 12_000)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        leCallback?.let { cb -> try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {} }
        leCallback = null
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }
}

/** Сопоставление Bluetooth-имени с ТВ, найденным по Wi-Fi. */
fun matchByName(bt: BtTv, tvs: List<WifiTv>): WifiTv? {
    fun norm(s: String) = s.lowercase().replace("[tv]", "").replace(Regex("[^a-z0-9а-яё]"), "")
    val b = norm(bt.name)
    if (b.isEmpty()) return null
    return tvs.firstOrNull { norm(it.name) == b }
        ?: tvs.firstOrNull { val w = norm(it.name); w.isNotEmpty() && (w.contains(b) || b.contains(w)) }
        ?: tvs.singleOrNull()?.takeIf { bt.isSamsung }
}
