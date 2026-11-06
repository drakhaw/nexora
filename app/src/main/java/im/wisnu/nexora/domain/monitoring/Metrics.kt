package im.wisnu.nexora.domain.monitoring

import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.ShellCommand
import im.wisnu.nexora.core.adb.map
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * One metrics snapshot (MON-01 to MON-07). Each field is null when this
 * firmware does not expose it; the UI shows "Unavailable", never an estimate.
 */
data class Metrics(
    val cpuPercent: Double?,
    val memory: Memory?,
    val storage: Storage?,
    val battery: Battery?,
    val uptimeSeconds: Long?,
    val collectedAt: Long,
) {
    data class Memory(val totalKb: Long, val availableKb: Long)
    data class Storage(val mount: String, val totalKb: Long, val usedKb: Long, val availableKb: Long)
    data class Battery(val levelPercent: Int?, val charging: Boolean?, val temperatureC: Double?)
}

class MetricsReader @Inject constructor() {

    /**
     * Collects everything in one shell round trip. /proc/stat is sampled
     * twice, one second apart, to compute CPU use. The script is a constant,
     * so it carries no user input.
     */
    suspend fun read(session: AdbSession, now: () -> Long = System::currentTimeMillis): AdbResult<Metrics> {
        // Sections are separated by a marker line; "sleep" produces none of its own.
        val raw = ShellCommand.raw(
            "cat /proc/stat 2>/dev/null; echo $M; sleep 1; cat /proc/stat 2>/dev/null; echo $M; " +
                "cat /proc/meminfo 2>/dev/null; echo $M; cat /proc/uptime 2>/dev/null; echo $M; " +
                "df -k /data 2>/dev/null; echo $M; dumpsys battery 2>/dev/null",
        )
        return session.execute(raw, 15.seconds).map { result ->
            val parts = result.stdout.split("$M\n", "$M\r\n")
            fun part(i: Int) = parts.getOrNull(i).orEmpty()
            Metrics(
                cpuPercent = cpuPercent(parseCpuStat(part(0)), parseCpuStat(part(1))),
                memory = parseMeminfo(part(2)),
                storage = parseDf(part(4)),
                battery = parseBattery(part(5)),
                uptimeSeconds = parseUptime(part(3)),
                collectedAt = now(),
            )
        }
    }

    companion object {
        private const val M = "@@NEXORA@@"

        data class CpuSample(val idle: Long, val total: Long)

        /** First `cpu ` line of /proc/stat: idle = idle + iowait. */
        fun parseCpuStat(text: String): CpuSample? {
            val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
            val v = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size < 4) return null
            return CpuSample(idle = v[3] + (v.getOrNull(4) ?: 0), total = v.take(8).sum())
        }

        fun cpuPercent(a: CpuSample?, b: CpuSample?): Double? {
            if (a == null || b == null) return null
            val total = b.total - a.total
            val idle = b.idle - a.idle
            if (total <= 0 || idle < 0 || idle > total) return null
            return (total - idle) * 100.0 / total
        }

        fun parseMeminfo(text: String): Metrics.Memory? {
            fun kb(key: String) = Regex("""^$key:\s+(\d+)\s*kB""", RegexOption.MULTILINE).find(text)
                ?.groupValues?.get(1)?.toLongOrNull()
            val total = kb("MemTotal") ?: return null
            // MemAvailable exists from Linux 3.14; older kernels: free + buffers + cached.
            val available = kb("MemAvailable")
                ?: listOf(kb("MemFree"), kb("Buffers"), kb("Cached")).takeIf { l -> l.all { it != null } }
                    ?.sumOf { it!! }
                ?: return null
            return Metrics.Memory(total, available)
        }

        fun parseUptime(text: String): Long? =
            text.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()?.toLong()

        /**
         * Parses `df -k /data`. Handles toybox (`1K-blocks Used Available`)
         * and old toolbox output (`Size Used Free` with K/M/G suffixes).
         */
        fun parseDf(text: String): Metrics.Storage? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val header = lines.firstOrNull() ?: return null
            val row = lines.drop(1).firstOrNull { it.split(Regex("\\s+")).let { t -> t.firstOrNull() == "/data" || t.lastOrNull() == "/data" } }
                ?: lines.getOrNull(1) ?: return null
            val t = row.split(Regex("\\s+"))
            return if (header.contains("1K-blocks") || header.contains("Available")) {
                if (t.size < 4) return null
                val total = t[1].toLongOrNull() ?: return null
                val used = t[2].toLongOrNull() ?: return null
                val avail = t[3].toLongOrNull() ?: return null
                Metrics.Storage(t.last(), total, used, avail)
            } else {
                // toolbox: Filesystem Size Used Free Blksize
                if (t.size < 4) return null
                val total = sizeKb(t[1]) ?: return null
                val used = sizeKb(t[2]) ?: return null
                val free = sizeKb(t[3]) ?: return null
                Metrics.Storage(t[0], total, used, free)
            }
        }

        private fun sizeKb(s: String): Long? {
            val m = Regex("""^([\d.]+)([KMGT]?)$""").matchEntire(s) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2]) { "" -> 1.0 / 1024; "K" -> 1.0; "M" -> 1024.0; "G" -> 1024.0 * 1024; else -> 1024.0 * 1024 * 1024 }
            return (n * mult).toLong()
        }

        /** `dumpsys battery`. Returns null when no battery is present (typical for TV boxes). */
        fun parseBattery(text: String): Metrics.Battery? {
            fun v(key: String) = Regex("""^\s*$key:\s*(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
            if (v("present") == "false") return null
            val level = v("level")?.toIntOrNull()
            val scale = v("scale")?.toIntOrNull()?.takeIf { it > 0 } ?: 100
            val percent = level?.let { it * 100 / scale }
            val status = v("status")?.toIntOrNull()
            val temp = v("temperature")?.toIntOrNull()?.let { it / 10.0 }
            if (percent == null && status == null && temp == null) return null
            return Metrics.Battery(percent, status?.let { it == 2 || it == 5 }, temp)
        }
    }
}
