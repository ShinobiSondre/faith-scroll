package com.faithscroll.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.faithscroll.app.data.Diag
import com.faithscroll.app.util.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finds TVs on the Wi-Fi and plays a video on them, two ways:
 *  - DIAL: tells the TV's own YouTube app to open the video (what YouTube's cast button does).
 *  - DLNA ("Play to"): sends the video's stream to the TV's built-in player – most smart TVs,
 *    Samsung included, can do this even when they don't let phones start YouTube.
 * One entry per TV (by its address), with the TV's own name.
 */
object Dial {
    data class Tv(
        val host: String,
        val name: String,
        val dialAppUrl: String? = null,     // where DIAL apps are started (YouTube)
        val avControlUrl: String? = null,   // DLNA AVTransport control (play a link)
        val maker: String = ""
    ) {
        val canYouTube get() = dialAppUrl != null
        val canPlayLink get() = avControlUrl != null
    }

    val tvs = MutableStateFlow<List<Tv>>(emptyList())
    val searching = MutableStateFlow(false)
    val playingOn = MutableStateFlow<String?>(null)
    /** Everything that answered (type and name), for the diagnostics. */
    private val seen = ConcurrentHashMap<String, String>()

    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

    private val searchTypes = listOf(
        "urn:dial-multiscreen-org:service:dial:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:samsung.com:device:RemoteControlReceiver:1",
        "upnp:rootdevice"
    )

    suspend fun search(ctx: Context) = withContext(Dispatchers.IO) {
        if (searching.value) return@withContext
        searching.value = true
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("faithscroll-tv").apply { setReferenceCounted(false); acquire() }
        val locations = linkedSetOf<String>()
        try {
            MulticastSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.soTimeout = 800
                sock.bind(java.net.InetSocketAddress(0))
                val group = InetAddress.getByName("239.255.255.250")
                val end = System.currentTimeMillis() + 7_000
                var round = 0
                var nextSend = 0L
                while (System.currentTimeMillis() < end) {
                    if (round < 3 && System.currentTimeMillis() >= nextSend) {
                        searchTypes.forEach { st ->
                            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: $st\r\n" +
                                "USER-AGENT: Android UPnP/1.1 FaithScroll/1\r\n\r\n").toByteArray()
                            runCatching { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                        }
                        round++; nextSend = System.currentTimeMillis() + 1500
                    }
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    val loc = Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: continue
                    if (locations.add(loc)) describe(loc)?.let { merge(it) }
                }
            }
        } catch (e: Exception) {
            Diag.err("cast", "TV search failed", e)
        } finally {
            lock.release()
            searching.value = false
            Diag.log("cast", "TV search answered: " + seen.entries.joinToString(" | ") { "${it.key}: ${it.value}" })
            Diag.log("cast", "TVs: " + tvs.value.joinToString { "${it.name} (${it.host}${if (it.canYouTube) ", YouTube" else ""}${if (it.canPlayLink) ", play link" else ""})" })
        }
    }

    private fun merge(t: Tv) {
        val list = tvs.value.toMutableList()
        val i = list.indexOfFirst { it.host == t.host }
        if (i < 0) list += t else {
            val o = list[i]
            // Keep the nicest name (the TV's own "friendly" name, not a generic one) and every ability.
            val name = listOf(o.name, t.name).maxByOrNull { n -> (if (n.startsWith("[TV]")) 100 else 0) + (if (n == "TV") -50 else 0) + n.length } ?: o.name
            list[i] = o.copy(name = name, dialAppUrl = o.dialAppUrl ?: t.dialAppUrl, avControlUrl = o.avControlUrl ?: t.avControlUrl, maker = o.maker.ifBlank { t.maker })
        }
        // TVs first: things that can play; routers and other boxes that can't are left out.
        tvs.value = list.filter { it.canYouTube || it.canPlayLink }
    }

    /** Reads a device description: its name, maker, and whether it has DIAL and/or a DLNA player. */
    private fun describe(location: String): Tv? = runCatching {
        http.newCall(Request.Builder().url(location).build()).execute().use { r ->
            val appUrl = r.header("Application-URL")
            val xml = r.body?.string().orEmpty()
            val host = android.net.Uri.parse(location).host.orEmpty()
            fun tag(t: String) = Regex("<$t>([^<]+)</$t>").find(xml)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")
            val name = tag("friendlyName") ?: tag("modelName") ?: "TV"
            val maker = tag("manufacturer").orEmpty()
            val type = tag("deviceType").orEmpty()
            seen[host + " " + type.substringAfterLast(":device:")] = "$name / $maker"
            // DLNA player: the AVTransport service's control address (relative to the description).
            val av = Regex("<service>(?:(?!</service>).)*?AVTransport(?:(?!</service>).)*?<controlURL>([^<]+)</controlURL>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()
            val base = tag("URLBase") ?: location
            val avUrl = av?.let { java.net.URL(java.net.URL(base), it).toString() }
            if (appUrl == null && avUrl == null && !maker.contains("samsung", true)) return@use null
            Tv(host, name, appUrl?.let { if (it.endsWith("/")) it else "$it/" }, avUrl, maker)
        }
    }.getOrNull()

    /**
     * Plays a video on [tv]. YouTube videos first try the TV's YouTube app; if the TV won't (or for
     * other videos) the stream link [link] goes to the TV's own player. Returns an error, or null.
     */
    suspend fun play(tv: Tv, youTubeId: String?, link: suspend () -> String?, title: String, positionMs: Long): String? = withContext(Dispatchers.IO) {
        var why: String? = null
        if (youTubeId != null && tv.dialAppUrl != null) {
            why = dialYouTube(tv, youTubeId, positionMs)
            if (why == null) return@withContext null
        }
        if (tv.avControlUrl != null) {
            val url = link() ?: return@withContext why ?: tr("This video can't be sent to the TV.", "Denne videoen kan ikke sendes til TV-en.")
            val err = dlnaPlay(tv, url, title, positionMs)
            if (err == null) return@withContext null
            return@withContext err
        }
        why ?: tr("${tv.name} can't play videos from the phone.", "${tv.name} kan ikke spille videoer fra telefonen.")
    }

    private fun dialYouTube(tv: Tv, id: String, positionMs: Long): String? {
        val body = "v=$id&t=${positionMs / 1000}".toRequestBody("text/plain; charset=utf-8".toMediaType())
        return runCatching {
            http.newCall(Request.Builder().url(tv.dialAppUrl + "YouTube").post(body).build()).execute().use { r ->
                Diag.log("cast", "YouTube app on ${tv.name}: HTTP ${r.code}")
                if (r.code in 200..299) { playingOn.value = tv.name; null }
                else tr("${tv.name} didn't open YouTube (${r.code}).", "${tv.name} åpnet ikke YouTube (${r.code}).")
            }
        }.getOrElse { tr("Couldn't reach ${tv.name}.", "Fikk ikke kontakt med ${tv.name}.") }
    }

    private fun soap(tv: Tv, action: String, args: String): Int {
        val env = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(tv.avControlUrl!!)
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .post(env.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) Diag.log("cast", "$action on ${tv.name}: HTTP ${r.code} ${r.body?.string()?.take(200)}")
            r.code
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private suspend fun dlnaPlay(tv: Tv, url: String, title: String, positionMs: Long): String? = runCatching {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:video/mp4:*\">${esc(url)}</res></item></DIDL-Lite>"
        runCatching { soap(tv, "Stop", "") }
        val set = soap(tv, "SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
        if (set !in 200..299) return tr("${tv.name} wouldn't take the video ($set).", "${tv.name} tok ikke imot videoen ($set).")
        val play = soap(tv, "Play", "<Speed>1</Speed>")
        if (play !in 200..299) return tr("${tv.name} wouldn't start it ($play).", "${tv.name} startet den ikke ($play).")
        Diag.log("cast", "playing on ${tv.name} (DLNA)")
        playingOn.value = tv.name
        if (positionMs > 5_000) {
            delay(3_000)
            val s = positionMs / 1000
            runCatching { soap(tv, "Seek", "<Unit>REL_TIME</Unit><Target>%d:%02d:%02d</Target>".format(s / 3600, s / 60 % 60, s % 60)) }
        }
        null
    }.getOrElse { tr("Couldn't reach ${tv.name}.", "Fikk ikke kontakt med ${tv.name}.") }

    suspend fun stop(tv: Tv) = withContext(Dispatchers.IO) {
        if (tv.dialAppUrl != null) runCatching { http.newCall(Request.Builder().url(tv.dialAppUrl + "YouTube/run").delete().build()).execute().close() }
        if (tv.avControlUrl != null) runCatching { soap(tv, "Stop", "") }
        playingOn.value = null
    }
}
