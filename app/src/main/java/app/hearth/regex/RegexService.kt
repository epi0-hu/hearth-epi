package app.hearth.regex

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import app.hearth.core.Json
import app.hearth.core.KotlinRegexBatch
import app.hearth.core.Macros
import app.hearth.core.RegexBatch
import app.hearth.core.RegexEngine
import app.hearth.core.RegexItem
import app.hearth.core.RegexScript
import app.hearth.core.RegexTarget
import app.hearth.core.jsonOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Runs SillyTavern regex scripts. The primary engine is real JavaScript (a background
 * WebView running assets/regex_engine.js), so presets get exact JS RegExp behavior —
 * unbounded look-behind, named groups, every flag. If WebView JS is unavailable or fails,
 * it falls back to the built-in Kotlin engine.
 */
class RegexService(context: Context) {
    private val app = context.applicationContext
    private var web: WebView? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val loaded = LinkedHashSet<String>()
    private val lock = Mutex()
    @Volatile private var broken = false

    private var lastScripts: List<RegexScript>? = null
    private var lastJson: String = "[]"
    private var lastId: String = ""

    // ------------------------------------------------------------ public API

    suspend fun apply(
        scripts: List<RegexScript>,
        items: List<RegexItem>,
        target: RegexTarget,
        vars: Map<String, String>,
        variables: Map<String, String> = emptyMap(),
        isEdit: Boolean = false,
    ): List<String> {
        if (items.isEmpty()) return emptyList()
        if (scripts.isEmpty()) return items.map { it.text }
        if (!broken) {
            val r = runCatching { applyJs(scripts, items, target, vars, variables, isEdit) }
                .onFailure { Log.w(TAG, "JS regex failed, using fallback", it) }.getOrNull()
            if (r != null && r.size == items.size) return r
        }
        return withContext(Dispatchers.Default) {
            val ex = Macros(vars, variables = HashMap(variables))::expand
            items.map { RegexEngine.apply(scripts, it.text, it.placement, target, it.depth, ex, onlyRunOnEdit = isEdit) }
        }
    }

    suspend fun apply1(
        scripts: List<RegexScript>, text: String, placement: Int, target: RegexTarget, depth: Int?,
        vars: Map<String, String>, variables: Map<String, String> = emptyMap(), isEdit: Boolean = false,
    ): String = apply(scripts, listOf(RegexItem(text, placement, depth)), target, vars, variables, isEdit).first()

    /** A blocking adapter for the prompt builder. Must not be called on the main thread. */
    fun batchFor(scripts: List<RegexScript>): RegexBatch =
        if (Looper.myLooper() == Looper.getMainLooper()) KotlinRegexBatch(scripts)
        else RegexBatch { items, target, vars, variables -> runBlocking { apply(scripts, items, target, vars, variables) } }

    /** Why a pattern won't compile (as JavaScript), or null. */
    suspend fun check(src: String): String? {
        if (src.isBlank()) return null
        if (!broken) {
            val r = runCatching { eval("HearthRegex.check(" + Json.stringify(src) + ")") }.getOrNull()
            if (r != null) return if (r == "null") null else (Json.parse(r) as? String)
        }
        return RegexEngine.compileError(src)
    }

    /** Runs one script on sample text, for the editor's tester. */
    suspend fun test(script: RegexScript, text: String, vars: Map<String, String>): String {
        if (!broken) {
            val code = "HearthRegex.test(" + Json.stringify(script.toJson()) + "," + Json.stringify(text) + "," +
                Json.stringify(vars) + ")"
            val r = runCatching { eval(code) }.getOrNull()
            if (r != null && r != "null") return (Json.parse(r) as? String) ?: r
        }
        return RegexEngine.run(script, text, Macros(vars)::expand)
    }

    // ------------------------------------------------------------ JS host

    private suspend fun applyJs(
        scripts: List<RegexScript>, items: List<RegexItem>, target: RegexTarget,
        vars: Map<String, String>, variables: Map<String, String>, isEdit: Boolean,
    ): List<String>? {
        val id = ensureSet(scripts) ?: return null
        val req = jsonOf(
            "set" to id,
            "target" to when (target) { RegexTarget.DISPLAY -> "display"; RegexTarget.PROMPT -> "prompt"; RegexTarget.STORE -> "store" },
            "isEdit" to isEdit,
            "vars" to vars,
            "variables" to variables,
            "items" to items.map { jsonOf("t" to it.text, "p" to it.placement, "d" to it.depth) },
        )
        var raw = eval("JSON.stringify(HearthRegex.run(" + Json.stringify(req) + "))") ?: return null
        if (raw == "null" || raw == "\"null\"") {
            // The set was evicted on the JS side; reload once.
            lock.withLock { loaded.remove(id) }
            ensureSet(scripts) ?: return null
            raw = eval("JSON.stringify(HearthRegex.run(" + Json.stringify(req) + "))") ?: return null
        }
        // evaluateJavascript returns the JSON *encoding* of the string we produced.
        val json = Json.parse(raw) as? String ?: return null
        val list = Json.parse(json) as? List<*> ?: return null
        return list.map { (it as? String) ?: "" }
    }

    /** Makes sure the script list is loaded in the JS engine; returns its set id. */
    private suspend fun ensureSet(scripts: List<RegexScript>): String? {
        if (!start()) return null
        val (id, json) = lock.withLock {
            if (scripts !== lastScripts && scripts != lastScripts) {
                lastJson = Json.stringify(scripts.map { it.toJson() })
                lastId = Integer.toHexString(lastJson.hashCode()) + "_" + lastJson.length
                lastScripts = scripts
            }
            lastId to lastJson
        }
        if (lock.withLock { id in loaded }) return id
        lock.withLock {
            if (id in loaded) return id
            eval("HearthRegex.loadBegin(" + Json.stringify(id) + ")") ?: return null
            var i = 0
            while (i < json.length) {
                val part = json.substring(i, minOf(json.length, i + CHUNK))
                eval("HearthRegex.loadPart(" + Json.stringify(id) + "," + Json.stringify(part) + ")") ?: return null
                i += CHUNK
            }
            eval("HearthRegex.loadEnd(" + Json.stringify(id) + ")") ?: return null
            loaded.add(id)
            while (loaded.size > 5) loaded.remove(loaded.first())
        }
        return id
    }

    /** Starts the background WebView (once). Returns false if JS isn't usable. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun start(): Boolean {
        if (broken) return false
        val d = withContext(Dispatchers.Main) {
            ready?.let { return@withContext it }
            val def = CompletableDeferred<Boolean>()
            ready = def
            try {
                val js = app.assets.open("regex_engine.js").bufferedReader().use { it.readText() }
                val w = WebView(app)
                w.settings.javaScriptEnabled = true
                w.settings.allowFileAccess = false
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (def.isCompleted) return
                        view.evaluateJavascript(js + "\n;typeof HearthRegex") { r -> def.complete(r?.contains("object") == true) }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                        // The JS process died (e.g. low memory): drop it and start fresh next time.
                        if (web === view) { web = null; ready = null; loaded.clear() }
                        runCatching { view.destroy() }
                        return true
                    }
                }
                web = w
                w.loadDataWithBaseURL("https://hearth.local/", "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
            } catch (t: Throwable) {
                Log.w(TAG, "WebView unavailable", t)
                def.complete(false)
            }
            def
        }
        val ok = withTimeoutOrNull(8_000) { d.await() } ?: false
        if (!ok) broken = true
        return ok
    }

    private suspend fun eval(code: String): String? {
        if (!start()) return null
        return withTimeoutOrNull(15_000) {
            withContext(Dispatchers.Main) {
                val w = web ?: return@withContext null
                suspendCancellableCoroutine { cont ->
                    w.evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(r) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "HearthRegex"
        private const val CHUNK = 120_000
    }
}
