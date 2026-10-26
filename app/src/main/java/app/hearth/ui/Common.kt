package app.hearth.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Decoded thumbnails, keyed by "<id>@<version>". ~12 MB covers hundreds of 192px avatars. */
object AvatarCache {
    val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
}

@Composable
fun Avatar(
    id: String,
    name: String,
    thumb: File,
    version: Long,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val key = "$id@$version"
    val bmp by produceState<ImageBitmap?>(initialValue = AvatarCache.cache.get(key), key) {
        if (value == null && thumb.exists()) {
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(thumb.path)?.asImageBitmap() }.getOrNull()
            }?.also { AvatarCache.cache.put(key, it) }
        }
    }
    val m = modifier.size(size).clip(CircleShape)
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = m)
    } else {
        Box(m.background(colorFor(name)), contentAlignment = Alignment.Center) {
            Text(
                name.trim().take(1).uppercase(Locale.ROOT),
                color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

private fun colorFor(name: String): Color {
    val hues = floatArrayOf(18f, 32f, 350f, 200f, 160f, 270f, 45f, 120f)
    val h = hues[(name.hashCode() and 0x7fffffff) % hues.size]
    return Color.hsl(h, 0.45f, 0.42f)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = optionLabel(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        // Transparent overlay captures taps (a read-only text field swallows them otherwise).
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    step: Float = 0.01f,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { v -> onChange((Math.round(v / step) * step)) },
            valueRange = range,
        )
    }
}

/** Integer field that keeps its own text while typing and reports parsed values. */
@Composable
fun IntField(label: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }.take(8)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String = "Delete",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
