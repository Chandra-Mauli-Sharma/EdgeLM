package ai.edgelm.runtime

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Applies an EdgeLM binary delta to reconstruct a new model file from the resident one
 * (arch doc Part 10 — "Delta updates: a fine-tune bump downloads MBs, not GBs").
 *
 * Delta format `EDLT1` (produced server-side by Hub / tools/gen_delta.py):
 *   magic  : "EDLT1\n" (6 bytes)
 *   ops*   : one byte op then args (big-endian):
 *     'C' int64 offset, int64 length   -> copy [offset, offset+length) from the OLD file
 *     'A' int64 length, <length bytes> -> append that many NEW bytes verbatim
 *
 * Apply is a single streaming pass — constant memory even for multi-GB models. The
 * generation side (rolling-hash diff) lives off-device; this is the on-device applier.
 */
object BinaryPatch {

    private val MAGIC = "EDLT1\n".toByteArray(Charsets.US_ASCII)

    /** True if [f] looks like an EdgeLM delta (magic header). */
    fun isDelta(f: File): Boolean = runCatching {
        if (f.length() < MAGIC.size) return false
        f.inputStream().use { val b = ByteArray(MAGIC.size); it.read(b); b.contentEquals(MAGIC) }
    }.getOrDefault(false)

    /** Reconstruct [out] by applying [delta] to [old]. Throws on a malformed delta. */
    fun apply(old: File, delta: File, out: File) {
        RandomAccessFile(old, "r").use { oldRaf ->
            DataInputStream(BufferedInputStream(delta.inputStream())).use { din ->
                val magic = ByteArray(MAGIC.size); din.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "not an EdgeLM delta (bad magic)" }
                BufferedOutputStream(out.outputStream()).use { o ->
                    while (true) {
                        val op = din.read()
                        if (op < 0) break                    // clean EOF
                        when (op.toChar()) {
                            'C' -> copyFromOld(oldRaf, din.readLong(), din.readLong(), o)
                            'A' -> appendNew(din, din.readLong(), o)
                            else -> throw IllegalStateException("bad delta op byte: $op")
                        }
                    }
                }
            }
        }
    }

    private fun copyFromOld(raf: RandomAccessFile, offset: Long, length: Long, o: OutputStream) {
        raf.seek(offset)
        var remaining = length
        val buf = ByteArray(1 shl 16)
        while (remaining > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("delta copy past end of old file")
            o.write(buf, 0, n); remaining -= n
        }
    }

    private fun appendNew(din: DataInputStream, length: Long, o: OutputStream) {
        var remaining = length
        val buf = ByteArray(1 shl 16)
        while (remaining > 0) {
            val n = din.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("delta truncated in add op")
            o.write(buf, 0, n); remaining -= n
        }
    }
}
