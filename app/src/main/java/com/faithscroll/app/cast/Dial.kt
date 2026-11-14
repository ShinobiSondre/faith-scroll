package com.faithscroll.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.faithscroll.app.data.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * "Play on TV" the way YouTube does it: finds TVs on the Wi-Fi that have the YouTube app (Samsung,
 * LG, Sony, Android TV, Chromecast …) with DIAL – the open standard YouTube's own cast button uses –
 * and tells the TV's YouTube app to play the video from the same second. No Google Cast needed.
 */
object Dial {
    /** A TV with a YouTube app. [appUrl] is where its apps are started. */
    data class Tv(val name: String, val appUrl: String, val host: String)

    val tvs = MutableStateFlow<List<Tv>>(emptyList())
    val searching = MutableStateFlow(false)
    /** The TV the video was last sent to. */
    val playingOn = MutableStateFlow<String?>(null)

    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    /** Asks the network "who has DIAL?" for a few seconds and reads each answer's description. */
    suspend fun search(ctx: Context) = withContext(Dispatchers.IO) {
        if (searching.value) return@withContext
        searching.value = true
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("faithscroll-dial").apply { setReferenceCounted(false); acquire() }
        val locations = linkedSetOf<String>()
        try {
            MulticastSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.soTimeout = 1200
                sock.bind(java.net.InetSocketAddress(0))
                val group = InetAddress.getByName("239.255.255.250")
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                    "ST: urn:dial-multiscreen-org:service:dial:1\r\nUSER-AGENT: FaithScroll/1 UPnP/1.1\r\n\r\n").toByteArray()
                val end = System.currentTimeMillis() + 4_000
                var sent = 0
                while (System.currentTimeMillis() < end) {
                    if (sent < 3) { sock.send(DatagramPacket(msg, msg.size, group, 1900)); sent++ }
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { loc ->
                        if (locations.add(loc)) describe(loc)?.let { tv ->
                            if (tvs.value.none { it.appUrl == tv.appUrl }) tvs.value = tvs.value + tv
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Diag.err("cast", "TV search failed", e)
        } finally {
            lock.release()
            searching.value = false
            Diag.log("cast", "TV search: ${tvs.value.size} with DIAL (${tvs.value.joinToString { it.name }})")
        }
    }

    /** The device description gives its name; its Application-URL header says where apps are started. */
    private fun describe(location: String): Tv? = runCatching {
        http.newCall(Request.Builder().url(location).build()).execute().use { r ->
            val appUrl = r.header("Application-URL") ?: return@use null
            val xml = r.body?.string().orEmpty()
            val name = Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)?.groupValues?.get(1)?.trim()
                ?: Regex("<modelName>([^<]+)</modelName>").find(xml)?.groupValues?.get(1)?.trim() ?: "TV"
            Tv(name.replace("&amp;", "&"), if (appUrl.endsWith("/")) appUrl else "$appUrl/", android.net.Uri.parse(location).host.orEmpty())
        }
    }.getOrNull()

    /**
     * Starts YouTube on [tv] with [videoId] at [positionMs] – the same message YouTube's own app
     * sends. Returns an error text, or null when the TV said yes.
     */
    suspend fun play(tv: Tv, videoId: String, positionMs: Long): String? = withContext(Dispatchers.IO) {
        val body = "v=$videoId&t=${positionMs / 1000}".toRequestBody("text/plain; charset=utf-8".toMediaType())
        runCatching {
            http.newCall(Request.Builder().url(tv.appUrl + "YouTube").post(body).build()).execute().use { r ->
                Diag.log("cast", "YouTube on ${tv.name}: HTTP ${r.code}")
                when (r.code) {
                    in 200..299 -> { playingOn.value = tv.name; null }
                    404 -> com.faithscroll.app.util.tr("${tv.name} has no YouTube app.", "${tv.name} har ikke YouTube-appen.")
                    403 -> com.faithscroll.app.util.tr("${tv.name} said no – check the TV's settings for starting apps from phones.",
                        "${tv.name} sa nei – sjekk innstillingen på TV-en for å starte apper fra mobilen.")
                    else -> com.faithscroll.app.util.tr("The TV answered ${r.code}.", "TV-en svarte ${r.code}.")
                }
            }
        }.getOrElse { com.faithscroll.app.util.tr("Couldn't reach ${tv.name}.", "Fikk ikke kontakt med ${tv.name}.") }
    }

    /** Closes YouTube on the TV. */
    suspend fun stop(tv: Tv) = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url(tv.appUrl + "YouTube/run").delete().build()).execute().close() }
        playingOn.value = null
    }
}
