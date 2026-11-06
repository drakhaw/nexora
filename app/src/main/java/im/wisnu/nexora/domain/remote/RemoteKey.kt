package im.wisnu.nexora.domain.remote

/** Logical remote actions mapped to Android key codes (design §6.2). */
enum class RemoteKey(val keyCode: Int, val label: String, val repeatable: Boolean = false) {
    Up(19, "Up", repeatable = true),
    Down(20, "Down", repeatable = true),
    Left(21, "Left", repeatable = true),
    Right(22, "Right", repeatable = true),
    Center(23, "OK"),
    Back(4, "Back"),
    Home(3, "Home"),
    Recents(187, "Recent apps"),
    VolumeUp(24, "Volume up", repeatable = true),
    VolumeDown(25, "Volume down", repeatable = true),
    Mute(164, "Mute"),
    Power(26, "Power"),
    Menu(82, "Menu"),
    PlayPause(85, "Play/pause"),
    Delete(67, "Delete", repeatable = true),
    Enter(66, "Enter"),
}

/**
 * Prepares text for `input text` (KEY-02, KEY-03).
 *
 * `input text` reliably handles printable ASCII only; other characters need a
 * companion IME (KEY-04). Spaces must be sent as `%s`. Shell metacharacters
 * are handled by [im.wisnu.nexora.core.adb.ShellCommand] quoting.
 */
object InputText {
    sealed interface Result {
        data class Ok(val chunks: List<String>) : Result
        data class Unsupported(val characters: Set<Char>) : Result
    }

    private const val CHUNK = 80

    fun encode(text: String): Result {
        val bad = text.filterNot { it in ' '..'~' }.toSet()
        if (bad.isNotEmpty()) return Result.Unsupported(bad)
        // Chunk before escaping so a `%s` is never split.
        return Result.Ok(text.chunked(CHUNK).map { it.replace(" ", "%s") })
    }
}
