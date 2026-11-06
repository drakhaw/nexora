package im.wisnu.nexora.domain.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GetpropParserTest {

    @Test fun `parses key value lines and skips noise`() {
        val out = """
            [ro.product.model]: [AX512]
            [ro.product.manufacturer]: [Akari]
            [ro.build.version.sdk]: [28]
            [ro.empty]: []
            garbage line
            [ro.crlf]: [x]
        """.trimIndent().replace("[ro.crlf]: [x]", "[ro.crlf]: [x]\r")

        val props = DeviceInfoReader.parseGetprop(out)

        assertEquals("AX512", props["ro.product.model"])
        assertEquals("Akari", props["ro.product.manufacturer"])
        assertEquals("28", props["ro.build.version.sdk"])
        assertEquals("x", props["ro.crlf"])
        assertFalse(props.containsKey("ro.empty"))
        assertEquals(4, props.size)
    }
}
