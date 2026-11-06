package im.wisnu.nexora.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens from the Nexora brand guide (design.md §4). Change values here only.
 * Guide Slate Gray (#64748B) is below 4.5:1 on these surfaces, so it is used for
 * borders only; secondary text uses a lighter slate.
 */
object NexoraTokens {
    val Background = Color(0xFF0B1220) // Midnight Blue
    val Surface = Color(0xFF111A2E)
    val SurfaceElevated = Color(0xFF1A2540)
    val Accent = Color(0xFF3B82F6) // Nexora Blue
    val AccentSecondary = Color(0xFF8B5CF6) // Nexora Purple
    val TextPrimary = Color(0xFFF8FAFC) // Snow White
    val TextSecondary = Color(0xFF94A3B8)
    val Success = Color(0xFF36B37E)
    val Warning = Color(0xFFE5A33A)
    val Error = Color(0xFFE05D68)
    val Border = Color(0xFF64748B) // Slate Gray

    val CardRadius = 16.dp
    val Space = 4.dp
}

private val colors = darkColorScheme(
    primary = NexoraTokens.Accent,
    onPrimary = NexoraTokens.Background,
    background = NexoraTokens.Background,
    onBackground = NexoraTokens.TextPrimary,
    surface = NexoraTokens.Surface,
    onSurface = NexoraTokens.TextPrimary,
    surfaceVariant = NexoraTokens.SurfaceElevated,
    onSurfaceVariant = NexoraTokens.TextSecondary,
    surfaceContainer = NexoraTokens.Surface,
    surfaceContainerHigh = NexoraTokens.SurfaceElevated,
    secondary = NexoraTokens.AccentSecondary,
    onSecondary = NexoraTokens.TextPrimary,
    outline = NexoraTokens.Border,
    outlineVariant = NexoraTokens.SurfaceElevated,
    error = NexoraTokens.Error,
)

private val shapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(NexoraTokens.CardRadius),
)

@Composable
fun NexoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}
