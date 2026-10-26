package app.hearth.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.UUID

/** Detects messages that should be rendered as HTML instead of plain formatted text. */
object Html {
    private val TAG = Regex(
        "<(div|span|p|br|hr|details|summary|table|thead|tbody|tr|td|th|img|style|font|center|ul|ol|li|b|i|u|s|em|strong|" +
            "h[1-6]|pre|code|blockquote|a|section|article|small|sup|sub|mark|ruby|rt|q|kbd|figure|figcaption|svg|progress|meter)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val TAG_SPLIT = Regex("<[^>]*>")
    private val BLOCK_NL = Regex(
        "\\n*(</?(?:div|table|thead|tbody|tr|td|th|details|summary|ul|ol|li|section|article|h[1-6]|hr|p|blockquote|figure|style|br)\\b[^>]*>)\\n*",
        RegexOption.IGNORE_CASE,
    )

    fun has(text: String): Boolean = text.indexOf('<') >= 0 && TAG.containsMatchIn(text)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Applies the roleplay formatting (*actions*, **bold**, "dialogue") to text between
     * tags only, so attributes such as style="..." are never touched.
     */
    fun formatBody(text: String): String {
        val src = BLOCK_NL.replace(text) { it.groupValues[1] }
        val sb = StringBuilder(src.length + 64)
        var italic = false
        var bold = false
        var quote = false
        var raw = 0 // inside <style>/<script>/<pre>/<code>
        var last = 0
        fun formatText(t: String) {
            if (raw > 0) { sb.append(t); return }
            var i = 0
            while (i < t.length) {
                val c = t[i]
                when {
                    c == '*' && i + 1 < t.length && t[i + 1] == '*' -> { sb.append(if (bold) "</strong>" else "<strong>"); bold = !bold; i += 2; continue }
                    c == '*' -> sb.append(if (italic) "</em>" else "<em class=\"act\">").also { italic = !italic }
                    c == '"' || c == '\u201C' || c == '\u201D' -> {
                        if (!quote) { sb.append("<span class=\"q\">").append(c) } else { sb.append(c).append("</span>") }
                        quote = !quote
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
            if (RAW_OPEN.containsMatchIn(lower)) raw++
            else if (RAW_CLOSE.containsMatchIn(lower)) raw = (raw - 1).coerceAtLeast(0)
            sb.append(tag)
            last = m.range.last + 1
        }
        formatText(src.substring(last))
        if (quote) sb.append("</span>")
        if (italic) sb.append("</em>")
        if (bold) sb.append("</strong>")
        return sb.toString()
    }

    private val RAW_OPEN = Regex("^<(style|script|pre|code)\\b")
    private val RAW_CLOSE = Regex("^</(style|script|pre|code)\\b")

    private fun css(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

    fun document(text: String, fg: Color, palette: ChatPalette, link: Color, fontPx: Float, allowScripts: Boolean): String {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val scriptSrc = if (allowScripts) "'nonce-$nonce' 'unsafe-inline' 'unsafe-eval' *" else "'nonce-$nonce'"
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src * data: blob: 'unsafe-inline'; script-src $scriptSrc">
<style>
html,body{margin:0;padding:0;background:transparent;color:${css(fg)};font-family:sans-serif;font-size:${fontPx}px;line-height:1.45;overflow-wrap:anywhere;-webkit-text-size-adjust:none}
#hearth-root{white-space:pre-wrap}
#hearth-root div,#hearth-root table,#hearth-root details,#hearth-root ul,#hearth-root ol,#hearth-root section,#hearth-root article,#hearth-root figure,#hearth-root blockquote{white-space:normal}
em.act{color:${css(palette.action)};font-style:italic} span.q{color:${css(palette.quote)}}
img,video,svg{max-width:100%;height:auto} table{border-collapse:collapse;max-width:100%}
pre,code{white-space:pre-wrap;font-family:monospace;background:${css(palette.codeBg)};border-radius:4px}
a{color:${css(link)}} summary{cursor:pointer}
</style></head><body><div id="hearth-root">${formatBody(text)}</div>
<script nonce="$nonce">(function(){var r=document.getElementById('hearth-root');var last=-1;
function s(){var h=Math.ceil(r.getBoundingClientRect().height);if(h!==last){last=h;HearthBridge.height(h);}}
if(window.ResizeObserver){new ResizeObserver(s).observe(r);}window.addEventListener('load',s);
document.addEventListener('toggle',s,true);setTimeout(s,50);setTimeout(s,400);s();})();</script>
</body></html>"""
    }

    /** Escapes text for showing raw tags (used if HTML rendering is turned off). */
    fun escape(text: String) = esc(text)
}

/** Remembers measured heights so rows don't jump when scrolled back into view. */
private val heightCache = LruCache<String, Int>(512)

@SuppressLint("ViewConstructor", "SetJavaScriptEnabled", "JavascriptInterface")
private class HtmlView(ctx: Context) : WebView(ctx) {
    var onHeight: (Int) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())

    inner class Bridge {
        @JavascriptInterface
        fun height(h: Int) { main.post { onHeight(h) } }
    }

    init {
        setBackgroundColor(AColor.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.textZoom = 100
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addJavascriptInterface(Bridge(), "HearthBridge")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
                }
                return true
            }
        }
    }

    /** Forwards a tap from Compose so links and <details> work while scrolling stays with the list. */
    fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0)
        dispatchTouchEvent(down); dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }
}

/**
 * Renders one message as HTML in a WebView. Only messages that actually contain HTML
 * use this path; everything else stays on the fast native text renderer.
 */
@Composable
fun HtmlMessage(document: String, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val key = document.hashCode().toString() + ":" + document.length
    var height by remember(key) { mutableStateOf(heightCache.get(key) ?: 24) }
    val view = remember { arrayOfNulls<HtmlView>(1) }
    Box(modifier.fillMaxWidth().height(height.dp)) {
        AndroidView(
            factory = { ctx -> HtmlView(ctx).also { view[0] = it } },
            onReset = { it.tag = null },
            update = { wv ->
                view[0] = wv
                wv.onHeight = { h -> if (h > 0) { height = h; heightCache.put(key, h) } }
                if (wv.tag != key) {
                    wv.tag = key
                    wv.loadDataWithBaseURL("https://hearth.local/", document, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.matchParentSize(),
        )
        // Transparent layer: list scrolling keeps working; taps are forwarded to the page.
        Box(
            Modifier.matchParentSize().pointerInput(key) {
                detectTapGestures(
                    onTap = { o -> view[0]?.tap(o.x, o.y) },
                    onLongPress = { onLongPress() },
                )
            },
        )
    }
}
