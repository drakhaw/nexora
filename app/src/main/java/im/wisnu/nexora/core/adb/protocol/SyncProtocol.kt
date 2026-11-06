package im.wisnu.nexora.core.adb.protocol

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The target answered FAIL. [remoteMessage] comes from adbd, e.g. "open failed: Permission denied". */
class SyncFailException(message: String) : IOException(message) {
    // Message doubles as the remote text so coroutine stack-trace recovery copies keep it.
    val remoteMessage: String get() = message.orEmpty()
}

/**
 * ADB sync protocol v1 (AOSP adb `file_sync_protocol.h`), library-independent.
 * Runs over one `sync:` stream. Each request: 4-byte ASCII id, 4-byte
 * little-endian length, payload.
 */
class SyncClient(private val input: InputStream, private val output: OutputStream) {

    /**
     * Uploads [source] to [remotePath] (SEND/DATA/DONE). [mode] holds permission
     * bits; the regular-file type bit is added as the adb client does.
     * [onChunk] receives the cumulative byte count after each chunk.
     */
    fun send(source: InputStream, remotePath: String, mode: Int, mtimeSeconds: Int, onChunk: (Long) -> Unit) {
        validatePath(remotePath)
        writeRequest(ID_SEND, "$remotePath,${S_IFREG or (mode and 0x1FF)}".toByteArray(Charsets.UTF_8))
        val buf = ByteArray(DATA_MAX)
        var total = 0L
        while (true) {
            val n = source.read(buf)
            if (n < 0) break
            if (n == 0) continue
            writeRequest(ID_DATA, buf, n)
            total += n
            onChunk(total)
        }
        writeHeader(ID_DONE, mtimeSeconds)
        output.flush()

        val header = ByteArray(8)
        readFully(input, header)
        when (idOf(header)) {
            ID_OKAY -> Unit
            ID_FAIL -> throw SyncFailException(readMessage(lengthOf(header)))
            else -> throw IOException("sync: unexpected reply to SEND")
        }
    }

    /** Downloads [remotePath] into [sink] (RECV). */
    fun recv(remotePath: String, sink: OutputStream, onChunk: (Long) -> Unit) {
        validatePath(remotePath)
        writeRequest(ID_RECV, remotePath.toByteArray(Charsets.UTF_8))
        output.flush()
        val header = ByteArray(8)
        val buf = ByteArray(DATA_MAX)
        var total = 0L
        while (true) {
            readFully(input, header)
            val length = lengthOf(header)
            when (idOf(header)) {
                ID_DATA -> {
                    if (length < 0 || length > DATA_MAX) throw IOException("sync: bad DATA length")
                    readFully(input, buf, length)
                    sink.write(buf, 0, length)
                    total += length
                    onChunk(total)
                }
                ID_DONE -> return
                ID_FAIL -> throw SyncFailException(readMessage(length))
                else -> throw IOException("sync: unexpected reply to RECV")
            }
        }
    }

    /** Lists a directory (LIST). Entries "." and ".." are dropped. */
    fun list(remotePath: String, maxEntries: Int = 20_000): List<im.wisnu.nexora.core.adb.RemoteFile> {
        validatePath(remotePath)
        writeRequest(ID_LIST, remotePath.toByteArray(Charsets.UTF_8))
        output.flush()
        val dent = ByteArray(20) // id, mode, size, mtime, namelen
        val entries = mutableListOf<im.wisnu.nexora.core.adb.RemoteFile>()
        while (true) {
            readFully(input, dent)
            val b = ByteBuffer.wrap(dent).order(ByteOrder.LITTLE_ENDIAN)
            when (b.int) {
                ID_DENT -> {
                    val mode = b.int
                    val size = b.int.toLong() and 0xFFFFFFFFL
                    val mtime = b.int.toLong() and 0xFFFFFFFFL
                    val nameLen = b.int
                    if (nameLen < 0 || nameLen > PATH_MAX) throw IOException("sync: bad DENT name length")
                    val name = ByteArray(nameLen).also { readFully(input, it) }.toString(Charsets.UTF_8)
                    if (name != "." && name != "..") {
                        entries += im.wisnu.nexora.core.adb.RemoteFile(name, mode, size, mtime)
                        if (entries.size > maxEntries) throw IOException("sync: too many entries")
                    }
                }
                ID_DONE -> return entries
                ID_FAIL -> throw SyncFailException("list failed")
                else -> throw IOException("sync: unexpected reply to LIST")
            }
        }
    }

    fun quit() {
        writeHeader(ID_QUIT, 0)
        output.flush()
    }

    private fun readMessage(length: Int): String {
        if (length < 0 || length > MSG_MAX) throw IOException("sync: bad FAIL length")
        val msg = ByteArray(length)
        readFully(input, msg)
        return String(msg, Charsets.UTF_8)
    }

    private fun writeRequest(id: Int, payload: ByteArray, length: Int = payload.size) {
        // Header and payload in one buffer so they travel in as few packets as possible.
        val packet = ByteBuffer.allocate(8 + length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(id).putInt(length).put(payload, 0, length).array()
        output.write(packet)
    }

    private fun writeHeader(id: Int, value: Int) {
        output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(value).array())
    }

    private fun validatePath(path: String) {
        require(path.startsWith("/")) { "remote path must be absolute" }
        require(path.toByteArray(Charsets.UTF_8).size <= PATH_MAX) { "remote path too long" }
    }

    companion object {
        const val DATA_MAX = 64 * 1024
        private const val PATH_MAX = 1024
        private const val MSG_MAX = 64 * 1024
        private const val S_IFREG = 0x8000 // 0100000

        private fun id(s: String): Int =
            ByteBuffer.wrap(s.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int

        val ID_SEND = id("SEND")
        val ID_RECV = id("RECV")
        val ID_DATA = id("DATA")
        val ID_DONE = id("DONE")
        val ID_OKAY = id("OKAY")
        val ID_FAIL = id("FAIL")
        val ID_QUIT = id("QUIT")
        val ID_LIST = id("LIST")
        val ID_DENT = id("DENT")

        private fun idOf(h: ByteArray) = ByteBuffer.wrap(h, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        private fun lengthOf(h: ByteArray) = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}

private fun readFully(input: InputStream, buf: ByteArray, length: Int) {
    var off = 0
    while (off < length) {
        val n = input.read(buf, off, length - off)
        if (n < 0) throw java.io.EOFException("sync: stream ended")
        off += n
    }
}
