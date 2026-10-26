package app.hearth.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Bottom sheet menu: an optional header (title + subtitle + leading content), then the
 * actions, which slide in one after another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    title: String?,
    subtitle: String? = null,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    fun close(then: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); then() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (title != null) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            actions.forEachIndexed { i, a ->
                val anim = remember { Animatable(if (reduced) 1f else 0f) }
                LaunchedEffect(Unit) { anim.animateTo(1f, tween(260, delayMillis = 30 * i)) }
                val tint = when {
                    !a.enabled -> MaterialTheme.colorScheme.outline
                    a.destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    Modifier.fillMaxWidth()
                        .graphicsLayer { alpha = anim.value; translationY = (1f - anim.value) * 24f }
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = a.enabled) { close(a.onClick) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (a.destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(a.icon, null, Modifier.size(20.dp),
                            tint = if (a.destructive) MaterialTheme.colorScheme.error else if (a.enabled) MaterialTheme.colorScheme.primary else tint)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(a.label, style = MaterialTheme.typography.bodyLarge, color = tint)
                }
            }
        }
    }
}

/** Small uppercase serif label used for section headings. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Serif, letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 2.2f),
        color = color,
        modifier = modifier,
    )
}
