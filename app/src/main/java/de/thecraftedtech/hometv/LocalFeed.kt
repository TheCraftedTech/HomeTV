package de.thecraftedtech.hometv

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lokaler Uebergabeweg SatIpSession -> LibVLC (nur Loopback, kein zusaetzlicher Server nach aussen).
 *
 *  - [UdpFeed]: Datagramme an udp://@127.0.0.1:port. Einfach, aber verlustbehaftet: liest LibVLC kurz nicht
 *    (Decoder-Start, GC, CPU-Last), laeuft der Socket-Puffer (~0,2-0,4 s) ueber und Pakete gehen verloren.
 *  - [TcpFeed]: LibVLC verbindet sich per tcp://127.0.0.1:port. Verlustfrei mit Rueckstau: ein elastischer
 *    Puffer in der App faengt Burst/Stau ab (Ueberlauf wird gezaehlt, nicht verschwiegen).
 */
interface LocalFeed {
    /** Adresse, die LibVLC oeffnen soll (z. B. "tcp://127.0.0.1:41234"). */
    val url: String
    fun start()
    fun write(buf: ByteArray, off: Int, len: Int)
    fun close()
    /** Bytes, die auf die Weitergabe an LibVLC warten. */
    val queuedBytes: Long
    /** Bytes, die wegen vollem Puffer verworfen wurden (sollte 0 bleiben). */
    val overflowBytes: Long
    /** Ob LibVLC bereits verbunden ist (UDP: immer true). */
    val consumerConnected: Boolean
    val name: String
}

class UdpFeed : LocalFeed {
    private val socket = DatagramSocket()
    private val port = DatagramSocket(0).use { it.localPort }
    private val local = InetAddress.getByName("127.0.0.1")
    override val url get() = "udp://@127.0.0.1:$port"
    override val name = "UDP-Loopback"
    override val queuedBytes = 0L
    override val overflowBytes = 0L
    override val consumerConnected = true
    override fun start() { runCatching { socket.sendBufferSize = 1 shl 20 } }
    override fun write(buf: ByteArray, off: Int, len: Int) {
        try { socket.send(DatagramPacket(buf, off, len, local, port)) } catch (_: IOException) { }
    }
    override fun close() { socket.close() }
}

class TcpFeed(private val capacityBytes: Int = 24 * 1024 * 1024) : LocalFeed {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queued = AtomicLong()
    private val dropped = AtomicLong()
    private val running = AtomicBoolean(true)
    @Volatile private var client: Socket? = null
    private var thread: Thread? = null

    override val url get() = "tcp://127.0.0.1:${server.localPort}"
    override val name = "TCP-Loopback"
    override val queuedBytes get() = queued.get()
    override val overflowBytes get() = dropped.get()
    override val consumerConnected get() = client != null

    override fun start() {
        thread = Thread({ serve() }, "feed-tcp").also { it.isDaemon = true; it.start() }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        if (queued.get() + len > capacityBytes) { dropped.addAndGet(len.toLong()); return }
        queued.addAndGet(len.toLong())
        queue.offer(buf.copyOfRange(off, off + len))
    }

    private fun serve() {
        try {
            val s = server.accept() // wartet, bis LibVLC sich verbindet
            s.tcpNoDelay = true
            runCatching { s.sendBufferSize = 1 shl 20 }
            client = s
            val out = s.getOutputStream()
            val batch = ArrayList<ByteArray>(64)
            val tmp = ByteArray(64 * 1316)
            while (running.get()) {
                val first = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                batch.clear(); batch.add(first)
                queue.drainTo(batch, 47)
                var n = 0
                for (b in batch) { System.arraycopy(b, 0, tmp, n, b.size); n += b.size }
                out.write(tmp, 0, n)           // blockiert bei langsamem LibVLC -> Rueckstau in die Queue
                queued.addAndGet(-n.toLong())
            }
        } catch (_: IOException) {
            // LibVLC hat die Verbindung geschlossen (Senderwechsel/Stop) – normal
        } finally {
            runCatching { client?.close() }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        runCatching { client?.close() }
        queue.clear()
    }
}
