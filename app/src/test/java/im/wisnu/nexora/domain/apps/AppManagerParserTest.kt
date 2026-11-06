package im.wisnu.nexora.domain.apps

import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppManagerParserTest {

    @Test fun `parses package lines including -f form`() {
        val out = "package:com.android.tv.settings\r\npackage:/data/app/x/base.apk=com.netflix.ninja\nnoise\npackage:bad name\n"
        assertEquals(listOf("com.android.tv.settings", "com.netflix.ninja"), AppManager.parsePackages(out))
    }

    @Test fun `package validation blocks shell metacharacters`() {
        assertTrue(AppManager.isValidPackage("com.example.app_1"))
        assertFalse(AppManager.isValidPackage("com.x;reboot"))
        assertFalse(AppManager.isValidPackage("nodots"))
    }

    @Test fun `resolved component from last line`() {
        val out = "priority=0 preferredOrder=0 match=0x108000\ncom.netflix.ninja/.MainActivity\n"
        assertEquals("com.netflix.ninja/.MainActivity", AppManager.parseResolvedComponent(out, "com.netflix.ninja"))
        assertNull(AppManager.parseResolvedComponent("No activity found\n", "com.netflix.ninja"))
    }

    @Test fun `pm outcome`() {
        assertTrue(AppManager.pmOutcome("Performing Streamed Install\nSuccess\n") is AdbResult.Ok)
        val fail = AppManager.pmOutcome("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n") as AdbResult.Err
        assertEquals(AdbError.ShellFailure("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"), fail.error)
    }
}
