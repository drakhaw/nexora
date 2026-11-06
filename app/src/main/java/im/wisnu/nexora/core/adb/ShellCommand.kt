package im.wisnu.nexora.core.adb

/**
 * A shell command line. Build it with [of] so every argument is quoted;
 * never concatenate untrusted values into a command (design §6.2).
 * [raw] is reserved for the terminal, where the user types the script.
 */
class ShellCommand private constructor(val script: String) {

    override fun toString(): String = "ShellCommand(<${script.length} chars>)"

    companion object {
        fun of(program: String, vararg args: String): ShellCommand =
            ShellCommand((listOf(program) + args).joinToString(" ") { quote(it) })

        fun raw(script: String): ShellCommand {
            require(script.isNotBlank()) { "empty command" }
            return ShellCommand(script)
        }

        private const val SAFE = "@%+=:,./_-"

        /** POSIX single-quote escaping. Safe for `sh -c` on the target. */
        fun quote(arg: String): String {
            if (arg.isNotEmpty() && arg.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in SAFE }) {
                return arg
            }
            return "'" + arg.replace("'", "'\\''") + "'"
        }
    }
}
