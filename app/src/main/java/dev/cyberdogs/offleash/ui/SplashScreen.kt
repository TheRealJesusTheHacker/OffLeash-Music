package dev.cyberdogs.offleash.ui

import androidx.compose.ui.graphics.Brush
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cyberdogs.offleash.R
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The cold-launch splash (2026-10-27, owner's spec — see
 * [dev.cyberdogs.offleash.playback.SplashGate] for WHEN it shows):
 * the Cyberdogs Doberman, with a glitch treatment, for 3.8
 * seconds; a tap anywhere skips it at once. The lightning bolt
 * the mark originally spat was removed by the owner's order of
 * 2026-11-04, which supersedes that part of the 2026-10-27
 * spec: the logo now presents bare, everywhere in the app.
 *
 * Palette discipline: Cyberdogs black + tan + one lightning
 * accent. The glitch ghosts are the logo's own silhouette tinted
 * tan and lightning — never a rainbow channel split. When the
 * system animator scale is 0 (reduced motion), the logo renders
 * still; the duration and the tap-to-skip are unchanged.
 *
 * The artwork is the on-file brand logo with its cream backdrop
 * keyed to transparency (`cyberdogs_logo_alpha`), so the dog sits
 * on the theme's black directly.
 */
@Composable
fun SplashScreen(onSkip: () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    var jitter by remember { mutableStateOf(Offset.Zero) }
    if (!reducedMotion) {
        LaunchedEffect(Unit) {
            val random = Random(27)
            while (true) {
                delay(GLITCH_TICK_MS)
                // Mostly still; a jitter burst roughly one tick in
                // five — a glitch, not a vibration.
                jitter = if (random.nextFloat() < 0.2f) {
                    Offset(
                        random.nextInt(-6, 7).toFloat(),
                        random.nextInt(-2, 3).toFloat(),
                    )
                } else {
                    Offset.Zero
                }
            }
        }
    }
    val tan = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // TalkBack names the one action this surface has;
                // without a label the full-screen skip was an
                // unlabelled click target (chaos sweep round 2). It
                // is never a focus trap either way: the root's timer
                // dismisses the splash on its own.
                onClickLabel = "Skip the splash",
            ) { onSkip() },
        contentAlignment = Alignment.Center,
    ) {
        // Stage glow (GUI elevation pass): the theme's own tan as a
        // soft radial wash behind the mark. Pure surface — the
        // glitch loop, the timing and the gate are the shipped
        // treatment, unchanged.
        Box(
            modifier = Modifier
                .size(380.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            tan.copy(alpha = 0.15f),
                            tan.copy(alpha = 0.04f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                if (jitter != Offset.Zero) {
                    Image(
                        painter = painterResource(id = R.drawable.cyberdogs_logo_alpha),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(tan, BlendMode.SrcIn),
                        alpha = 0.45f,
                        modifier = Modifier
                            .size(LOGO_SIZE)
                            .offset(x = jitter.x.dp * 2, y = 0.dp),
                    )
                    Image(
                        painter = painterResource(id = R.drawable.cyberdogs_logo_alpha),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(LIGHTNING, BlendMode.SrcIn),
                        alpha = 0.45f,
                        modifier = Modifier
                            .size(LOGO_SIZE)
                            .offset(x = (-jitter.x).dp * 2, y = 0.dp),
                    )
                }
                Image(
                    painter = painterResource(id = R.drawable.cyberdogs_logo_alpha),
                    contentDescription = "Cyberdogs logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(LOGO_SIZE)
                        .offset(x = jitter.x.dp, y = jitter.y.dp),
                )
            }
            // The bolt that dropped from the dog's mouth here was
            // removed by the owner's order of 2026-11-04; the mark
            // stands over the wordmark bare.
            Spacer(Modifier.height(24.dp))
            Text(
                text = "OFFLEASH",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 5.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "CYBERDOGS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 7.sp,
                color = tan,
            )
        }
    }
}

/**
 * The one accent beyond black + tan: the lightning yellow, kept
 * as the second glitch-ghost tint. (Its other job — the bolt
 * under the mark — was removed by the owner's order of
 * 2026-11-04.)
 */
private val LIGHTNING = Color(0xFFF2C230)

private val LOGO_SIZE = 196.dp

private const val GLITCH_TICK_MS = 90L
