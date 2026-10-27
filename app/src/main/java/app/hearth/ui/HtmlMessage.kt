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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.hearth.core.HtmlFormat
import java.util.UUID

/** Detects messages that should be rendered as HTML instead of plain formatted text. */
object Html {
    private val TAG = Regex(
        "<(div|span|p|br|hr|details|summary|table|thead|tbody|tr|td|th|img|style|font|center|ul|ol|li|b|i|u|s|em|strong|" +
            "h[1-6]|pre|code|blockquote|a|section|article|small|sup|sub|mark|ruby|rt|q|kbd|figure|figcaption|svg|progress|meter)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    fun has(text: String): Boolean = text.indexOf('<') >= 0 && TAG.containsMatchIn(text)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun formatBody(text: String): String = HtmlFormat.formatBody(text)


    private fun css(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

    private val MD_IMAGE = Regex("!\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)")

    /** Turns markdown images ![alt](url) into <img> tags so they display. */
    fun markdownImages(text: String): String {
        if (text.indexOf("![") < 0) return text
        return MD_IMAGE.replace(text) { m ->
            val alt = m.groupValues[1].replace("\"", "&quot;")
            val url = m.groupValues[2].replace("\"", "%22")
            "<img class=\"md\" src=\"$url\" alt=\"$alt\" loading=\"eager\">"
        }
    }

    /**
     * The page each HTML message lives in. It's loaded once per WebView; message content is
     * then swapped in through hearthSet(), so streaming updates don't reload the page.
     */
    fun shell(fg: Color, palette: ChatPalette, link: Color, fontPx: Float, allowScripts: Boolean, serif: Boolean = false): String {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val scriptSrc = if (allowScripts) "'nonce-$nonce' 'unsafe-inline' 'unsafe-eval' *" else "'nonce-$nonce'"
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta http-equiv="Content-Security-Policy" content="default-src * data: blob: 'unsafe-inline'; script-src $scriptSrc">
<style>
html,body{margin:0;padding:0;background:transparent;color:${css(fg)};font-family:${if (serif) "serif" else "sans-serif"};font-size:${fontPx}px;line-height:1.55;overflow-wrap:anywhere;-webkit-text-size-adjust:none}
#hearth-root{display:flow-root;padding:2px 2px 8px 2px;box-sizing:border-box}
#hearth-root img,#hearth-root table,#hearth-root pre,#hearth-root div,#hearth-root details,#hearth-root section,#hearth-root article{max-width:100%;box-sizing:border-box}
em.act{color:${css(palette.action)};font-style:italic} span.q{color:${css(palette.quote)}}
/* Text the model or a regex colored itself keeps its color (same rule as SillyTavern). */
font[color] em.act,font[color] span.q,font[color] strong,[style*="color"] span.q,[style*="color"] em.act{color:inherit}
img,video,svg{max-width:100%;height:auto} table{border-collapse:collapse;max-width:100%}
img.md{display:block;margin:6px 0;border-radius:14px;max-height:70vh;object-fit:contain}
.hearth-caret{color:${css(palette.ember)}}
pre,code{white-space:pre-wrap;font-family:monospace;background:${css(palette.codeBg)};border-radius:4px}
a{color:${css(link)}} summary{cursor:pointer}
</style></head><body><div id="hearth-root"></div>
<script nonce="$nonce">(function(){
var r=document.getElementById('hearth-root'),last=-1,pad=0,queued=false,runScripts=${if (allowScripts) "true" else "false"};
function fit(){
  r.style.zoom='';
  var cw=document.documentElement.clientWidth,sw=r.scrollWidth;
  if(sw>cw+1){r.style.zoom=(cw/sw).toFixed(4);}
}
// Walks visible elements, clipping each subtree to any ancestor with overflow other than
// "visible" — collapsed panels (max-height:0; overflow:hidden) then take up no space.
function walk(el,clip,acc){
  var kids=el.children;
  for(var i=0;i<kids.length;i++){
    var k=kids[i],cs=getComputedStyle(k);
    if(cs.display==='none'||cs.visibility==='hidden'||parseFloat(cs.opacity)===0||cs.position==='fixed')continue;
    var b=k.getBoundingClientRect(),t=b.top,bt=b.bottom;
    if(clip){if(t<clip.t)t=clip.t;if(bt>clip.b)bt=clip.b;}
    if(b.width>0&&bt>t){if(t<acc.min)acc.min=t;if(bt>acc.max)acc.max=bt;}
    var c=clip;
    if(cs.overflowX!=='visible'||cs.overflowY!=='visible'){c={t:t,b:bt};}
    if(c&&c.b<=c.t)continue;
    if(k.children.length)walk(k,c,acc);
  }
}
function measure(){
  queued=false;
  fit();
  var base=r.getBoundingClientRect(),top0=base.top+2+pad;
  var acc={min:top0,max:base.bottom};
  walk(r,null,acc);
  // Pop-overs above the first line get room instead of being clipped.
  var want=Math.ceil(Math.max(0,top0-acc.min));
  if(want!==pad){pad=want;r.style.paddingTop=(2+pad)+'px';queue();return;}
  var h=Math.ceil(acc.max-base.top+4);
  if(h!==last){last=h;HearthBridge.height(h);}
}
function queue(){if(!queued){queued=true;requestAnimationFrame(measure);}}
window.hearthSet=function(html,fin){
  r.innerHTML=html;
  if(fin&&runScripts){
    var ss=r.querySelectorAll('script');
    for(var i=0;i<ss.length;i++){var o=ss[i],n=document.createElement('script');
      for(var j=0;j<o.attributes.length;j++){n.setAttribute(o.attributes[j].name,o.attributes[j].value);}
      n.text=o.text;o.parentNode.replaceChild(n,o);}
  }
  queue();setTimeout(queue,120);
};
if(window.ResizeObserver){var ro=new ResizeObserver(queue);ro.observe(r);ro.observe(document.body);}
if(window.MutationObserver){new MutationObserver(function(recs){for(var i=0;i<recs.length;i++){if(recs[i].target!==r||recs[i].type==='childList'){queue();return;}}}).observe(r,{attributes:true,childList:true,subtree:true,characterData:true});}
['load','toggle','transitionend','animationend','error'].forEach(function(e){document.addEventListener(e,queue,true);});
document.addEventListener('click',function(){setTimeout(queue,60);setTimeout(queue,400);},true);
if(document.fonts&&document.fonts.ready){document.fonts.ready.then(queue);}
setTimeout(queue,400);setTimeout(queue,1500);
})();</script>
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
    var shellKey: Int? = null
    private var loaded = false
    private var pending: Pair<String, Boolean>? = null
    var shownBody: String? = null
    var shownFinal = false

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

            override fun onPageFinished(view: WebView, url: String?) {
                loaded = true
                pending?.let { (b, f) -> pending = null; push(b, f) }
            }
        }
    }

    fun loadShell(shell: String) {
        val k = shell.hashCode()
        if (shellKey == k) return
        shellKey = k
        loaded = false
        shownBody = null
        loadDataWithBaseURL("https://hearth.local/", shell, "text/html", "utf-8", null)
    }

    /** Swaps in new message content without reloading the page. */
    fun setBody(body: String, final: Boolean) {
        if (body == shownBody && final == shownFinal) return
        shownBody = body
        shownFinal = final
        if (loaded) push(body, final) else pending = body to final
    }

    private fun push(body: String, final: Boolean) {
        evaluateJavascript("window.hearthSet&&window.hearthSet(" + app.hearth.core.Json.stringify(body) + "," + final + ")", null)
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
 * Renders one message as HTML in a WebView. Only messages that actually contain HTML (or
 * images) use this path; everything else stays on the fast native text renderer. While a
 * reply streams, new content is pushed into the already-loaded page each frame.
 */
@Composable
fun HtmlMessage(body: String, shell: String, final: Boolean, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val cacheKey = if (final) body.hashCode().toString() + ":" + body.length else null
    var height by remember { mutableStateOf(cacheKey?.let { heightCache.get(it) } ?: 24) }
    val view = remember { arrayOfNulls<HtmlView>(1) }
    val longPress by rememberUpdatedState(onLongPress)
    Box(modifier.fillMaxWidth().height(height.dp)) {
        AndroidView(
            factory = { ctx -> HtmlView(ctx).also { view[0] = it } },
            onReset = { it.shownBody = null },
            update = { wv ->
                view[0] = wv
                wv.onHeight = { h -> if (h > 0) { height = h; if (cacheKey != null) heightCache.put(cacheKey, h) } }
                wv.loadShell(shell)
                wv.setBody(body, final)
            },
            modifier = Modifier.matchParentSize(),
        )
        // Transparent layer: list scrolling keeps working; taps are forwarded to the page.
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> view[0]?.tap(o.x, o.y) },
                    onLongPress = { longPress() },
                )
            },
        )
    }
}
