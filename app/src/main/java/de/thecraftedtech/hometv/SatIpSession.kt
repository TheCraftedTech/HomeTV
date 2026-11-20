package de.thecraftedtech.hometv

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimaler SAT>IP-/RTSP-Client fuer die FRITZ!Box (gemessen in docs/stream-analysis.md).
 *
 * Warum ein eigener Client statt LibVLC direkt auf rtsp://?
 *  - Die Box akzeptiert nur RTP ueber UDP (TCP-interleaved -> "461").
 *  - Der Router vor der Box verwirft unaufgefordertes UDP: ohne ein kleines "Punch"-Paket vom
 *    Client-RTP-Port an den Server-RTP-Port kommen 0 Pakete an (gemessen), mit Punch ~10 Mbit/s.
 *  - Session-Timeout 60 s: Keep-Alive per OPTIONS (GET_PARAMETER wird nicht angeboten).
 *  - Sauberes TEARDOWN ist wichtig, verwaiste Sessions stoerten die Box in den Tests.
 *
 * Ablauf: OPTIONS -> DESCRIBE -> SETUP (UDP) -> Punch -> PLAY rtsp://host/stream=ID -> RTP empfangen,
 * RTP-Header entfernen und den MPEG-TS-Inhalt per Loopback-UDP an den lokalen Player weiterreichen.
 */
class SatIpSession(
    private val rtspUrl: String,
    private val localTsPort: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(text: String)
        fun onStreaming()
        fun onError(text: String)
    }

    private val tag = "HomeTV/SatIP"
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    // Statistik fuer das Info-Overlay
    val bytesReceived = AtomicLong()
    val packetsReceived = AtomicLong()
    val packetsLost = AtomicLong()
    @Volatile var lastPacketAtMs: Long = 0L
        private set

    private lateinit var host: String
    private var port = 554
    private var ctrl: Socket? = null
    private var cseq = 0
    private var session: String? = null
    private var sessionTimeoutSec = 60
    private var playUrl: String = ""
    private var rtpSocket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null
    private var serverRtpPort = 0
    private var serverRtcpPort = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ run() }, "satip-session").also { it.start() }
    }

    /** Beendet die Session. Fuehrt TEARDOWN aus und wartet hoechstens [timeoutMs] auf den Abschluss. */
    fun stop(timeoutMs: Long = 2000) {
        running.set(false)
        val t = worker ?: return
        try {
            t.join(timeoutMs)
        } catch (_: InterruptedException) {
        }
    }

    private fun run() {
        var receiver: Thread? = null
        try {
            val uri = URI(rtspUrl)
            host = uri.host ?: throw IOException("Ungültige RTSP-Adresse")
            port = if (uri.port > 0) uri.port else 554

            listener.onState("Verbinde mit $host …")
            // Achtung: kein apply{} – darin wuerde "port" zu Socket.getPort() (=0) aufgeloest.
            val control = Socket()
            control.tcpNoDelay = true
            control.soTimeout = 6000
            ctrl = control
            control.connect(InetSocketAddress(host, this.port), 5000)

            request("OPTIONS", rtspUrl).requireOk("OPTIONS")
            request("DESCRIBE", rtspUrl, "Accept: application/sdp\r\n").requireOk("DESCRIBE")

            openUdpPair()
            val rtpPort = rtpSocket!!.localPort
            val setup = request(
                "SETUP", rtspUrl,
                "Transport: RTP/AVP;unicast;client_port=$rtpPort-${rtpPort + 1}\r\n",
            )
            setup.requireOk("SETUP")
            parseSession(setup.header("session"))
            val transport = setup.header("transport") ?: ""
            Regex("server_port=(\\d+)(?:-(\\d+))?").find(transport)?.let {
                serverRtpPort = it.groupValues[1].toInt()
                serverRtcpPort = it.groupValues[2].toIntOrNull() ?: (serverRtpPort + 1)
            }
            if (serverRtpPort == 0) throw IOException("SETUP-Antwort ohne server_port")
            val streamId = setup.header("com.ses.streamid")
            playUrl = if (streamId != null) "rtsp://$host:$port/stream=$streamId" else rtspUrl

            punch() // NAT/Firewall-Rueckpfad oeffnen, bevor die Box sendet

            receiver = Thread({ receiveLoop() }, "satip-rtp").also { it.start() }
            request("PLAY", playUrl).requireOk("PLAY")
            listener.onState("Warte auf Daten …")

            // Haupt-Schleife: Punch wiederholen, Keep-Alive senden
            val keepAliveMs = (sessionTimeoutSec * 1000L / 2).coerceIn(5_000, 30_000)
            var lastKeepAlive = System.currentTimeMillis()
            var lastPunch = lastKeepAlive
            while (running.get()) {
                Thread.sleep(250)
                val now = System.currentTimeMillis()
                if (now - lastPunch >= 5_000) {
                    punch(); lastPunch = now
                }
                if (now - lastKeepAlive >= keepAliveMs) {
                    request("OPTIONS", playUrl).requireOk("Keep-Alive")
                    lastKeepAlive = now
                }
            }
        } catch (e: InterruptedException) {
            // normales Beenden
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(tag, "Session-Fehler", e)
                listener.onError(describe(e))
            }
        } finally {
            running.set(false)
            teardown()
            receiver?.let { runCatching { it.join(1000) } }
            runCatching { rtpSocket?.close() }
            runCatching { rtcpSocket?.close() }
            runCatching { ctrl?.close() }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "Zeitüberschreitung bei der Verbindung zur FRITZ!Box ($host:$port)."
        is java.net.ConnectException -> "Die FRITZ!Box ($host:$port) lehnt die Verbindung ab oder ist nicht erreichbar."
        is RtspException -> e.message ?: "RTSP-Fehler"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun openUdpPair() {
        repeat(30) {
            val rtp = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(0)) }
            if (rtp.localPort % 2 != 0) { rtp.close(); return@repeat }
            val rtcp = try {
                DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(rtp.localPort + 1)) }
            } catch (_: Exception) { rtp.close(); return@repeat }
            rtp.soTimeout = 1000
            runCatching { rtp.receiveBufferSize = 2 * 1024 * 1024 }
            rtpSocket = rtp
            rtcpSocket = rtcp
            return
        }
        throw IOException("Kein freies UDP-Portpaar für RTP/RTCP")
    }

    /** Kleines UDP-Paket in Richtung Server (wie ffmpeg), damit NAT/Firewall den Rueckweg erlaubt. */
    private fun punch() {
        val addr = InetAddress.getByName(host)
        val dummy = byteArrayOf(0xCE.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte())
        runCatching { rtpSocket?.send(DatagramPacket(dummy, dummy.size, addr, serverRtpPort)) }
        runCatching { rtcpSocket?.send(DatagramPacket(dummy, dummy.size, addr, serverRtcpPort)) }
    }

    private fun receiveLoop() {
        val rtp = rtpSocket ?: return
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        val out = DatagramSocket()
        val local = InetAddress.getByName("127.0.0.1")
        var lastSeq = -1
        var first = true
        try {
            while (running.get()) {
                try {
                    pkt.length = buf.size
                    rtp.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val len = pkt.length
                if (len < 12 || ((buf[0].toInt() shr 6) and 3) != 2) continue // kein RTP v2
                val cc = buf[0].toInt() and 0x0F
                val hasExt = (buf[0].toInt() and 0x10) != 0
                val hasPad = (buf[0].toInt() and 0x20) != 0
                var off = 12 + cc * 4
                if (hasExt) {
                    if (len < off + 4) continue
                    val extWords = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
                    off += 4 + extWords * 4
                }
                val pad = if (hasPad) buf[len - 1].toInt() and 0xFF else 0
                val payload = len - off - pad
                if (payload <= 0) continue

                val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                if (lastSeq >= 0) {
                    val diff = (seq - lastSeq) and 0xFFFF
                    if (diff in 2..1000) packetsLost.addAndGet((diff - 1).toLong())
                }
                lastSeq = seq

                packetsReceived.incrementAndGet()
                bytesReceived.addAndGet(payload.toLong())
                lastPacketAtMs = System.currentTimeMillis()
                if (first) {
                    first = false
                    listener.onStreaming()
                }
                // MPEG-TS (Vielfache von 188 Byte) unveraendert an den lokalen Player
                out.send(DatagramPacket(buf, off, payload, local, localTsPort))
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(tag, "RTP-Empfang beendet", e)
        } finally {
            out.close()
        }
    }

    private fun teardown() {
        val s = ctrl ?: return
        if (session == null || s.isClosed) return
        try {
            s.soTimeout = 1500
            request("TEARDOWN", playUrl.ifEmpty { rtspUrl })
            Log.i(tag, "TEARDOWN gesendet")
        } catch (e: Exception) {
            Log.w(tag, "TEARDOWN fehlgeschlagen: ${e.message}")
        }
    }

    // ---------- RTSP-Grundfunktionen ----------

    private class RtspException(message: String) : IOException(message)

    private class Response(val status: Int, val reason: String, val headers: Map<String, String>) {
        fun header(name: String) = headers[name.lowercase()]
        fun requireOk(what: String): Response {
            if (status != 200) throw RtspException(
                when (status) {
                    453, 503 -> "Die FRITZ!Box hat gerade keinen freien Tuner/Stream (RTSP $status bei $what)."
                    404 -> "Sender wird von der FRITZ!Box nicht gefunden (RTSP 404 bei $what)."
                    else -> "FRITZ!Box antwortete mit RTSP $status $reason bei $what."
                },
            )
            return this
        }
    }

    private fun parseSession(value: String?) {
        if (value == null) return
        session = value.substringBefore(';').trim()
        Regex("timeout=(\\d+)").find(value)?.let { sessionTimeoutSec = it.groupValues[1].toInt() }
    }

    private fun request(method: String, target: String, extra: String = ""): Response {
        val s = ctrl ?: throw IOException("keine Steuerverbindung")
        cseq++
        val sb = StringBuilder()
        sb.append("$method $target RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: HomeTV/1.0\r\n")
        session?.let { sb.append("Session: $it\r\n") }
        sb.append(extra).append("\r\n")
        s.getOutputStream().apply { write(sb.toString().toByteArray(Charsets.US_ASCII)); flush() }
        return readResponse(s)
    }

    private fun readResponse(s: Socket): Response {
        val input = s.getInputStream()
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("Verbindung von der FRITZ!Box geschlossen")
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (head.size() > 16_384) throw IOException("RTSP-Antwort zu groß")
        }
        val lines = head.toString("US-ASCII").split("\r\n").filter { it.isNotEmpty() }
        val status = lines.first().split(' ', limit = 3)
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        headers["content-length"]?.toIntOrNull()?.let { n ->
            var left = n
            val tmp = ByteArray(1024)
            while (left > 0) {
                val r = input.read(tmp, 0, minOf(left, tmp.size))
                if (r < 0) break
                left -= r
            }
        }
        return Response(status.getOrNull(1)?.toIntOrNull() ?: -1, status.getOrNull(2) ?: "", headers)
    }
}
