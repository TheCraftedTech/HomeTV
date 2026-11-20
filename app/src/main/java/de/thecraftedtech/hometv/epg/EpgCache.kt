package de.thecraftedtech.hometv.epg

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Lokaler Zwischenspeicher der Programmdaten (kompaktes Binaerformat). Geschrieben wird in eine Temp-Datei und
 * danach atomar umbenannt – ein abgebrochener Schreibvorgang beschaedigt nie den vorhandenen Cache.
 */
object EpgCache {
    private const val MAGIC = "HTEPG1"

    fun write(file: File, data: EpgData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { o ->
            o.writeUTF(MAGIC)
            val m = data.meta
            o.writeLong(m.createdAt)
            o.writeUTF(m.sourceUrl)
            writeNullable(o, m.etag)
            writeNullable(o, m.lastModified)
            o.writeInt(m.programmeCount); o.writeInt(m.matchedChannels); o.writeInt(m.totalChannels); o.writeInt(m.xmltvChannels)
            val keys = data.channelKeys.toList()
            o.writeInt(keys.size)
            for (k in keys) {
                val ps = data.programmes(k)
                o.writeUTF(k)
                o.writeInt(ps.size)
                for (p in ps) {
                    o.writeLong(p.start); o.writeLong(p.end)
                    o.writeUTF(p.title)
                    writeNullable(o, p.desc)
                }
            }
        }
        if (file.exists() && !file.delete()) throw IOException("Alter Cache nicht ersetzbar")
        if (!tmp.renameTo(file)) throw IOException("Cache konnte nicht gespeichert werden")
    }

    /** Liest den Cache; bei fehlender/defekter Datei null (der Aufrufer faellt dann auf "keine Daten" zurueck). */
    fun read(file: File): EpgData? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { i ->
                if (i.readUTF() != MAGIC) return null
                val created = i.readLong()
                val url = i.readUTF()
                val etag = readNullable(i)
                val lastMod = readNullable(i)
                val meta = EpgMeta(created, url, etag, lastMod, i.readInt(), i.readInt(), i.readInt(), i.readInt())
                val n = i.readInt()
                val map = HashMap<String, Array<Programme>>(n * 2)
                repeat(n) {
                    val key = i.readUTF()
                    val cnt = i.readInt()
                    if (cnt < 0 || cnt > 1_000_000) throw IOException("ungueltig")
                    map[key] = Array(cnt) { Programme(i.readLong(), i.readLong(), i.readUTF(), readNullable(i)) }
                }
                EpgData(meta, map)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeNullable(o: DataOutputStream, s: String?) {
        o.writeBoolean(s != null)
        if (s != null) o.writeUTF(s)
    }

    private fun readNullable(i: DataInputStream): String? = if (i.readBoolean()) i.readUTF() else null
}
