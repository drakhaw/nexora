package im.wisnu.nexora.domain.monitoring

import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.ShellCommand
import im.wisnu.nexora.core.adb.map
import javax.inject.Inject

/**
 * Device identity snapshot (MON-04). Any field the firmware does not expose
 * stays null and the UI shows "Unavailable"; values are never estimated.
 */
data class DeviceInfo(
    val manufacturer: String?,
    val model: String?,
    val androidVersion: String?,
    val sdkInt: Int?,
    val buildFingerprint: String?,
    val collectedAt: Long,
)

class DeviceInfoReader @Inject constructor() {

    suspend fun read(session: AdbSession, now: Long = System.currentTimeMillis()): AdbResult<DeviceInfo> =
        session.execute(ShellCommand.of("getprop")).map { result ->
            val props = parseGetprop(result.stdout)
            DeviceInfo(
                manufacturer = props["ro.product.manufacturer"],
                model = props["ro.product.model"],
                androidVersion = props["ro.build.version.release"],
                sdkInt = props["ro.build.version.sdk"]?.toIntOrNull(),
                buildFingerprint = props["ro.build.fingerprint"],
                collectedAt = now,
            )
        }

    companion object {
        private val LINE = Regex("""^\[([^\]]+)]: \[(.*)]$""")

        /** Parses `getprop` output (`[key]: [value]` per line). Blank values become absent. */
        fun parseGetprop(output: String): Map<String, String> =
            output.lineSequence()
                .mapNotNull { LINE.matchEntire(it.trimEnd('\r')) }
                .map { it.groupValues[1] to it.groupValues[2].trim() }
                .filter { it.second.isNotEmpty() }
                .toMap()
    }
}
