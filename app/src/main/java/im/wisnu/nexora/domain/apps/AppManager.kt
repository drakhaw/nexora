package im.wisnu.nexora.domain.apps

import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.ShellCommand
import im.wisnu.nexora.core.adb.map
import im.wisnu.nexora.core.adb.run
import java.io.InputStream
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class InstalledApp(val packageName: String, val system: Boolean)

data class AppDetails(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val firstInstall: String?,
    val lastUpdate: String?,
    val launchActivity: String?,
)

/** Package operations over ADB shell (APP-01 to APP-08). */
class AppManager @Inject constructor() {

    suspend fun list(session: AdbSession): AdbResult<List<InstalledApp>> {
        val all = session.run(ShellCommand.of("pm", "list", "packages"), 20.seconds)
        if (all is AdbResult.Err) return all
        val third = session.run(ShellCommand.of("pm", "list", "packages", "-3"), 20.seconds)
        val userSet = (third as? AdbResult.Ok)?.value?.let(::parsePackages)?.toSet()
        return all.map { out ->
            parsePackages(out).map { InstalledApp(it, system = userSet != null && it !in userSet) }
                .sortedWith(compareBy({ it.system }, { it.packageName }))
        }
    }

    /**
     * Resolves a launchable activity, preferring the TV (Leanback) launcher
     * category. Returns null when the package has none (APP-08).
     */
    suspend fun resolveLaunchActivity(session: AdbSession, pkg: String): AdbResult<String?> {
        if (!isValidPackage(pkg)) return AdbResult.Err(AdbError.ShellFailure("invalid package name"))
        for (category in LAUNCH_CATEGORIES) {
            val r = session.run(
                ShellCommand.of(
                    "cmd", "package", "resolve-activity", "--brief",
                    "-a", "android.intent.action.MAIN", "-c", category, pkg,
                ),
            )
            when (r) {
                is AdbResult.Ok -> parseResolvedComponent(r.value, pkg)?.let { return AdbResult.Ok(it) }
                is AdbResult.Err -> if (r.error !is AdbError.ShellFailure) return r
            }
        }
        return AdbResult.Ok(null)
    }

    /** Launches [pkg] (APP-03). Falls back to monkey on firmware without `cmd package resolve-activity`. */
    suspend fun launch(session: AdbSession, pkg: String): AdbResult<Unit> {
        when (val resolved = resolveLaunchActivity(session, pkg)) {
            is AdbResult.Err -> return resolved
            is AdbResult.Ok -> resolved.value?.let { component ->
                return session.run(ShellCommand.of("am", "start", "-n", component)).map { }
            }
        }
        for (category in LAUNCH_CATEGORIES) {
            val r = session.run(ShellCommand.of("monkey", "-p", pkg, "-c", category, "1"), 15.seconds)
            if (r is AdbResult.Ok && "Events injected" in r.value) return AdbResult.Ok(Unit)
            if (r is AdbResult.Err && r.error !is AdbError.ShellFailure) return r
        }
        return AdbResult.Err(AdbError.Unsupported("launching this app: it has no launcher activity"))
    }

    suspend fun forceStop(session: AdbSession, pkg: String): AdbResult<Unit> =
        checked(pkg) { session.run(ShellCommand.of("am", "force-stop", pkg)).map { } }

    /** Uninstall (APP-07). Callers must confirm first (SEC-06). */
    suspend fun uninstall(session: AdbSession, pkg: String): AdbResult<Unit> = checked(pkg) {
        when (val r = session.execute(ShellCommand.of("pm", "uninstall", pkg), 1.minutes)) {
            is AdbResult.Err -> r
            is AdbResult.Ok -> pmOutcome(r.value.stdout + "\n" + r.value.stderr.orEmpty())
        }
    }

    suspend fun details(session: AdbSession, pkg: String): AdbResult<AppDetails> = checked(pkg) {
        val dump = session.run(ShellCommand.of("dumpsys", "package", pkg), 15.seconds)
        if (dump is AdbResult.Err) return@checked dump
        val text = (dump as AdbResult.Ok).value
        val launch = (resolveLaunchActivity(session, pkg) as? AdbResult.Ok)?.value
        AdbResult.Ok(
            AppDetails(
                packageName = pkg,
                versionName = field(text, "versionName"),
                versionCode = field(text, "versionCode"),
                firstInstall = field(text, "firstInstallTime"),
                lastUpdate = field(text, "lastUpdateTime"),
                launchActivity = launch,
            ),
        )
    }

    /**
     * Installs an APK (APP-06, FIL-06): push to /data/local/tmp, `pm install -r`,
     * then delete the temporary file. [onProgress] reports pushed bytes.
     */
    suspend fun installApk(
        session: AdbSession,
        apk: InputStream,
        onProgress: (Long) -> Unit,
    ): AdbResult<Unit> {
        val pushed = session.files.push(apk, TMP_APK, mode = 420, onProgress = onProgress)
        if (pushed is AdbResult.Err) return pushed
        return try {
            when (val r = session.execute(ShellCommand.of("pm", "install", "-r", TMP_APK), 3.minutes)) {
                is AdbResult.Err -> r
                is AdbResult.Ok -> pmOutcome(r.value.stdout + "\n" + r.value.stderr.orEmpty())
            }
        } finally {
            session.execute(ShellCommand.of("rm", "-f", TMP_APK))
        }
    }

    private suspend fun <T> checked(pkg: String, block: suspend () -> AdbResult<T>): AdbResult<T> =
        if (isValidPackage(pkg)) block() else AdbResult.Err(AdbError.ShellFailure("invalid package name"))

    companion object {
        private const val TMP_APK = "/data/local/tmp/nexora-install.apk"
        private val LAUNCH_CATEGORIES = listOf(
            "android.intent.category.LEANBACK_LAUNCHER",
            "android.intent.category.LAUNCHER",
        )
        private val PACKAGE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

        fun isValidPackage(pkg: String): Boolean = PACKAGE.matches(pkg)

        fun parsePackages(output: String): List<String> =
            output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").substringAfterLast('=') }
                .filter(::isValidPackage)
                .distinct()
                .toList()

        /** `resolve-activity --brief` prints the component on its last line, e.g. `pkg/.Main`. */
        fun parseResolvedComponent(output: String, pkg: String): String? =
            output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
                ?.takeIf { it.startsWith("$pkg/") && ' ' !in it }

        /** `pm` prints "Success" or "Failure [REASON]". */
        fun pmOutcome(output: String): AdbResult<Unit> {
            val lines = output.lineSequence().map { it.trim() }
            if (lines.any { it == "Success" }) return AdbResult.Ok(Unit)
            val failure = lines.firstOrNull { it.startsWith("Failure") || it.startsWith("Error") }
                ?: "no Success reported"
            return AdbResult.Err(AdbError.ShellFailure(failure.take(200)))
        }

        private fun field(text: String, name: String): String? =
            Regex("""\b$name=(\S+(?: \S+)?)""").find(text)?.groupValues?.get(1)
                ?.let { if (name.endsWith("Time")) it else it.substringBefore(' ') }
    }
}
