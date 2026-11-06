package im.wisnu.nexora.core.adb.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Returns at most one byte per read, like a stream split across many ADB packets. */
internal class TrickleInputStream(bytes: ByteArray) : InputStream() {
    private val inner = ByteArrayInputStream(bytes)
    override fun read(): Int = inner.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else inner.read(b, off, 1)
}

class ShellV2Test {

    private fun stream(vararg packets: ByteArray) = packets.fold(ByteArray(0)) { acc, p -> acc + p }

    @Test fun `splits stdout stderr and exit code`() {
        val bytes = stream(
            ShellV2.packet(ShellV2.ID_STDOUT, "hello ".toByteArray()),
            ShellV2.packet(ShellV2.ID_STDERR, "warn".toByteArray()),
            ShellV2.packet(ShellV2.ID_STDOUT, "world".toByteArray()),
            ShellV2.packet(ShellV2.ID_EXIT, byteArrayOf(2)),
        )
        val out = ShellV2.read(TrickleInputStream(bytes), 1024)
        assertEquals("hello world", out.stdout.decodeToString())
        assertEquals("warn", out.stderr.decodeToString())
        assertEquals(2, out.exitCode)
        assertEquals(4, out.packets)
    }

    @Test fun `exit code above 127 is unsigned`() {
        val out = ShellV2.read(ByteArrayInputStream(ShellV2.packet(ShellV2.ID_EXIT, byteArrayOf(0xFF.toByte()))), 16)
        assertEquals(255, out.exitCode)
    }

    @Test fun `payload byte 0xFF survives`() {
        val bytes = stream(ShellV2.packet(ShellV2.ID_STDOUT, byteArrayOf(0xFF.toByte(), 0)), ShellV2.packet(3, byteArrayOf(0)))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0), ShellV2.read(TrickleInputStream(bytes), 16).stdout)
    }

    @Test fun `empty stream means no reply`() {
        val out = ShellV2.read(ByteArrayInputStream(ByteArray(0)), 16)
        assertNull(out.exitCode)
        assertEquals(0, out.packets)
    }

    @Test(expected = EOFException::class) fun `truncated packet fails`() {
        ShellV2.read(ByteArrayInputStream(ShellV2.packet(ShellV2.ID_STDOUT, "abc".toByteArray()).copyOf(6)), 16)
    }

    @Test(expected = IOException::class) fun `output cap enforced`() {
        ShellV2.read(ByteArrayInputStream(ShellV2.packet(ShellV2.ID_STDOUT, ByteArray(32))), 16)
    }

    @Test(expected = IOException::class) fun `negative length rejected`() {
        ShellV2.read(ByteArrayInputStream(byteArrayOf(1, -1, -1, -1, -1)), 16)
    }

    @Test fun `close stdin packet layout`() {
        assertArrayEquals(byteArrayOf(4, 0, 0, 0, 0), ShellV2.closeStdinPacket())
    }

    @Test fun `max data stream never writes more than limit per call`() {
        val sizes = mutableListOf<Int>()
        val sink = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                sizes += len
                super.write(b, off, len)
            }
        }
        MaxDataOutputStream(sink, 4).write(ByteArray(10) { it.toByte() })
        assertEquals(listOf(4, 4, 2), sizes)
        assertArrayEquals(ByteArray(10) { it.toByte() }, sink.toByteArray())
    }
}
