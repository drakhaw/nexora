package im.wisnu.nexora.feature.splash

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import im.wisnu.nexora.R
import kotlinx.coroutines.delay

/** Splash colors, sampled from media/splash.png. */
private object SplashColors {
    val Backdrop = Color(0xFF03081A)
    val BackdropCenter = Color(0xFF0A1433)
    val ArcCore = Color(0xFFBFEFFF)
    val ArcBlue = Color(0xFF1F6BFF)
    val ArcDeep = Color(0xFF0B33C8)
    val ArcPurple = Color(0xFF8B3CFF)
    val TaglineGray = Color(0xFFC3CAD8)
    val TaglineCyan = Color(0xFF22C3F2)
    val TaglinePurple = Color(0xFFA374F7)
}

/**
 * Branded splash (media/splash.png): glowing arcs, Nexora mark, wordmark and tagline.
 * Fades in, holds, then calls [onFinished].
 */
@Composable
fun NexoraSplash(onFinished: () -> Unit, holdMillis: Long = 1200) {
    val alpha = remember { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(350))
        delay(holdMillis)
        finish()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(SplashColors.Backdrop),
    ) {
        val width = maxWidth
        Canvas(Modifier.fillMaxSize()) { drawBackdrop() }
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_nexora_mark),
                contentDescription = null,
                modifier = Modifier.width(width * 0.36f),
            )
            Spacer(Modifier.height(width * 0.07f))
            Canvas(
                Modifier
                    .width(width * 0.74f)
                    .aspectRatio(WORDMARK_W / WORDMARK_H),
            ) { drawWordmark(Color.White) }
            Spacer(Modifier.height(width * 0.05f))
            val taglineSize = with(LocalDensity.current) { (width * 0.033f).toSp() }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = SplashColors.TaglineGray)) { append("YOUR ANDROID TV. ") }
                    withStyle(SpanStyle(color = SplashColors.TaglineCyan)) { append("UNDER YOUR ") }
                    withStyle(SpanStyle(color = SplashColors.TaglinePurple)) { append("CONTROL.") }
                },
                fontSize = taglineSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.16.em,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            // Optical centre sits a little above the middle, as in the guide.
            Spacer(Modifier.height(width * 0.18f))
        }
    }
}

/** Backdrop: soft centre glow plus four edge arcs, measured from the guide in screen-width units. */
private fun DrawScope.drawBackdrop() {
    val w = size.width
    drawRect(
        Brush.radialGradient(
            listOf(SplashColors.BackdropCenter, SplashColors.Backdrop),
            center = Offset(w / 2, size.height * 0.42f),
            radius = w * 0.9f,
        ),
    )
    // Top-left arcs: circle centres given from the top-left corner. Angles in degrees, clockwise from 3 o'clock.
    glowArc(Offset(1.091f * w, 1.306f * w), 1.333f * w, from = 200f, to = 280f, bright = true, purpleAtStart = true)
    glowArc(Offset(1.120f * w, 0.821f * w), 1.072f * w, from = 198f, to = 255f, bright = false, purpleAtStart = false)
    // Bottom-right arcs: circle centres given from the bottom-right corner.
    val br = Offset(w, size.height)
    glowArc(br - Offset(0.993f * w, 1.271f * w), 1.272f * w, from = 22f, to = 102f, bright = true, purpleAtStart = true)
    glowArc(br - Offset(0.918f * w, 0.679f * w), 0.855f * w, from = 22f, to = 70f, bright = false, purpleAtStart = false)
}

/**
 * One arc drawn as a soft glow with a bright core so it reads as a light beam.
 * Both ends fade out; with [purpleAtStart] the start end shifts to Nexora Purple, as in the guide.
 */
private fun DrawScope.glowArc(
    center: Offset,
    radius: Float,
    from: Float,
    to: Float,
    bright: Boolean,
    purpleAtStart: Boolean,
) {
    val w = size.width
    val a = from / 360f
    val b = to / 360f
    val span = b - a
    val clear = SplashColors.ArcBlue.copy(alpha = 0f)
    val startTint = if (purpleAtStart) SplashColors.ArcPurple else SplashColors.ArcDeep
    val tint = Brush.sweepGradient(
        0f to clear,
        a to clear,
        a + span * 0.12f to startTint,
        a + span * 0.35f to SplashColors.ArcBlue,
        a + span * 0.75f to SplashColors.ArcBlue,
        b to clear,
        1f to clear,
        center = center,
    )
    val arcTopLeft = center - Offset(radius, radius)
    val arcSize = Size(radius * 2, radius * 2)
    val scale = if (bright) 1f else 0.6f
    val sweep = to - from
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Blur mask filters render with hardware acceleration from API 28: soft bloom like the guide.
        val glow = listOf(
            Triple(0.11f, 0.09f, 0.35f), // wide haze: stroke, blur radius, alpha
            Triple(0.035f, 0.03f, 0.75f),
            Triple(0.012f, 0.008f, 0.95f),
        )
        for ((stroke, blur, alpha) in glow) {
            blurArc(tint, from, sweep, arcTopLeft, arcSize, alpha * scale, w * stroke, w * blur)
        }
    } else {
        // Older devices: stacked strokes approximate the bloom.
        val layers = listOf(0.16f to 0.06f, 0.08f to 0.14f, 0.035f to 0.32f, 0.014f to 0.75f)
        for ((width, alpha) in layers) {
            drawArc(tint, from, sweep, false, arcTopLeft, arcSize, alpha * scale, Stroke(width = w * width))
        }
    }
    if (bright) {
        val core = Brush.sweepGradient(
            0f to Color.Transparent,
            a + span * 0.25f to Color.Transparent,
            a + span * 0.5f to SplashColors.ArcCore,
            a + span * 0.7f to SplashColors.ArcCore,
            b to Color.Transparent,
            1f to Color.Transparent,
            center = center,
        )
        drawArc(core, from, sweep, false, arcTopLeft, arcSize, 1f, Stroke(width = w * 0.004f))
    }
}

/** Arc stroke with a Gaussian blur mask, drawn through the framework paint. */
private fun DrawScope.blurArc(
    brush: Brush,
    from: Float,
    sweep: Float,
    topLeft: Offset,
    arcSize: Size,
    alpha: Float,
    strokeWidth: Float,
    blurRadius: Float,
) {
    val paint = Paint().apply {
        style = PaintingStyle.Stroke
        this.strokeWidth = strokeWidth
    }
    brush.applyTo(size, paint, alpha)
    paint.asFrameworkPaint().maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
    drawIntoCanvas { it.drawArc(Rect(topLeft, arcSize), from, sweep, false, paint) }
}

private const val WORDMARK_W = 694f
private const val WORDMARK_H = 90f

/**
 * "NEXORA" wordmark, geometric caps with an open (bar-less) A, measured from media/splash.png.
 * Drawn in a 694 x 90 unit box; stroke weight 20.
 */
private fun DrawScope.drawWordmark(color: Color) {
    val s = size.width / WORDMARK_W
    withTransform({ scale(s, s, pivot = Offset.Zero) }) {
        // Each part is its own path so overlapping parts never cancel out under the fill rule.
        fun part(block: Path.() -> Unit) = drawPath(Path().apply(block), color)
        // N
        part { addRect(Rect(0f, 0f, 20f, 90f)) }
        part { addRect(Rect(94f, 0f, 114f, 90f)) }
        part { poly(0f to 0f, 28f to 0f, 114f to 90f, 86f to 90f) }
        // E
        part { addRect(Rect(139f, 0f, 159f, 90f)) }
        part { addRect(Rect(139f, 0f, 222f, 20f)) }
        part { addRect(Rect(139f, 35f, 214f, 55f)) }
        part { addRect(Rect(139f, 70f, 222f, 90f)) }
        // X
        part { poly(241f to 0f, 267f to 0f, 350f to 90f, 324f to 90f) }
        part { poly(321f to 0f, 347f to 0f, 265f to 90f, 240f to 90f) }
        // R stem and leg
        part { addRect(Rect(482f, 0f, 502f, 90f)) }
        part { poly(522f to 50f, 548f to 50f, 571f to 90f, 545f to 90f) }
        // A (open)
        part { poly(583f to 90f, 629f to 0f, 649f to 0f, 694f to 90f, 674f to 90f, 639f to 21f, 603f to 90f) }
        val stroke = Stroke(width = 20f, cap = StrokeCap.Butt)
        // O
        drawOval(color, topLeft = Offset(368f, 10f), size = Size(84f, 70f), style = stroke)
        // R bowl
        val bowl = Path().apply {
            moveTo(492f, 10f)
            lineTo(543.5f, 10f)
            arcTo(Rect(526f, 10f, 561f, 45f), -90f, 180f, false)
            lineTo(492f, 45f)
        }
        drawPath(bowl, color, style = stroke)
    }
}

private fun Path.poly(vararg pts: Pair<Float, Float>) {
    moveTo(pts[0].first, pts[0].second)
    for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
    close()
}
