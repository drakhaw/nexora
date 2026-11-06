package im.wisnu.nexora.domain.monitoring

import im.wisnu.nexora.domain.monitoring.MetricsReader.Companion.cpuPercent
import im.wisnu.nexora.domain.monitoring.MetricsReader.Companion.parseBattery
import im.wisnu.nexora.domain.monitoring.MetricsReader.Companion.parseCpuStat
import im.wisnu.nexora.domain.monitoring.MetricsReader.Companion.parseDf
import im.wisnu.nexora.domain.monitoring.MetricsReader.Companion.parseMeminfo
import im.wisnu.nexora.domain.monitoring.MetricsReader.Companion.parseUptime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsParserTest {

    @Test fun `cpu percent from two samples`() {
        val a = parseCpuStat("cpu  100 0 100 700 100 0 0 0 0 0\r\ncpu0 1 2 3 4")
        val b = parseCpuStat("cpu  150 0 150 800 100 0 0 0 0 0")
        // total delta 200, idle delta 100 -> 50 %
        assertEquals(50.0, cpuPercent(a, b)!!, 0.001)
    }

    @Test fun `cpu unavailable when stat unreadable`() {
        assertNull(cpuPercent(parseCpuStat("cat: /proc/stat: Permission denied"), parseCpuStat("")))
    }

    @Test fun `meminfo prefers MemAvailable`() {
        val m = parseMeminfo("MemTotal:        2000000 kB\nMemFree:  100 kB\nMemAvailable:    500000 kB\n")!!
        assertEquals(2_000_000, m.totalKb)
        assertEquals(500_000, m.availableKb)
    }

    @Test fun `meminfo falls back on old kernels`() {
        val m = parseMeminfo("MemTotal: 1000 kB\r\nMemFree: 100 kB\r\nBuffers: 50 kB\r\nCached: 250 kB\r\n")!!
        assertEquals(400, m.availableKb)
    }

    @Test fun `uptime seconds`() {
        assertEquals(12345L, parseUptime("12345.67 54321.00\n"))
        assertNull(parseUptime(""))
    }

    @Test fun `df toybox format`() {
        val s = parseDf(
            "Filesystem      1K-blocks    Used Available Use% Mounted on\n" +
                "/dev/block/dm-2   5000000 1000000   4000000  20% /data\n",
        )!!
        assertEquals(5_000_000, s.totalKb)
        assertEquals(1_000_000, s.usedKb)
        assertEquals(4_000_000, s.availableKb)
        assertEquals("/data", s.mount)
    }

    @Test fun `df old toolbox format with units`() {
        val s = parseDf("Filesystem               Size     Used     Free   Blksize\n/data                    5.5G     1.0G     4.5G   4096\n")!!
        assertEquals((5.5 * 1024 * 1024).toLong(), s.totalKb)
        assertEquals(1024L * 1024, s.usedKb)
    }

    @Test fun `df garbage is unavailable`() {
        assertNull(parseDf("df: /data: Permission denied"))
    }

    @Test fun `battery parsed and absent battery is unavailable`() {
        val b = parseBattery("Current Battery Service state:\n  present: true\n  status: 2\n  level: 40\n  scale: 50\n  temperature: 315\n")!!
        assertEquals(80, b.levelPercent)
        assertEquals(true, b.charging)
        assertEquals(31.5, b.temperatureC!!, 0.001)
        assertNull(parseBattery("  present: false\n  level: 100\n"))
    }
}
