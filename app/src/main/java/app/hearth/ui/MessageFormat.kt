package app.hearth.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Single-pass roleplay formatter: *actions* → italic/dim, **bold**, "dialogue" → accent,
 * `code` and ``` blocks → monospace. Linear time and no regex, so it is cheap enough
 * to re-run on every streaming frame.
 */
fun formatMessage(text: String, p: ChatPalette): AnnotatedString {
    val b = AnnotatedString.Builder(text.length)
    val run = StringBuilder()
    var cur: SpanStyle? = null
    var italic = false
    var bold = false
    var quote = false
    var code = false
    var block = false

    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = p.code, background = p.codeBg)

    fun style(): SpanStyle? {
        if (block || code) return codeStyle
        if (!italic && !bold && !quote) return null
        return SpanStyle(
            color = if (quote) p.quote else if (italic) p.action else androidx.compose.ui.graphics.Color.Unspecified,
            fontStyle = if (italic) FontStyle.Italic else null,
            fontWeight = if (bold) FontWeight.Bold else null,
        )
    }

    fun flush() {
        if (run.isEmpty()) return
        val s = cur
        if (s == null) b.append(run.toString()) else b.withStyle(s) { append(run.toString()) }
        run.setLength(0)
    }

    fun emit(c: Char) {
        val s = style()
        if (s != cur) { flush(); cur = s }
        run.append(c)
    }

    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (c == '`' && text.startsWith("```", i)) {
            block = !block
            i += 3
            if (block) { // skip language tag
                while (i < n && text[i] != '\n') i++
                if (i < n) i++
            }
            continue
        }
        if (block) { emit(c); i++; continue }
        when {
            c == '`' -> { code = !code; i++ }
            code -> { emit(c); i++ }
            c == '*' && i + 1 < n && text[i + 1] == '*' -> { bold = !bold; i += 2 }
            c == '*' -> { italic = !italic; i++ }
            c == '"' || c == '“' || c == '”' -> {
                if (!quote) { quote = true; emit(c) } else { emit(c); quote = false }
                i++
            }
            else -> { emit(c); i++ }
        }
    }
    flush()
    return b.toAnnotatedString()
}
