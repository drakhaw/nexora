package im.wisnu.nexora.core.adb.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SyncClientTest {

    private fun msg(id: String, length: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(length).put(payload).array()

    private fun data(payload: ByteArray) = msg("DATA", payload.size, payload)

    /** Parses client requests back into (id, length, payload) triples. */
    private fun requests(bytes: ByteArray, payloadFor: (String) -> Boolean): List<Triple<String, Int, ByteArray>> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<Triple<String, Int, ByteArray>>()
        while (buf.hasRemaining()) {
            val id = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val len = buf.int
            val payload = if (payloadFor(id)) ByteArray(len).also { buf.get(it) } else ByteArray(0)
            out += Triple(id, len, payload)
        }
        return out
    }

    @Test fun `send writes SEND DATA DONE and accepts OKAY`() {
        val sent = ByteArrayOutputStream()
        val content = ByteArray(SyncClient.DATA_MAX + 10) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        val reply = TrickleInputStream(msg("OKAY", 0))

        SyncClient(reply, sent).send(ByteArrayInputStream(content), "/sdcard/a b.apk", 420, 1234) { progress += it }

        val reqs = requests(sent.toByteArray()) { it == "SEND" || it == "DATA" }
        assertEquals(listOf("SEND", "DATA", "DATA", "DONE"), reqs.map { it.first })
        assertEquals("/sdcard/a b.apk,${0x8000 or 420}", reqs[0].third.decodeToString())
        assertArrayEquals(content, reqs[1].third + reqs[2].third)
        assertEquals(1234, reqs[3].second)
        assertEquals(content.size.toLong(), progress.last())
    }

    @Test fun `send FAIL surfaces remote message`() {
        val reply = msg("FAIL", 25, "open failed: Permission denied".toByteArray().copyOf(25))
        try {
            SyncClient(ByteArrayInputStream(reply), ByteArrayOutputStream())
                .send(ByteArrayInputStream(ByteArray(3)), "/system/x", 420, 0) {}
            fail("expected SyncFailException")
        } catch (e: SyncFailException) {
            assertEquals("open failed: Permission d", e.remoteMessage)
        }
    }

    @Test fun `recv collects DATA until DONE`() {
        val reply = data("abc".toByteArray()) + data("def".toByteArray()) + msg("DONE", 0)
        val sink = ByteArrayOutputStream()
        val sent = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()

        SyncClient(TrickleInputStream(reply), sent).recv("/sdcard/f.txt", sink) { progress += it }

        assertEquals("abcdef", sink.toString())
        assertEquals(listOf(3L, 6L), progress)
        val req = requests(sent.toByteArray()) { true }.single()
        assertEquals("RECV", req.first)
        assertEquals("/sdcard/f.txt", req.third.decodeToString())
    }

    @Test(expected = SyncFailException::class) fun `recv FAIL throws`() {
        val reply = msg("FAIL", 4, "nope".toByteArray())
        SyncClient(ByteArrayInputStream(reply), ByteArrayOutputStream()).recv("/data/x", ByteArrayOutputStream()) {}
    }

    @Test(expected = java.io.IOException::class) fun `recv rejects oversized DATA`() {
        SyncClient(ByteArrayInputStream(msg("DATA", SyncClient.DATA_MAX + 1)), ByteArrayOutputStream())
            .recv("/x", ByteArrayOutputStream()) {}
    }

    private fun dent(mode: Int, size: Int, mtime: Int, name: String): ByteArray {
        val n = name.toByteArray()
        return ByteBuffer.allocate(20 + n.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("DENT".toByteArray()).putInt(mode).putInt(size).putInt(mtime).putInt(n.size).put(n).array()
    }

    @Test fun `list parses entries and drops dot entries`() {
        val done = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put("DONE".toByteArray()).array()
        val reply = dent(0x41ED, 4096, 1, ".") + dent(0x41ED, 4096, 1, "Download") +
            dent(0x81A4, -1, 2, "big.bin") + dent(0xA1FF, 9, 3, "link") + done
        val entries = SyncClient(TrickleInputStream(reply), ByteArrayOutputStream()).list("/sdcard")
        assertEquals(listOf("Download", "big.bin", "link"), entries.map { it.name })
        assertEquals(true, entries[0].isDirectory)
        assertEquals(0xFFFFFFFFL, entries[1].size) // unsigned 32-bit size
        assertEquals(true, entries[2].isSymlink)
    }

    @Test(expected = IllegalArgumentException::class) fun `relative path rejected`() {
        SyncClient(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()).recv("x", ByteArrayOutputStream()) {}
    }
}
