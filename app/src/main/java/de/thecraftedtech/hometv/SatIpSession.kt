package de.thecraftedtech.hometv

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

/** Momentaufnahme der Messwerte einer SAT>IP-Session (alles echte Zaehler, keine Schaetzungen). */
data class NetStats(
    val packets: Long,          // empfangene RTP-Pakete
    val bytes: Long,            // empfangene TS-Nutzdaten
    val lost: Long,             // endgueltig fehlende RTP-Pakete (Sequenzluecken)
    val reordered: Long,        // rechtzeitig einsortierte, vertauschte Pakete
    val lateOrDuplicate: Long,  // zu spaet/doppelt eingetroffen, verworfen
    val tsPackets: Long,        // an den Player gelieferte TS-Pakete
    val tsCcErrors: Long,       // Continuity-Counter-Fehler im gelieferten Strom (= fehlende TS-Pakete)
    val tsBadSync: Long,
    val jitterMs: Double,       // RFC-3550-Jitter (Ankunft gegen RTP-Zeitstempel, 90 kHz)
    val rcvBufBytes: Int,       // tatsaechlich gewaehrter UDP-Empfangspuffer
    val dataTimeMs: Long,       // PCR-Datenzeit seit Start
    val feedQueued: Long,       // Bytes, die auf LibVLC warten
    val feedOverflow: Long,     // Bytes, die wegen vollem App-Puffer verworfen wurden
    val msSinceLastPacket: Long,
)

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
 * in Sequenzreihenfolge bringen, RTP-Header entfernen und den MPEG-TS-Inhalt an den [LocalFeed] (-> LibVLC) geben.
 */
class SatIpSession(
    private val rtspUrl: String,
    private val feed: LocalFeed,
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

    private val packetsRx = AtomicLong()
    private val bytesRx = AtomicLong()
    @Volatile private var lastPacketAtMs = 0L
    @Volatile private var jitterMs = 0.0
    @Volatile private var rcvBuf = 0
    private val maxGapWindowMs = AtomicLong()

    private val inspector = TsInspector()

    // Start-Gate: Messungen (docs/stream-analysis.md) zeigen, dass der Stream-Start der FRITZ!Box (6 von 10 gemessenen Starts) in den ersten ~1 s
    // fehlende TS-Pakete enthaelt (Continuity-Fehler bei ~0 s und ~0,93 s nach dem ersten PCR), danach 0. Damit der Decoder
    // nicht auf einem beschaedigten ersten GOP beginnt, wird erst weitergereicht, wenn die Startphase ruhig ist:
    // mindestens GATE_MIN_MS seit dem ersten Datenpaket UND GATE_QUIET_MS ohne TS-Fehler, spaetestens nach GATE_MAX_MS.
    private var gateOpen = false
    private var gateFirstAtMs = 0L
    private var gateLastErrAtMs = 0L
    @Volatile var gateOpenedAfterMs = -1L; private set
    private val gateDropped = AtomicLong()
    val gateDroppedBytes: Long get() = gateDropped.get()

    private val resequencer = RtpResequencer { b, o, l ->
        val errBefore = inspector.ccErrors
        inspector.inspect(b, o, l)
        val now = System.currentTimeMillis()
        if (!gateOpen) {
            if (gateFirstAtMs == 0L) gateFirstAtMs = now
            if (inspector.ccErrors != errBefore) gateLastErrAtMs = now
            val age = now - gateFirstAtMs
            if ((age >= GATE_MIN_MS && now - gateLastErrAtMs >= GATE_QUIET_MS) || age >= GATE_MAX_MS) {
                gateOpen = true
                gateOpenedAfterMs = age
                AppLog.i(tag, "Start-Gate offen nach $age ms (verworfen: ${gateDropped.get() / 1024} KiB, TS-Fehler in der Startphase: ${inspector.ccErrors})")
            }
        }
        if (gateOpen) feed.write(b, o, l) else gateDropped.addAndGet(l.toLong())
    }

    val lastPacketAgoMs: Long get() = if (lastPacketAtMs == 0L) -1 else System.currentTimeMillis() - lastPacketAtMs

    fun stats() = NetStats(
        packets = packetsRx.get(), bytes = bytesRx.get(), lost = resequencer.lost, reordered = resequencer.reordered,
        lateOrDuplicate = resequencer.lateOrDuplicate, tsPackets = inspector.tsPackets, tsCcErrors = inspector.ccErrors,
        tsBadSync = inspector.badSync, jitterMs = jitterMs, rcvBufBytes = rcvBuf, dataTimeMs = inspector.dataTimeMs,
        feedQueued = feed.queuedBytes, feedOverflow = feed.overflowBytes,
        msSinceLastPacket = if (lastPacketAtMs == 0L) -1 else System.currentTimeMillis() - lastPacketAtMs,
    )

    /** Groesste Ankunftsluecke seit dem letzten Aufruf (ms). */
    fun takeMaxGapMs(): Long = maxGapWindowMs.getAndSet(0)

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
                AppLog.e(tag, "Session-Fehler", e)
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
            rtp.soTimeout = 50
            // Android begrenzt den Wunsch auf net.core.rmem_max; der tatsaechliche Wert wird gemessen und angezeigt.
            runCatching { rtp.receiveBufferSize = 4 * 1024 * 1024 }
            rcvBuf = runCatching { rtp.receiveBufferSize }.getOrDefault(0)
            AppLog.i(tag, "UDP-Empfangspuffer: $rcvBuf Bytes (angefordert 4194304), Port ${rtp.localPort}")
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
        // Der Empfang darf nie hinter Decoder/UI zurueckfallen: sonst laeuft der Socket-Puffer ueber (= Bildfehler).
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val rtp = rtpSocket ?: return
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        var first = true
        var prevArrivalMs = 0.0
        var prevRtpTs = 0L
        var haveTs = false
        try {
            while (running.get()) {
                try {
                    pkt.length = buf.size
                    rtp.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    resequencer.tick(System.currentTimeMillis())
                    continue
                }
                val nowNs = System.nanoTime()
                val nowMs = System.currentTimeMillis()
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
                val rtpTs = ((buf[4].toLong() and 0xFF) shl 24) or ((buf[5].toLong() and 0xFF) shl 16) or
                    ((buf[6].toLong() and 0xFF) shl 8) or (buf[7].toLong() and 0xFF)

                // Ankunftsstatistik: Jitter nach RFC 3550 (Ankunft gegen RTP-Zeitstempel, 90 kHz) und groesste Luecke
                val arrivalMs = nowNs / 1_000_000.0
                if (haveTs) {
                    val gap = (arrivalMs - prevArrivalMs).toLong()
                    if (gap > maxGapWindowMs.get()) maxGapWindowMs.set(gap)
                    val dTs = ((rtpTs - prevRtpTs) and 0xFFFFFFFFL).toDouble() / 90.0
                    val d = Math.abs((arrivalMs - prevArrivalMs) - dTs)
                    if (d < 10_000) jitterMs += (d - jitterMs) / 16.0
                }
                prevArrivalMs = arrivalMs; prevRtpTs = rtpTs; haveTs = true

                packetsRx.incrementAndGet()
                bytesRx.addAndGet(payload.toLong())
                lastPacketAtMs = nowMs
                if (first) {
                    first = false
                    listener.onStreaming()
                }
                resequencer.push(seq, buf, off, payload, nowMs)
            }
        } catch (e: Exception) {
            if (running.get()) AppLog.w(tag, "RTP-Empfang beendet: ${e.message}")
        }
    }

    private fun teardown() {
        val s = ctrl ?: return
        if (session == null || s.isClosed) return
        try {
            s.soTimeout = 1500
            request("TEARDOWN", playUrl.ifEmpty { rtspUrl })
            AppLog.i(tag, "TEARDOWN gesendet")
        } catch (e: Exception) {
            AppLog.w(tag, "TEARDOWN fehlgeschlagen: ${e.message}")
        }
    }

    companion object {
        const val GATE_MIN_MS = 1200L
        const val GATE_QUIET_MS = 500L
        const val GATE_MAX_MS = 3000L
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
        sb.append("$method $target RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: HomeTV/1.1\r\n")
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
