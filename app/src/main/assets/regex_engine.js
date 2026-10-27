/*
 * Hearth regex engine: runs SillyTavern regex scripts with real JavaScript RegExp, so
 * presets behave exactly as in SillyTavern (unbounded look-behind, named groups, flags…).
 * Mirrors SillyTavern's getRegexedString / runRegexScript / regexFromString.
 * Runs inside Android's WebView (V8); the same file is used by the unit tests under Node.
 */
var HearthRegex = (function () {
  'use strict';
  var sets = {};          // setId -> array of scripts
  var pending = {};       // setId -> chunks being loaded
  var regexCache = {};    // pattern source -> RegExp | null

  // ---------------------------------------------------------------- SillyTavern helpers
  function regexFromString(input) {
    if (Object.prototype.hasOwnProperty.call(regexCache, input)) {
      var c = regexCache[input];
      if (c) c.lastIndex = 0;
      return c;
    }
    var out;
    try {
      var m = input.match(/(\/?)(.+)\1([a-z]*)/i);
      if (m[3] && !/^(?!.*?(.).*?\1)[gmixXsuUAJ]+$/.test(m[3])) out = RegExp(input);
      else out = new RegExp(m[2], m[3]);
    } catch (e) { out = null; }
    regexCache[input] = out;
    return out;
  }

  function regexError(input) {
    try {
      var m = input.match(/(\/?)(.+)\1([a-z]*)/i);
      if (!m) return 'Empty pattern';
      if (m[3] && !/^(?!.*?(.).*?\1)[gmixXsuUAJ]+$/.test(m[3])) RegExp(input);
      else new RegExp(m[2], m[3]);
      return null;
    } catch (e) { return String(e && e.message || e); }
  }

  function sanitizeRegexMacro(x) {
    return (x && typeof x === 'string') ? x.replace(/[\n\r\t\v\f\0.^$*+?{}[\]\\/|()]/gs, function (s) {
      switch (s) {
        case '\n': return '\\n'; case '\r': return '\\r'; case '\t': return '\\t';
        case '\v': return '\\v'; case '\f': return '\\f'; case '\0': return '\\0';
        default: return '\\' + s;
      }
    }) : x;
  }

  // ---------------------------------------------------------------- macros (subset of substituteParams)
  function makeSubstitutor(vars, variables) {
    var lower = {};
    Object.keys(vars || {}).forEach(function (k) { lower[k.toLowerCase()] = String(vars[k]); });
    var store = {};
    Object.keys(variables || {}).forEach(function (k) { store[k] = String(variables[k]); });
    var ops = {};
    ['', 'chat', 'global'].forEach(function (s) {
      ops['set' + s + 'var'] = 'set'; ops['get' + s + 'var'] = 'get'; ops['add' + s + 'var'] = 'add';
      ops['inc' + s + 'var'] = 'inc'; ops['dec' + s + 'var'] = 'dec'; ops['has' + s + 'var'] = 'has';
      ops['flush' + s + 'var'] = 'flush';
    });
    function num(x) { var n = Number(x); return isNaN(n) ? null : n; }
    function resolve(body, raw) {
      var b = body.trim();
      if (b.indexOf('//') === 0) return '';
      var l = b.toLowerCase();
      if (Object.prototype.hasOwnProperty.call(lower, l)) return lower[l];
      if (l === 'newline') return '\n';
      if (l === 'noop') return '';
      if (l === 'trim') return '\u0000trim\u0000';
      var parts = b.split('::');
      var op = ops[parts[0].trim().toLowerCase()];
      if (op && parts.length >= 2) {
        var name = parts[1].trim(), val = parts.slice(2).join('::');
        switch (op) {
          case 'set': store[name] = val; return '';
          case 'get': return store[name] !== undefined ? store[name] : '';
          case 'has': return store[name] !== undefined ? 'true' : 'false';
          case 'flush': delete store[name]; return '';
          case 'add': var a = num(store[name]), c = num(val.trim());
            store[name] = (a !== null && c !== null && store[name] !== undefined) ? String(a + c) : (store[name] || '') + val; return '';
          case 'inc': store[name] = String((num(store[name]) || 0) + 1); return store[name];
          case 'dec': store[name] = String((num(store[name]) || 0) - 1); return store[name];
        }
      }
      if (/^(random|pick)::/i.test(b)) { var o = b.split('::').slice(1); return o[Math.floor(Math.random() * o.length)]; }
      if (/^(random|pick):/i.test(b)) { var o2 = b.slice(b.indexOf(':') + 1).split(','); return o2[Math.floor(Math.random() * o2.length)].trim(); }
      return raw;
    }
    return function substitute(text) {
      if (typeof text !== 'string' || text.indexOf('{{') < 0) return text;
      var t = text, prev;
      for (var i = 0; i < 6 && t !== prev; i++) {
        prev = t;
        t = t.replace(/\{\{([^{}]*)\}\}/g, function (m, body) { return resolve(body, m); });
      }
      if (t.indexOf('\u0000trim\u0000') >= 0) t = t.replace(/(?:\r?\n)*\u0000trim\u0000(?:\r?\n)*/g, '');
      return t;
    };
  }

  // ---------------------------------------------------------------- runRegexScript
  function filterString(rawString, trimStrings, sub) {
    var finalString = String(rawString);
    (trimStrings || []).forEach(function (t) {
      var s = sub(t);
      if (s) finalString = finalString.split(s).join('');
    });
    return finalString;
  }

  function runRegexScript(script, rawString, sub) {
    var newString = rawString;
    if (!script || script.disabled || !script.findRegex || !rawString) return newString;
    var src;
    switch (Number(script.substituteRegex || 0)) {
      case 1: src = sub(script.findRegex); break;
      case 2: src = script.findRegex.replace(/\{\{[^{}]*\}\}/g, function (m) { var v = sub(m); return v === m ? m : sanitizeRegexMacro(v); }); break;
      default: src = script.findRegex;
    }
    var findRegex = regexFromString(src);
    if (!findRegex) return newString;
    var replaceString = String(script.replaceString || '').replace(/{{match}}/gi, '$0');
    newString = rawString.replace(findRegex, function () {
      var args = Array.prototype.slice.call(arguments);
      var withGroups = replaceString.replace(/\$(\d+)|\$<([^>]+)>/g, function (_, n, groupName) {
        var match;
        if (n) match = args[Number(n)];
        else if (groupName) { var groups = args[args.length - 1]; match = groups && typeof groups === 'object' && groups[groupName]; }
        if (!match) return '';
        return filterString(match, script.trimStrings, sub);
      });
      return sub(withGroups);
    });
    return newString;
  }

  // ---------------------------------------------------------------- getRegexedString
  function applies(script, target, depth, isEdit, placement) {
    var md = !!script.markdownOnly, po = !!script.promptOnly;
    var ok = (md && target === 'display') || (po && target === 'prompt') || (!md && !po && target === 'store');
    if (!ok) return false;
    if (isEdit && !script.runOnEdit) return false;
    if (typeof depth === 'number') {
      var mn = script.minDepth, mx = script.maxDepth;
      if (mn !== null && mn !== undefined && mn !== '' && !isNaN(mn) && Number(mn) >= -1 && depth < Number(mn)) return false;
      if (mx !== null && mx !== undefined && mx !== '' && !isNaN(mx) && Number(mx) >= 0 && depth > Number(mx)) return false;
    }
    return Array.isArray(script.placement) && script.placement.indexOf(placement) >= 0;
  }

  function getRegexedString(scripts, rawString, placement, target, depth, sub, isEdit) {
    if (typeof rawString !== 'string' || !rawString) return rawString || '';
    var s = rawString;
    for (var i = 0; i < scripts.length; i++) {
      var sc = scripts[i];
      if (sc.disabled || !applies(sc, target, depth, isEdit, placement)) continue;
      try { s = runRegexScript(sc, s, sub); } catch (e) { /* a broken script never breaks the message */ }
    }
    return s;
  }

  // ---------------------------------------------------------------- public API (JSON in, JSON out)
  return {
    has: function (setId) { return !!sets[setId]; },
    loadBegin: function (setId) { pending[setId] = []; return true; },
    loadPart: function (setId, chunk) { (pending[setId] = pending[setId] || []).push(chunk); return true; },
    loadEnd: function (setId) {
      var list = JSON.parse((pending[setId] || []).join(''));
      delete pending[setId];
      sets[setId] = list;
      var ids = Object.keys(sets);
      if (ids.length > 6) delete sets[ids[0]];
      return list.length;
    },
    /**
     * req: { set, target: 'display'|'prompt'|'store', isEdit, vars: {}, variables: {},
     *        items: [{ t: text, p: placement, d: depth|null }] }  → array of strings
     */
    run: function (req) {
      var scripts = sets[req.set];
      if (!scripts) return null;
      var sub = makeSubstitutor(req.vars, req.variables);
      return req.items.map(function (it) {
        return getRegexedString(scripts, it.t, it.p, req.target, (it.d === null || it.d === undefined) ? undefined : it.d, sub, !!req.isEdit);
      });
    },
    check: function (src) { return regexError(src); },
    /** Runs one script on a sample (regex editor's tester). */
    test: function (script, text, vars) {
      try { return runRegexScript(script, text, makeSubstitutor(vars, {})); } catch (e) { return 'Error: ' + e.message; }
    },
  };
})();
if (typeof module !== 'undefined') module.exports = HearthRegex;
