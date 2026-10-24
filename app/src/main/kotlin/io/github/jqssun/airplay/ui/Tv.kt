package io.github.jqssun.airplay.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

fun Context.isTvDevice(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun isTv(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { ctx.isTvDevice() }
}

/**
 * D-pad focus indicator. Pass the SAME shape the control uses so the highlight matches it:
 * [androidx.compose.foundation.shape.CircleShape] for buttons/icon buttons (pills/circles),
 * a small rounded shape for chips, and [RectangleShape] for full-width list rows.
 *
 * Does NOT clip the element (clipping would reshape pill buttons even when unfocused) and
 * does NOT scale (scaling clips full-width rows at the screen edge). It only paints a fill
 * tint + border in [shape], so the control keeps its own appearance.
 */
fun Modifier.dpadFocus(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        if (focused) primary.copy(alpha = 0.16f) else Color.Transparent, label = "dpadBg"
    )
    onFocusChanged { focused = it.isFocused }
        .background(bg, shape)
        .border(if (focused) 2.dp else 0.dp, if (focused) primary else Color.Transparent, shape)
}

/** Focus highlight matching pill-shaped buttons and circular icon buttons. */
fun Modifier.dpadFocusPill(): Modifier = dpadFocus(CircleShape)

/** Focus highlight matching M3 chips (small rounded corners). */
fun Modifier.dpadFocusChip(): Modifier = dpadFocus(RoundedCornerShape(8.dp))

fun Modifier.dpadAdjust(onLeft: () -> Unit, onRight: () -> Unit): Modifier =
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { onLeft(); true }
            Key.DirectionRight -> { onRight(); true }
            else -> false
        }
    }
