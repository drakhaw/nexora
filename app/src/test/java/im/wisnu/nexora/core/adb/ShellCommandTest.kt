package im.wisnu.nexora.core.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellCommandTest {

    @Test fun `safe args pass through unquoted`() {
        assertEquals("input keyevent 3", ShellCommand.of("input", "keyevent", "3").script)
    }

    @Test fun `spaces and metacharacters are single-quoted`() {
        assertEquals("echo 'a b' '\$(reboot)' 'x;y'", ShellCommand.of("echo", "a b", "\$(reboot)", "x;y").script)
    }

    @Test fun `embedded single quote is escaped`() {
        assertEquals("echo 'it'\\''s'", ShellCommand.of("echo", "it's").script)
    }

    @Test fun `empty arg becomes empty quotes`() {
        assertEquals("echo ''", ShellCommand.of("echo", "").script)
    }

    @Test fun `non-ascii is quoted`() {
        assertEquals("echo 'é'", ShellCommand.of("echo", "é").script)
    }

    @Test fun `toString hides the script`() {
        assertEquals("ShellCommand(<4 chars>)", ShellCommand.raw("ls /").toString())
    }
}
