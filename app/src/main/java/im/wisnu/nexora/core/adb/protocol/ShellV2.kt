package im.wisnu.nexora.core.adb.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB shell protocol v2 (AOSP adb `shell_protocol.h`), library-independent.
 *
 * Each packet: 1-byte id, 4-byte little-endian length, payload. The `raw`
 * (non-PTY) variant keeps stdout and stderr apart and ends with an exit packet.
 */
object ShellV2 {
    const val ID_STDIN = 0
    const val ID_STDOUT = 1
    const val ID_STDERR = 2
    const val ID_EXIT = 3
    const val ID_CLOSE_STDIN = 4

    private const val HEADER_SIZE = 5
    private const val MAX_PACKET = 1024 * 1024

    fun service(script: String): String = "shell,v2,raw:$script"

    /** Packet telling adbd the command gets no stdin, so commands that read stdin end. */
    fun closeStdinPacket(): ByteArray = packet(ID_CLOSE_STDIN, ByteArray(0))

    fun packet(id: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByte()).putInt(payload.size).put(payload).array()

    class Output(val stdout: ByteArray, val stderr: ByteArray, val exitCode: Int?, val packets: Int)

    /**
     * Reads packets until the exit packet or end of stream. [maxBytes] caps
     * stdout plus stderr so a malformed or endless reply cannot exhaust memory.
     */
    fun read(input: InputStream, maxBytes: Int): Output {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val header = ByteArray(HEADER_SIZE)
        var packets = 0
        while (true) {
            if (!readFullyOrEof(input, header)) {
                return Output(stdout.toByteArray(), stderr.toByteArray(), exitCode = null, packets = packets)
            }
            packets++
            val id = header[0].toInt() and 0xFF
            val length = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || length > MAX_PACKET) throw IOException("shell v2: bad packet length")
            val payload = ByteArray(length)
            if (!readFullyOrEof(input, payload)) throw EOFException("shell v2: truncated packet")
            when (id) {
                ID_STDOUT -> stdout.write(payload)
                ID_STDERR -> stderr.write(payload)
                ID_EXIT -> {
                    val code = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else null
                    return Output(stdout.toByteArray(), stderr.toByteArray(), code, packets)
                }
                else -> Unit // Ignore ids a client never receives (stdin, window size).
            }
            if (stdout.size() + stderr.size() > maxBytes) throw IOException("shell output exceeds limit")
        }
    }
}

/**
 * Fills [buf] completely. Returns false on a clean end of stream before the
 * first byte; throws [EOFException] if the stream ends part-way.
 */
internal fun readFullyOrEof(input: InputStream, buf: ByteArray): Boolean {
    var off = 0
    while (off < buf.size) {
        val n = input.read(buf, off, buf.size - off)
        if (n < 0) {
            if (off == 0) return false
            throw EOFException("stream ended mid-packet")
        }
        off += n
    }
    return true
}

internal fun readFully(input: InputStream, buf: ByteArray) {
    if (!readFullyOrEof(input, buf)) throw EOFException("stream ended")
}

/**
 * Splits writes into chunks no larger than [maxData], one write call each.
 * Needed because libadb's AdbStream.write sends several WRTE packets for a
 * large buffer without waiting for OKAY between them, which adbd rejects.
 */
class MaxDataOutputStream(private val out: OutputStream, private val maxData: Int) : OutputStream() {
    init {
        require(maxData > 0)
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var remaining = len
        while (remaining > 0) {
            val n = minOf(remaining, maxData)
            out.write(b, o, n)
            o += n
            remaining -= n
        }
    }

    override fun flush() = out.flush()
}
