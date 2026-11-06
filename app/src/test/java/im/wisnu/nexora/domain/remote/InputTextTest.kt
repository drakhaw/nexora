package im.wisnu.nexora.domain.remote

import im.wisnu.nexora.core.adb.ShellCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class InputTextTest {

    @Test fun `spaces become percent-s and specials are shell-quoted`() {
        val r = InputText.encode("hi there; it's \$HOME") as InputText.Result.Ok
        assertEquals(listOf("hi%sthere;%sit's%s\$HOME"), r.chunks)
        assertEquals("input text 'hi%sthere;%sit'\\''s%s\$HOME'", ShellCommand.of("input", "text", r.chunks[0]).script)
    }

    @Test fun `non-ascii is reported`() {
        val r = InputText.encode("café 😀") as InputText.Result.Unsupported
        assertEquals(setOf('é', '\uD83D', '\uDE00'), r.characters)
    }

    @Test fun `long text is chunked without splitting a space escape`() {
        val text = "a".repeat(79) + " b"
        val r = InputText.encode(text) as InputText.Result.Ok
        assertEquals(listOf("a".repeat(79) + "%s", "b"), r.chunks)
    }
}
