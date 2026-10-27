package app.hearth.core

object HtmlFormat {
    /** Only real tags, comments and doctypes; any other "<" is text and gets escaped. */
    private val TAG_SPLIT = Regex(
        "<!--[\\s\\S]*?-->|<![^>]*>|</?[A-Za-z][A-Za-z0-9:_.-]*(?:\\s(?:\"[^\"]*\"|'[^']*'|[^'\"<>])*)?/?>",
    )
    /** A quote or asterisk right after a tag name ("<coral\"Hi") is never valid HTML: the ">" is missing. */
    private val BROKEN_OPEN = Regex("<([A-Za-z][\\w:.-]*)[ \\t]*([\"\u201C\u201D*])")

    /** Repairs tags the model left unclosed, e.g. <coral"Hi"</coral> → <coral>"Hi"</coral>. */
    fun repairTags(text: String): String =
        if (text.indexOf('<') < 0) text else BROKEN_OPEN.replace(text) { "<" + it.groupValues[1] + ">" + it.groupValues[2] }
    private val RAW_OPEN = Regex("^<(style|script|pre|code|textarea)\\b")
    private val RAW_CLOSE = Regex("^</(style|script|pre|code|textarea)\\b")

    /**
     * Prepares message text the way SillyTavern does before showing it: roleplay formatting
     * (*actions*, **bold**, "dialogue") is applied to text between tags only, and line breaks
     * become <br> everywhere except inside <pre>/<code>/<style>/<script>. Formatting spans are
     * closed and reopened around every tag so they always nest correctly.
     */
    fun formatBody(text: String): String {
        val src = text.replace("\r\n", "\n")
        val sb = StringBuilder(src.length + 64)
        var italic = false
        var bold = false
        var quote = false
        var raw = 0 // inside <style>/<script>/<pre>/<code>/<textarea>
        var last = 0
        fun openAll() {
            if (bold) sb.append("<strong>")
            if (italic) sb.append("<em class=\"act\">")
            if (quote) sb.append("<span class=\"q\">")
        }
        fun closeAll() {
            if (quote) sb.append("</span>")
            if (italic) sb.append("</em>")
            if (bold) sb.append("</strong>")
        }
        fun formatText(t: String) {
            if (raw > 0) { sb.append(t); return }
            var i = 0
            while (i < t.length) {
                val c = t[i]
                when {
                    c == '\n' -> sb.append("<br>")
                    c == '<' -> sb.append("&lt;")
                    c == '*' && i + 1 < t.length && t[i + 1] == '*' -> { closeAll(); bold = !bold; openAll(); i += 2; continue }
                    c == '*' -> { closeAll(); italic = !italic; openAll() }
                    c == '"' || c == '\u201C' || c == '\u201D' -> {
                        if (!quote) { closeAll(); quote = true; openAll(); sb.append(c) }
                        else { sb.append(c); closeAll(); quote = false; openAll() }
                    }
                    else -> sb.append(c)
                }
                i++
            }
        }
        for (m in TAG_SPLIT.findAll(src)) {
            formatText(src.substring(last, m.range.first))
            val tag = m.value
            val lower = tag.lowercase()
            if (raw == 0) closeAll()
            sb.append(tag)
            if (RAW_OPEN.containsMatchIn(lower)) raw++
            else if (RAW_CLOSE.containsMatchIn(lower)) raw = (raw - 1).coerceAtLeast(0)
            if (raw == 0) openAll()
            last = m.range.last + 1
        }
        formatText(src.substring(last))
        closeAll()
        return sb.toString()
    }

}
