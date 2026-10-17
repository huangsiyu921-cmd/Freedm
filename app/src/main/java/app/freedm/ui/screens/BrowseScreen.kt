package app.freedm.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.freedm.browser.AdBlocker
import app.freedm.browser.SniffedMedia
import app.freedm.browser.Sniffer
import app.freedm.data.HistoryStore
import app.freedm.data.Repo
import app.freedm.diag.Diag
import app.freedm.engine.Engine
import app.freedm.engine.FileNamer
import app.freedm.engine.looksLikeDownload
import app.freedm.engine.Prober
import app.freedm.service.DownloadService
import app.freedm.ui.BrowserBus
import app.freedm.ui.formatBytes
import app.freedm.ui.firstUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Only real pages are restorable.
 *
 * Domestic sites hand out deeplinks (`baiduboxapp://`, `weixin://`, `intent://`);
 * storing one in the tab list or in `last_page` means the next start restores an
 * error page instead of the page the user was reading.
 */
private fun httpOnly(url: String?): String? =
    url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

/**
 * One browser tab: address, title, and its own history.
 *
 * The history is kept here, not in the WebView, because the app runs a single
 * WebView for all tabs — using the WebView's back/forward list meant every tab
 * shared one history, so going back in tab 2 could land on a page from tab 1.
 */
private data class TabEntry(
    val url: String,
    val title: String,
    /** Pages visited in this tab, oldest first. */
    val history: List<String> = emptyList(),
    /** Index into [history] of the page on screen; -1 for a fresh tab. */
    val cursor: Int = -1,
)

/**
 * Desktop mode needs more than a desktop UA: sites with responsive CSS still lay
 * out to the phone viewport, so GitHub looked mobile. Widen the layout viewport
 * and keep a minimum page width.
 */
private const val DESKTOP_VIEWPORT_JS = """
(function() {
  var w = 1280;
  var scale = (window.innerWidth > 0 ? window.innerWidth / w : 1).toFixed(3);
  var m = document.querySelector('meta[name="viewport"]');
  if (!m) { m = document.createElement('meta'); m.setAttribute('name', 'viewport'); document.head.appendChild(m); }
  m.setAttribute('content', 'width=' + w + ', initial-scale=' + scale);
  document.documentElement.style.minWidth = w + 'px';
})();
"""

private const val UA_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

/**
 * Dumps the request body of the MFA endpoints from the page's own bundle.
 *
 * `create_verification_code` answers with a gateway-level success and puts the real
 * outcome in `data.biz_code`, so a wrong request shape looks like a working one until
 * no mail arrives. The call site carries the exact field names.
 */
private const val MFA_CALL_JS = """
(function() {
  if (window.__freedmMfaCall) return;
  window.__freedmMfaCall = true;
  var urls = performance.getEntriesByType('resource')
    .map(function(e) { return e.name })
    .filter(function(n) { return /\.js(\?|$)/.test(n); });
  var done = false;
  urls.forEach(function(u) {
    if (done) return;
    fetch(u).then(function(r) { return r.text(); }).then(function(t) {
      if (done) return;
      var i = t.indexOf('/mfa/create_verification_code');
      if (i < 0) return;
      done = true;
      console.error('FREEDM_MFA_CREATE ' + t.slice(i, i + 320).replace(/\s+/g, ' '));
      var j = t.indexOf('/mfa/check_verification_code');
      if (j >= 0) console.error('FREEDM_MFA_CHK ' + t.slice(j, j + 320).replace(/\s+/g, ' '));
    }).catch(function() {});
  });
})();
"""

/**
 * Guards against promises that never settle in Android WebView.
 *
 * `navigator.credentials.*` (Credential Management) is not implemented there: calls do
 * not throw and do not reject, they just never settle. A login flow that awaits one
 * shows a spinner forever, with no error and no further request — which is exactly what
 * DeepSeek's "验证账号" screen does. Service worker registration is the other classic:
 * `ready` hangs if the registration never activates. Both are raced against a timeout so
 * the page can move on, and every call is logged.
 */
private const val PENDING_GUARD_JS = """
(function() {
  if (window.__freedmPendingGuard) return;
  window.__freedmPendingGuard = true;
  function log(m) { try { console.error('FREEDM_GUARD ' + m); } catch (_) {} }
  try {
    var c = navigator.credentials;
    if (!c) {
      log('credentials=absent');
    } else {
      ['store', 'get', 'create'].forEach(function(k) {
        var f = c[k];
        if (typeof f !== 'function') { log('cred.' + k + '=missing'); return; }
        c[k] = function() {
          log('cred.' + k + ' called');
          var p;
          try {
            p = f.apply(c, arguments);
          } catch (e) {
            log('cred.' + k + ' threw');
            return Promise.resolve(null);
          }
          if (!p || typeof p.then !== 'function') return p;
          return Promise.race([p, new Promise(function(res) {
            setTimeout(function() { log('cred.' + k + ' stuck'); res(null); }, 2500);
          })]);
        };
      });
    }
  } catch (e) { log('cred patch failed'); }
  try {
    if (navigator.serviceWorker) {
      navigator.serviceWorker.getRegistrations().then(function(r) {
        log('sw regs=' + r.length);
      }, function() { log('sw regs error'); });
      navigator.serviceWorker.ready.then(function() { log('sw ready'); });
    } else {
      log('sw absent');
    }
  } catch (e) { log('sw probe failed'); }
})();
"""

/**
 * Bridge for finishing a login that the page itself abandons.
 *
 * DeepSeek answers the password step with `MFA_VERIFICATION_CODE_REQUIRED` and an
 * `mfa_session_id`, then never builds the input for the code — the page just sits on an
 * empty overlay. The two endpoints it would have called are known, so the app asks for
 * the code itself and calls them directly.
 */
private const val MFA_BRIDGE_JS = """
(function() {
  if (window.__freedmMfaBridge) return;
  window.__freedmMfaBridge = true;
  function post(path, body) {
    return fetch(path, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function(r) { return r.json(); });
  }
  window.__freedmMfaSend = function() {
    var sid = window.__freedmMfaSid;
    if (!sid) { console.error('FREEDM_MFA_SEND_ERR no-sid'); return; }
    post('/auth-api/v0/users/mfa/create_verification_code', { mfa_session_id: sid })
      .then(function(j) {
        // Two layers again: `code` is the gateway, `data.biz_code` is the real answer
        // (rate limiting, "already sent", …).
        var bd = j.data || {};
        console.error('FREEDM_MFA_SEND ' + JSON.stringify({
          code: j.code,
          biz: bd.biz_code,
          msg: String(bd.biz_msg || j.msg || '').slice(0, 60)
        }));
      })
      .catch(function(e) { console.error('FREEDM_MFA_SEND_ERR ' + String(e).slice(0, 80)); });
  };
  window.__freedmMfaCheck = function(input) {
    var sid = window.__freedmMfaSid;
    if (!sid) { console.error('FREEDM_MFA_CHECK_ERR no-sid'); return; }
    post('/auth-api/v0/users/mfa/check_verification_code', {
      mfa_session_id: sid,
      verification_code: input
    }).then(function(j) {
      console.error('FREEDM_MFA_CHECK ' + JSON.stringify({
        code: j.code, msg: String(j.msg || '').slice(0, 60)
      }));
      if (j.code === 0) {
        try { location.href = '/usage'; } catch (_) { try { location.reload(); } catch (_) {} }
      }
    }).catch(function(e) { console.error('FREEDM_MFA_CHECK_ERR ' + String(e).slice(0, 80)); });
  };
})();
"""

private const val PAGE_ENV_JS = """(function() {
  try {
    var f = document.querySelector('input:not([type=hidden]),textarea,[contenteditable=true]');
    var v = document.querySelector('meta[name=viewport]');
    return JSON.stringify({
      field: f ? (f.tagName + '/' + (f.type || '') + '/' + (f.isContentEditable ? 'ce' : '')) : 'none',
      vp: v ? v.getAttribute('content') : 'none',
      w: window.innerWidth,
      h: window.innerHeight,
      dpr: window.devicePixelRatio,
      uad: (navigator.userAgentData && navigator.userAgentData.brands)
        ? navigator.userAgentData.brands.map(function (b) { return b.brand }).join(',')
        : 'none',
      ua: navigator.userAgent.slice(0, 90)
    });
  } catch (e) { return 'err:' + e.message; }
})();
"""

/** Upper bound on sniffed candidates; a long browsing session must not grow forever. */
private const val MAX_SNIFFED = 400

/**
 * Works around a WebView quirk: after an IME insert the caret stays at 0, so every
 * following character lands in front of the previous one (typing "asdf" shows up as
 * "fdsa"). Deletions are unaffected, which is the giveaway — the caret really sits at
 * position 0 instead of the value being rearranged afterwards.
 *
 * The fix runs a tick after the insert so the page's own handlers have had their go,
 * and only touches fields that are still at position 0. Logging rides along when
 * diagnostics are enabled, since the page and the app share no other channel.
 */
private const val INPUT_FIX_JS = """
(function() {
  if (window.__freedmInputFix) return;
  window.__freedmInputFix = true;
  document.addEventListener('input', function(e) {
    var t = e.target;
    if (!t || typeof t.value !== 'string') return;
    if (String(e.inputType || '').indexOf('insert') !== 0) return;
    if (t.selectionStart !== 0 || t.selectionEnd !== 0) return;
    setTimeout(function() {
      try {
        if (t.selectionStart === 0 && t.selectionEnd === 0 && t.value) {
          t.setSelectionRange(t.value.length, t.value.length);
        }
        if (window.__freedmInputLog) {
          console.error('FREEDM_INPUT ' + JSON.stringify({
            it: e.inputType || '',
            d: (e.data || '').slice(0, 12),
            ss: t.selectionStart,
            len: t.value.length,
            head: t.value.slice(0, 24)
          }));
        }
      } catch (_) {}
    }, 0);
  }, true);
  // Flipped on by the diagnostics probe, so a release build does not log keystrokes.
  window.__freedmInputLog = false;
})();
"""

/**
 * Records the page's own network calls (fetch / XHR) with their status or failure, and
 * — for the login call only — what came back and what the screen shows a moment later.
 * A login that spins forever after a 200 means the page gave up on its own, and the
 * page text at that point says why ("verify", "network error", …). Response bodies are
 * never logged, only the top-level keys.
 */
private const val NET_PROBE_JS = """
(function() {
  if (window.__freedmNetProbe) return;
  window.__freedmNetProbe = true;
  window.__freedmInputLog = true;
  function r(u) { return String(u || '').slice(0, 90); }
  function isLogin(u) { return String(u || '').indexOf('/login') >= 0; }
  // Unhandled rejections and errors are what leaves a page spinning after a successful
  // call: the await never resolves because something blew up where nobody catches it.
  window.addEventListener('unhandledrejection', function(e) {
    try {
      var r = e.reason;
      console.error('FREEDM_REJECT ' + String((r && (r.stack || r.message)) || r).slice(0, 220));
    } catch (_) {}
  });
  window.addEventListener('error', function(e) {
    try {
      console.error('FREEDM_ERROR ' + String(e.message).slice(0, 160) +
        ' @' + String(e.filename || '').slice(0, 70) + ':' + e.lineno);
    } catch (_) {}
  });
  function afterLogin() {
    setTimeout(function() {
      try {
        var m = document.querySelector(
          '[role=dialog],[class*=modal],[class*=Modal],[class*=dialog],[class*=Dialog],[class*=mask],[class*=Mask]'
        );
        var modal = 'none';
        if (m) {
          var cs = getComputedStyle(m);
          var kid = m.firstElementChild;
          var par = m.parentElement;
          modal = {
            cls: String(m.className || '').slice(0, 46),
            op: cs.opacity, vis: cs.visibility, z: cs.zIndex,
            w: m.offsetWidth, h: m.offsetHeight,
            kids: m.children.length,
            kid: kid ? (kid.tagName + '.' + String(kid.className || '').slice(0, 34)) : 'none',
            html: (m.innerHTML || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').slice(0, 120),
            par: par
              ? (String(par.className || '').slice(0, 30) + ' kids=' + par.children.length +
                 ' txt=' + (par.innerText || '').replace(/\s+/g, ' ').slice(0, 90))
              : 'none'
          };
        }
        console.error('FREEDM_AFTER_LOGIN ' + JSON.stringify({
          url: String(location.href).slice(0, 80),
          modal: modal,
          frames: Array.prototype.slice.call(document.querySelectorAll('iframe')).slice(0, 4)
            .map(function(f) {
              return String(f.src || '(blank)').slice(0, 46) + ' ' + f.offsetWidth + 'x' + f.offsetHeight;
            }).join(' || '),
          canvas: document.querySelectorAll('canvas').length,
          ck: document.cookie.length,
          ls: (function() { try { return Object.keys(localStorage).length } catch (_) { return -1 } })()
        }));
      } catch (_) {}
    }, 2500);
  }
  // Sockets and server-sent events never pass through fetch/XHR, so a login waiting on
  // one of those looks like "no request at all".
  try {
    var OW = window.WebSocket;
    if (OW) {
      window.WebSocket = function(u, p) {
        console.error('FREEDM_WS ' + String(u || '').slice(0, 80));
        var ws = new OW(u, p);
        ws.addEventListener('error', function() {
          console.error('FREEDM_WS_ERR ' + String(u || '').slice(0, 60));
        });
        ws.addEventListener('close', function(e) {
          console.error('FREEDM_WS_CLOSE ' + e.code);
        });
        return ws;
      };
      window.WebSocket.prototype = OW.prototype;
    }
  } catch (_) {}
  try {
    var OE = window.EventSource;
    if (OE) {
      window.EventSource = function(u, c) {
        console.error('FREEDM_SSE ' + String(u || '').slice(0, 80));
        return new OE(u, c);
      };
      window.EventSource.prototype = OE.prototype;
    }
  } catch (_) {}
  var of = window.fetch;
  if (of) {
    window.fetch = function(input, init) {
      var url = r(typeof input === 'string' ? input : (input && input.url));
      var p = of.apply(this, arguments);
      try {
        p.then(function(res) {
          console.error('FREEDM_FETCH ' + JSON.stringify({ u: url, s: res.status }));
          if (isLogin(url)) {
            try {
              res.clone().text().then(function(t) {
                var k = 'non-json';
                try {
                  var o = JSON.parse(t);
                  k = Array.isArray(o) ? 'array' : Object.keys(o).slice(0, 8).join(',');
                } catch (_) {}
                console.error('FREEDM_LOGIN_KEYS ' + k);
              });
            } catch (_) {}
            afterLogin();
          }
        }, function(err) {
          console.error('FREEDM_FETCH_ERR ' + JSON.stringify({
            u: url, m: String(err && err.message).slice(0, 70)
          }));
        });
      } catch (_) {}
      return p;
    };
  }
  var oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(m, u) {
    var url = r(u);
    try {
      this.addEventListener('loadend', function() {
        console.error('FREEDM_XHR ' + JSON.stringify({
          u: url, s: this.status, rs: this.readyState
        }));
        if (isLogin(url)) {
          try {
            var k = 'non-json';
            var o = JSON.parse(this.responseText);
            k = Array.isArray(o) ? 'array' : Object.keys(o).slice(0, 8).join(',');
            if (o && typeof o === 'object') {
              if ('code' in o) k += ' code=' + o.code;
              var d = o.data;
              if (d === null) k += ' data=null';
              else if (typeof d !== 'object') k += ' data=' + typeof d;
              else {
                k += ' data=' + Object.keys(d).slice(0, 10).join('/');
                if ('biz_code' in d) k += ' biz=' + d.biz_code;
                if (d.biz_msg) k += ' msg=' + String(d.biz_msg).slice(0, 60);
                var bd = d.biz_data;
                k += ' bd=' + (bd && typeof bd === 'object'
                  ? Object.keys(bd).slice(0, 8).join('/')
                  : String(bd).slice(0, 40));
                if (bd && bd.mfa_session_id) {
                  window.__freedmMfaSid = String(bd.mfa_session_id);
                  console.error('FREEDM_MFA_REQUIRED ' + window.__freedmMfaSid);
                }
              }
            }
            console.error('FREEDM_LOGIN_KEYS ' + k.slice(0, 400));
          } catch (_) {}
          afterLogin();
        }
      });
      this.addEventListener('error', function() {
        console.error('FREEDM_XHR_ERR ' + JSON.stringify({ u: url }));
      });
    } catch (_) {}
    return oo.apply(this, arguments);
  };
})();
"""

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowseScreen(onOpenHistory: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { Repo.prefs() }
    val scope = rememberCoroutineScope()

    var address by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(0f) }
    val media = remember { mutableStateListOf<SniffedMedia>() }
    val selected = remember { mutableStateListOf<String>() }
    var showMedia by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The start page is a Compose overlay rather than a loaded URL, so the first
    // thing on screen is never a blank WebView.
    var showStart by remember { mutableStateOf(true) }

    var menuOpen by remember { mutableStateOf(false) }
    // Tabs keep only the URL/title; the single WebView reloads on switch. They are
    // persisted so a restart comes back to the same set of pages.
    val tabs = remember {
        mutableStateListOf<TabEntry>().also { list ->
            runCatching {
                val arr = org.json.JSONArray(prefs.getString("browser_tabs", null) ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val hist = o.optJSONArray("history")?.let { h ->
                        (0 until h.length()).map { idx -> h.optString(idx) }
                    }.orEmpty()
                    list.add(
                        TabEntry(
                            url = o.optString("url"),
                            title = o.optString("title"),
                            history = hist,
                            cursor = o.optInt(
                                "cursor",
                                if (hist.isEmpty()) -1 else hist.lastIndex,
                            ),
                        ),
                    )
                }
            }
            if (list.isEmpty()) list.add(TabEntry("", ""))
        }
    }
    var activeTab by remember {
        mutableIntStateOf(prefs.getInt("browser_active_tab", 0).coerceIn(0, tabs.lastIndex))
    }
    var showTabs by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }

    // Back/forward come from the tab's own history, so they are derived, not
    // mirrored from the WebView (which only knows about one global stack).
    val activeEntry = tabs.getOrNull(activeTab)
    val canGoBack = !showStart && (activeEntry?.history?.isNotEmpty() == true)
    val canGoForward = !showStart &&
        activeEntry != null && activeEntry.cursor < activeEntry.history.lastIndex

    // WebView's own UA, cleaned of the two markers that give away an embedded
    // WebView: "; wv" and "Version/4.0". Sites that spot either serve a legacy
    // branch — the one whose inputs re-set their value on every keystroke (characters
    // land in reverse) and whose login never finishes. Everything else about the UA
    // stays real, so page behaviour still matches the engine rendering it.
    val defaultUa = remember {
        android.webkit.WebSettings.getDefaultUserAgent(ctx)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }

    fun persistTabs() {
        runCatching {
            val arr = org.json.JSONArray()
            tabs.forEach { t ->
                arr.put(
                    org.json.JSONObject().apply {
                        put("url", t.url)
                        put("title", t.title)
                        put("cursor", t.cursor)
                        put("history", org.json.JSONArray().also { h -> t.history.forEach(h::put) })
                    },
                )
            }
            prefs.edit()
                .putString("browser_tabs", arr.toString())
                .putInt("browser_active_tab", activeTab)
                .apply()
        }
    }
    var sourceText by remember { mutableStateOf<String?>(null) }
    var cookieImport by remember { mutableStateOf(false) }
    var cookieText by remember { mutableStateOf("") }
    // Set when the page reports that the account needs a verification code it never
    // asks for; drives the two-step dialog below.
    var mfaSid by remember { mutableStateOf<String?>(null) }
    var mfaCode by remember { mutableStateOf("") }
    // True between an explicit navigation and the page it lands on. Redirects call
    // `onPageFinished` again with a different URL, and those must replace the entry
    // instead of stacking — otherwise 返回 lands on the same page's redirect.
    var freshNav by remember { mutableStateOf(false) }

    var showCookies by remember { mutableStateOf(false) }
    var snifferEnabled by remember { mutableStateOf(prefs.getBoolean("sniff", true)) }
    var desktopUa by remember { mutableStateOf(prefs.getBoolean("desktop_ua", false)) }

    // Ad blocking state mirrored for the menu. The blocking decision itself reads
    // prefs directly, because shouldInterceptRequest runs off the main thread.
    var adblockOn by remember { mutableStateOf(AdBlocker.isEnabled()) }
    var siteAllowed by remember { mutableStateOf(false) }
    var blockedCount by remember { mutableLongStateOf(0L) }
    // WebView calls shouldInterceptRequest on its own threads; Compose state may
    // only be touched from the main thread, so sniffed media is handed over here.
    val sniffQueue = remember { ConcurrentLinkedQueue<SniffedMedia>() }
    val pageHost = remember { AtomicReference<String?>(null) }
    // Per-page counters for the diagnostic log (see docs/logic-spec.md §20).
    val pageStartedAt = remember { AtomicLong(0) }
    val sniffNetCount = remember { AtomicLong(0) }
    // Every subresource request for the current page, sniffed or not. This is the
    // number that tells whether a page loaded its CSS/JS at all — a "finished in
    // 89 ms with res=2" page is one that rendered almost nothing.
    val resCount = remember { AtomicLong(0) }
    val blockedAtStart = remember { AtomicLong(0) }
    val lastDrainLogAt = remember { AtomicLong(0) }

    fun absorb(list: List<SniffedMedia>) {
        val existing = media.map { it.key }.toHashSet()
        list.forEach {
            if (media.size >= MAX_SNIFFED) return
            if (existing.add(it.key)) media.add(it)
        }
    }

    fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val target = if (text.startsWith("http://") || text.startsWith("https://")) {
            text
        } else if (text.contains('.') && !text.contains(' ')) {
            "https://$text"
        } else {
            val engine = prefs.getString("search_engine", SEARCH_DEFAULT) ?: SEARCH_DEFAULT
            engine + java.net.URLEncoder.encode(text, "UTF-8")
        }
        showStart = false
        freshNav = true
        webView?.loadUrl(target)
    }

    fun showStartPage() {
        webView?.loadUrl("about:blank")
        address = ""
        media.clear()
        findOpen = false
        showStart = true
        // The tab becomes a blank tab again. Keeping the old address is what made
        // the tab list show the page the user just backed out of, and the next
        // launch reopen it.
        val cur = tabs.getOrNull(activeTab)
        if (cur != null && (cur.url.isNotEmpty() || cur.title.isNotEmpty() || cur.history.isNotEmpty())) {
            tabs[activeTab] = TabEntry("", "")
            persistTabs()
        }
    }

    /** Loads one entry of the active tab's own history and moves its cursor there. */
    fun openHistoryAt(index: Int) {
        val cur = tabs.getOrNull(activeTab) ?: return
        val target = cur.history.getOrNull(index) ?: return
        tabs[activeTab] = cur.copy(cursor = index)
        persistTabs()
        showStart = false
        address = target
        webView?.loadUrl(target)
    }

    /** Back inside this tab's history; past the first page it returns to the start page. */
    fun goBackSmart() {
        val cur = tabs.getOrNull(activeTab)
        if (cur != null && cur.cursor > 0) openHistoryAt(cur.cursor - 1)
        else if (!showStart) showStartPage()
    }

    fun goForwardSmart() {
        val cur = tabs.getOrNull(activeTab) ?: return
        if (cur.cursor in 0 until cur.history.lastIndex) openHistoryAt(cur.cursor + 1)
    }

    /** Switching tabs reloads the page the tab was left on, from its own history. */
    fun switchToTab(i: Int) {
        val tab = tabs.getOrNull(i) ?: return
        activeTab = i
        persistTabs()
        val target = httpOnly(tab.history.getOrNull(tab.cursor) ?: tab.url)
        if (target == null) {
            showStart = true
            address = ""
            webView?.loadUrl("about:blank")
        } else {
            showStart = false
            address = target
            webView?.loadUrl(target)
        }
    }

    /** Third-party cookies are per-WebView, and the switch can change at runtime. */
    fun applyCookiePolicy(wv: WebView) {
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(
                wv,
                prefs.getBoolean("third_party_cookies", true),
            )
        }
    }

    // The history page is a separate screen; it hands the row back through the bus.
    val pendingOpen by BrowserBus.open.collectAsState()
    LaunchedEffect(pendingOpen) {
        val url = pendingOpen ?: return@LaunchedEffect
        BrowserBus.consume()
        navigate(url)
    }

    fun setDesktop(on: Boolean) {
        desktopUa = on
        prefs.edit().putBoolean("desktop_ua", on).apply()
        webView?.let {
            it.settings.userAgentString = if (on) UA_DESKTOP else defaultUa
            // Re-request instead of reload: the UA decides what the server renders.
            val url = it.url
            if (url != null) it.loadUrl(url) else it.reload()
        }
    }

    /**
     * Creates one task per selected candidate, then hands them to the queue.
     * Uses `pump()` rather than `start()` per item so `max_concurrent` still holds.
     */
    fun addSelected() {
        val picked = media.filter { it.key in selected }
        if (picked.isEmpty()) return
        scope.launch {
            var added = 0
            withContext(Dispatchers.IO) {
                picked.forEach { item ->
                    runCatching {
                        val probe = runCatching { Prober.probe(item.url) }.getOrNull()
                        val dir = Repo.downloadsDir
                        val nm = FileNamer.uniqueIn(
                            dir,
                            FileNamer.sanitize(
                                probe?.suggestedName ?: item.title.ifBlank { "media" },
                            ),
                        )
                        Repo.create(
                            item.url, nm, dir.absolutePath,
                            Engine.defaultThreads(), false, probe,
                        )
                        added++
                    }
                }
            }
            if (added > 0) {
                DownloadService.start(Repo.context)
                Engine.pump()
            }
            Toast.makeText(ctx, "已添加 $added 项", Toast.LENGTH_SHORT).show()
            showMedia = false
            selected.clear()
        }
    }

    /** Queues one URL through the normal probe/segment path. */
    fun startDownload(url: String) {
        if (url.isBlank()) return
        scope.launch {
            val probe = withContext(Dispatchers.IO) {
                runCatching { Prober.probe(url) }.getOrNull()
            }
            val dir = Repo.downloadsDir
            val fallback = url.substringAfterLast('/').substringBefore('?').ifBlank { "download" }
            val name = FileNamer.uniqueIn(
                dir,
                FileNamer.sanitize(probe?.suggestedName ?: fallback),
            )
            Repo.create(url, name, dir.absolutePath, Engine.defaultThreads(), false, probe)
            DownloadService.start(Repo.context)
            Engine.pump()
            Toast.makeText(ctx, "已加入下载", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveCurrentPage() = startDownload(address)

    // The rule list is read once, off the main thread; this loop also drains what
    // shouldInterceptRequest queued and mirrors counters into Compose state.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AdBlocker.ensureLoaded(ctx) }
        while (true) {
            delay(500)
            if (sniffQueue.isNotEmpty()) {
                val batch = ArrayList<SniffedMedia>()
                while (true) batch.add(sniffQueue.poll() ?: break)
                absorb(batch)
                // A page that never reaches onPageFinished (VPN/timeout) still
                // shows up here, which is what tells the two cases apart.
                val now = System.currentTimeMillis()
                if (batch.isNotEmpty() && now - lastDrainLogAt.get() > 2000) {
                    lastDrainLogAt.set(now)
                    Diag.log("SNIFF_NET", "batch=${batch.size} total=${media.size}")
                }
            }
            adblockOn = AdBlocker.isEnabled()
            blockedCount = AdBlocker.blockedCount()
            siteAllowed = AdBlocker.isSiteAllowed(pageHost.get())
        }
    }

    // AndroidView has no onRelease in this Compose version: without an explicit
    // teardown the WebView (Activity context + its renderer) outlives the screen.
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
        }
    }

    if (showCookies) {
        SiteDataScreen(onBack = { showCookies = false })
        return
    }

    // Back closes the find bar first, then walks this tab's history, then returns
    // to the start page. Only after that does the system leave the tab.
    BackHandler(enabled = findOpen) { findOpen = false }
    BackHandler(enabled = canGoBack) { goBackSmart() }
    BackHandler(enabled = !canGoBack && !showStart) { showStartPage() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tabs live at the far left; the count doubles as the entry point.
            IconButton(onClick = { showTabs = true }, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "标签页",
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "${tabs.size}",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 2.dp, bottom = 2.dp),
                    )
                }
            }
            // Back / forward stay visible and just grey out; hiding them made the
            // bar shift around and gave no hint that history existed.
            IconButton(
                onClick = { goBackSmart() },
                enabled = canGoBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "后退",
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { goForwardSmart() },
                enabled = canGoForward,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "前进",
                    modifier = Modifier.size(20.dp),
                )
            }
            // Compact address bar: the field owns the reload action, so the bar
            // stays one line on a phone instead of crowding six icons.
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (address.isEmpty()) {
                        Text(
                            "搜索或输入网址",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { navigate(address) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(
                    onClick = { webView?.reload() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "菜单",
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("主页") {
                        menuOpen = false
                        showStartPage()
                    }
                    MenuItem("页内查找") {
                        menuOpen = false
                        findOpen = true
                    }
                    MenuItem("查看网页源码") {
                        menuOpen = false
                        webView?.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                            sourceText = unquoteJs(raw)
                        }
                    }
                    MenuItem("打印页面") {
                        menuOpen = false
                        val pm = ctx.getSystemService(PrintManager::class.java)
                        val wv = webView
                        if (pm != null && wv != null) {
                            val job = wv.createPrintDocumentAdapter("FreeDM")
                            runCatching { pm.print("FreeDM", job, null) }
                        }
                    }
                    MenuItem("保存网页") {
                        menuOpen = false
                        saveCurrentPage()
                    }
                    MenuItem("浏览历史") {
                        menuOpen = false
                        onOpenHistory()
                    }
                    HorizontalDivider()
                    MenuItem(if (adblockOn) "☑ 广告过滤（已拦 $blockedCount）" else "☐ 广告过滤") {
                        menuOpen = false
                        adblockOn = !adblockOn
                        AdBlocker.setEnabled(adblockOn)
                        webView?.reload()
                    }
                    MenuItem(if (siteAllowed) "本站恢复过滤" else "本站关闭过滤") {
                        menuOpen = false
                        val host = AdBlocker.hostOf(address)
                        if (host == null) {
                            Toast.makeText(ctx, "先打开一个网页", Toast.LENGTH_SHORT).show()
                        } else {
                            val allow = !siteAllowed
                            AdBlocker.setSiteAllowed(host, allow)
                            siteAllowed = allow
                            webView?.reload()
                        }
                    }
                    MenuItem(if (snifferEnabled) "☑ 嗅探音频/视频" else "☐ 嗅探音频/视频") {
                        menuOpen = false
                        snifferEnabled = !snifferEnabled
                        prefs.edit().putBoolean("sniff", snifferEnabled).apply()
                        if (!snifferEnabled) media.clear()
                    }
                    MenuItem("查看检测到的媒体 (${media.size})") {
                        menuOpen = false
                        showMedia = true
                    }
                    MenuItem("用系统浏览器打开", enabled = address.isNotBlank()) {
                        menuOpen = false
                        // Some sites (login + risk control) need a real browser. Hand the
                        // URL over with this app excluded, so the system picks another one
                        // instead of bouncing straight back here.
                        val view = Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse(address),
                        ).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra(
                                Intent.EXTRA_EXCLUDE_COMPONENTS,
                                arrayOf(
                                    android.content.ComponentName(
                                        ctx,
                                        app.freedm.MainActivity::class.java,
                                    ),
                                ),
                            )
                        }
                        runCatching { ctx.startActivity(view) }
                            .onFailure {
                                Toast.makeText(ctx, "没有其他浏览器", Toast.LENGTH_SHORT).show()
                            }
                    }
                    // For sites whose login cannot finish in a WebView (MFA screens that
                    // never render), sign in elsewhere and paste the cookies here.
                    MenuItem("导入 Cookie", enabled = address.isNotBlank()) {
                        menuOpen = false
                        cookieText = ""
                        cookieImport = true
                    }
                    HorizontalDivider()
                    MenuItem("复制链接", enabled = address.isNotBlank()) {
                        menuOpen = false
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("url", address))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    MenuItem("分享链接", enabled = address.isNotBlank()) {
                        menuOpen = false
                        runCatching {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, address)
                                    },
                                    null,
                                ),
                            )
                        }
                    }
                    MenuItem("用系统浏览器打开", enabled = address.isNotBlank()) {
                        menuOpen = false
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address)),
                            )
                        }
                    }
                    MenuItem("打开剪贴板中的链接") {
                        menuOpen = false
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        val raw = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.text?.toString()
                        val url = firstUrl(raw)
                        if (url == null) {
                            Toast.makeText(ctx, "剪贴板里没有链接", Toast.LENGTH_SHORT).show()
                        } else {
                            showStart = false
                            webView?.loadUrl(url)
                        }
                    }
                    HorizontalDivider()
                    MenuItem(if (desktopUa) "切换到移动版" else "切换到桌面版") {
                        menuOpen = false
                        setDesktop(!desktopUa)
                    }
                    MenuItem("网站数据") {
                        menuOpen = false
                        showCookies = true
                    }
                }
            }
        }

        if (findOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = {
                        findQuery = it
                        webView?.findAllAsync(it)
                    },
                    placeholder = { Text("页内查找") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { webView?.findNext(true) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "上一个")
                }
                IconButton(onClick = { webView?.findNext(false) }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "下一个")
                }
                IconButton(onClick = {
                    webView?.clearMatches()
                    findOpen = false
                }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭查找")
                }
            }
        }

        if (loading > 0f && loading < 1f) {
            // Small spinner under the address bar: a thin bar was too easy to miss.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // targetSdk 35 puts the window in edge-to-edge, so nothing shrinks when
                // the keyboard opens unless the app consumes the IME inset itself. Sites
                // that rely on `interactive-widget=resizes-content` (DeepSeek does) then
                // lay their fields out for a viewport that is not the real one — the
                // symptom is a caret that keeps snapping back to position 0.
                .imePadding(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = prefs.getBoolean("js_enabled", true)
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = if (desktopUa) UA_DESKTOP else defaultUa
                        // No overview mode. Forcing the page to fit the screen width means
                        // the WebView keeps a zoom factor, and a tap on a field is mapped
                        // through it — when that factor is stale (after the keyboard
                        // resizes things) the caret lands at position 0. Pages with a
                        // viewport meta lay themselves out correctly without it.
                        settings.loadWithOverviewMode = false
                        settings.useWideViewPort = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        // Verification / OAuth steps open in a popup; without this the
                        // window request is silently dropped and the page waits forever.
                        settings.setSupportMultipleWindows(true)
                        // Login flows (risk-control widgets, OAuth hops) live on other
                        // hosts and need their cookies. WebView refuses third-party
                        // cookies by default, and the symptom is exactly "confirm the
                        // password, then the dialog just sits there". The policy is
                        // applied per WebView, so it is re-applied on every page start
                        // too — the switch can change while this screen stays composed.
                        applyCookiePolicy(this)
                        // Login pages routinely pull http subresources; blocking them
                        // silently leaves a half-rendered dialog.
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // No forced darkening. It re-colours pages that ship their own
                        // theme and turns translucent overlays (a login spinner mask)
                        // into a grey blur. The transparent background already stops the
                        // white flash, which was the reason it was turned on.
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            settings.isAlgorithmicDarkeningAllowed = false
                        }
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        // Reached only when the WebView cannot render the target itself
                        // — `Content-Disposition: attachment`, or a type it has no
                        // viewer for. That is exactly "this link is a file", so the
                        // extension heuristic never has to guess.
                        setDownloadListener { dlUrl, _, _, mime, _ ->
                            if (dlUrl != null && dlUrl.startsWith("http")) {
                                Diag.log("DL_LISTENER", "host=${Diag.hostOf(dlUrl)} mime=$mime")
                                startDownload(dlUrl)
                            }
                        }

                        // Restore the active tab first; the older "remember page"
                        // switch stays as a fallback when no tab URL was saved.
                        // Only http(s) is restorable: a saved deeplink would come
                        // back as an error page and hide the start page.
                        val restored = httpOnly(tabs.getOrNull(activeTab)?.url)
                        if (restored != null) {
                            showStart = false
                            address = restored
                            loadUrl(restored)
                        } else if (prefs.getBoolean("remember_page", false)) {
                            httpOnly(prefs.getString("last_page", null))?.let {
                                showStart = false
                                address = it
                                loadUrl(it)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loading = newProgress / 100f
                            }

                            /**
                             * `window.open` is how login/verification flows usually show
                             * their second step. Without multi-window support the call
                             * returns null: the page dims itself and waits forever for a
                             * window that never appears — exactly "a blurry screen with no
                             * verification UI on it".
                             *
                             * The popup target is loaded in this same WebView instead of a
                             * second one; the flow completes on a real page, and pages that
                             * insist on talking through `window.opener` would need a second
                             * WebView (not wired up yet).
                             */
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?,
                            ): Boolean {
                                val host = view ?: return false
                                val probe = WebView(host.context)
                                probe.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val u = request?.url?.toString() ?: return false
                                        Diag.log("WINDOW_OPEN", u.take(90))
                                        showStart = false
                                        freshNav = true
                                        host.loadUrl(u)
                                        probe.destroy()
                                        return true
                                    }
                                }
                                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = probe
                                resultMsg?.sendToTarget()
                                return true
                            }

                            // The real source of a tab's title. Reading `view.title`
                            // inside onPageFinished picks up the *previous* page's
                            // title, which is how a tab ended up labelled
                            // "about:blank" while showing music.apple.com.
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                val clean = title?.trim()
                                if (clean.isNullOrEmpty() || clean == "about:blank") return
                                val cur = tabs.getOrNull(activeTab) ?: return
                                if (cur.url.isBlank() || cur.title == clean) return
                                tabs[activeTab] = cur.copy(title = clean)
                                persistTabs()
                            }

                            // Page-side failures are invisible from the app otherwise,
                            // and a login that "just stops" is usually one of these.
                            // Only errors: warnings from big sites fill the log.
                            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                                if (msg == null) return true
                                val level = msg.messageLevel()
                                if (level == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                                    level == android.webkit.ConsoleMessage.MessageLevel.TIP
                                ) {
                                    val body = msg.message()
                                    // Our own probes carry the useful payload; keep them whole.
                                    val limit = if (body.startsWith("FREEDM_")) 900 else 160
                                    Diag.log(
                                        "PAGE_JS",
                                        "host=${AdBlocker.hostOf(address).orEmpty()} " +
                                            "level=$level line=${msg.lineNumber()} " +
                                            body.take(limit),
                                    )
                                    // The page asked for a code and then abandoned it.
                                    when {
                                        body.startsWith("FREEDM_MFA_REQUIRED ") -> {
                                            mfaSid = body.removePrefix("FREEDM_MFA_REQUIRED ").trim()
                                            mfaCode = ""
                                        }

                                        body.startsWith("FREEDM_MFA_SEND ") ->
                                            Toast.makeText(
                                                ctx,
                                                // `biz_code` can be a rate-limit warning while
                                                // the mail still goes out, so do not call it a
                                                // failure — tell the user to check the mailbox.
                                                "已请求发送，请查收邮箱",
                                                Toast.LENGTH_SHORT,
                                            ).show()

                                        body.startsWith("FREEDM_MFA_CHECK ") ->
                                            Toast.makeText(
                                                ctx,
                                                if (body.contains("\"code\":0")) "验证通过"
                                                else "验证码不对或已过期",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                }
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {

                            override fun shouldOverrideUrlLoading(
                                v: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                // Domestic sites like to bounce the WebView into their
                                // own app (`baiduboxapp://`, `weixin://`, `intent://`).
                                // Letting WebView load it yields ERR_UNKNOWN_URL_SCHEME
                                // and replaces a perfectly good page with an error
                                // screen — swallow it instead of handing it to the
                                // system, so no other app gets launched either.
                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    Diag.log("NAV_BLOCKED", Diag.hostOf(url) ?: url.take(24))
                                    return true
                                }
                                // Direct file links would otherwise navigate to a
                                // page that renders as garbage; turn them into tasks.
                                if (!looksLikeDownload(url)) {
                                    // A link the user followed is a new page in this
                                    // tab's history, not a redirect of the current one.
                                    freshNav = true
                                    return false
                                }
                                startDownload(url)
                                return true
                            }

                            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                // Backing out to about:blank must land on the start
                                // page, not on an empty white WebView.
                                val blank = url == "about:blank"
                                address = if (blank) "" else url ?: address
                                if (blank) showStart = true
                                pageHost.set(AdBlocker.hostOf(url ?: ""))
                                pageStartedAt.set(System.currentTimeMillis())
                                sniffNetCount.set(0)
                                resCount.set(0)
                                blockedAtStart.set(AdBlocker.blockedCount())
                                v?.let { applyCookiePolicy(it) }
                                media.clear()
                                sniffQueue.clear()
                                if (!blank) Diag.log("PAGE_START", pageHost.get().orEmpty())
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                loading = 1f
                                // Deeplinks are not pages: storing one would make the
                                // next start restore an error screen.
                                val page = httpOnly(url)
                                if (page != null) {
                                    val cur = tabs.getOrNull(activeTab)
                                    if (cur != null) {
                                        val hist = cur.history.toMutableList()
                                        var cursor = cur.cursor
                                        // Moving through history lands on a URL the
                                        // cursor already points at, so only genuinely
                                        // new pages get appended — and anything ahead
                                        // of the cursor is dropped, like a browser.
                                        if (hist.getOrNull(cursor) != page) {
                                            if (freshNav) {
                                                while (hist.size > cursor + 1) {
                                                    hist.removeAt(hist.lastIndex)
                                                }
                                                hist.add(page)
                                                cursor = hist.lastIndex
                                            } else if (cursor >= 0) {
                                                // A redirect or an auto-jump of the page
                                                // we just started loading: same history
                                                // entry, new URL.
                                                hist[cursor] = page
                                            } else {
                                                hist.add(page)
                                                cursor = hist.lastIndex
                                            }
                                            freshNav = false
                                        }
                                        tabs[activeTab] = cur.copy(
                                            url = page,
                                            title = v?.title?.trim()
                                                ?.takeIf { it.isNotEmpty() && it != "about:blank" }
                                                ?: page,
                                            history = hist,
                                            cursor = cursor,
                                        )
                                        persistTabs()
                                        HistoryStore.add(
                                            prefs,
                                            page,
                                            v?.title?.trim()
                                                ?.takeIf { it.isNotEmpty() && it != "about:blank" }
                                                ?: page,
                                        )
                                    }
                                    if (prefs.getBoolean("remember_page", false)) {
                                        prefs.edit().putString("last_page", page).apply()
                                    }
                                    runCatching { android.net.Uri.parse(page).host }.getOrNull()
                                        ?.let { host ->
                                            val seen = prefs.getStringSet("visited_hosts", null)
                                                ?.toMutableSet() ?: mutableSetOf()
                                            if (seen.add(host)) {
                                                prefs.edit()
                                                    .putStringSet("visited_hosts", seen)
                                                    .apply()
                                            }
                                        }
                                }
                                val startedAt = pageStartedAt.get()
                                val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else -1
                                Diag.log(
                                    "PAGE_DONE",
                                    "host=${AdBlocker.hostOf(url ?: "").orEmpty()} ms=$elapsed " +
                                        "res=${resCount.get()} sniffed=${media.size} net=${sniffNetCount.get()} " +
                                        "blocked=${AdBlocker.blockedCount() - blockedAtStart.get()} " +
                                        "js=${v?.settings?.javaScriptEnabled}",
                                )
                                if (v == null) return
                                if (desktopUa) v.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                                if (!snifferEnabled) {
                                    Diag.log("SNIFF_OFF", "")
                                    return
                                }
                                v.evaluateJavascript(Sniffer.PROBE_JS) { raw ->
                                    val parsed = Sniffer.parseProbeResult(raw)
                                    Diag.log("SNIFF_DOM", "raw=${raw?.length ?: -1} parsed=${parsed.size}")
                                    absorb(parsed)
                                }
                                // What the page sees: the UA it was served (proves the
                                // WebView markers are gone), its viewport meta, and the
                                // kind of field on it. Enough to tell a site-side legacy
                                // branch apart from a WebView layout problem.
                                v.evaluateJavascript(PAGE_ENV_JS) { raw ->
                                    Diag.log("PAGE_ENV", raw?.take(240).orEmpty())
                                }
                                // Only while diagnostics are on — it hooks every field
                                // on the page, which is not something to leave running.
                                // Always on: these three are fixes, not diagnostics.
                                // The first repairs a WebView caret bug that makes typed
                                // characters arrive in reverse; the other two finish logins
                                // the page itself gives up on.
                                v.evaluateJavascript(INPUT_FIX_JS, null)
                                v.evaluateJavascript(PENDING_GUARD_JS, null)
                                v.evaluateJavascript(MFA_BRIDGE_JS, null)
                                if (prefs.getBoolean("diag_log", true)) {
                                    v.evaluateJavascript(NET_PROBE_JS, null)
                                    v.evaluateJavascript(MFA_CALL_JS, null)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                Diag.log(
                                    "PAGE_ERR",
                                    "host=${AdBlocker.hostOf(request?.url?.toString() ?: "").orEmpty()} " +
                                        "code=${error?.errorCode} main=${request?.isForMainFrame} " +
                                        "desc=${error?.description}",
                                )
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                Diag.log(
                                    "PAGE_HTTP_ERR",
                                    "host=${AdBlocker.hostOf(request?.url?.toString() ?: "").orEmpty()} " +
                                        "status=${errorResponse?.statusCode} main=${request?.isForMainFrame}",
                                )
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                if (request == null) return null
                                val u = request.url.toString()
                                resCount.incrementAndGet()

                                // The document itself is never blocked — a rule that
                                // happens to match the page would leave a blank tab.
                                if (!request.isForMainFrame &&
                                    AdBlocker.shouldBlock(u, pageHost.get())
                                ) {
                                    return AdBlocker.emptyResponse()
                                }

                                // Read prefs rather than Compose state: this callback
                                // runs on WebView's threads, not the main thread.
                                if (prefs.getBoolean("sniff", true)) {
                                    val kind = SniffedMedia.kindOf(u, null)
                                    if (kind != SniffedMedia.Kind.FILE) {
                                        sniffNetCount.incrementAndGet()
                                        sniffQueue.add(
                                            SniffedMedia(
                                                url = u,
                                                kind = kind,
                                                title = u.substringAfterLast('/').substringBefore('?'),
                                                fromNetwork = true,
                                            ),
                                        )
                                    }
                                }
                                return null
                            }
                        }
                    }
                },
                update = { webView = it },
            )

            if (showStart) {
                StartPage(
                    onSubmit = { navigate(it) },
                    onOpen = { url ->
                        showStart = false
                        freshNav = true
                        webView?.loadUrl(url)
                    },
                )
            }
        }
    }

    sourceText?.let { html ->
        AlertDialog(
            onDismissRequest = { sourceText = null },
            title = { Text("网页源码") },
            text = {
                Box(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        html,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("html", html))
                    sourceText = null
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { sourceText = null }) { Text("关闭") }
            },
        )
    }

    if (cookieImport) {
        AlertDialog(
            onDismissRequest = { cookieImport = false },
            title = { Text("导入 Cookie") },
            text = {
                Column {
                    Text(
                        "从浏览器的开发者工具里复制本站的 Cookie，粘到这里。" +
                            "形如 a=1; b=2，换行也可以。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { cookieText = it },
                        label = { Text("Cookie") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = cookieText.isNotBlank(),
                    onClick = {
                        val host = AdBlocker.hostOf(address)
                        val cm = android.webkit.CookieManager.getInstance()
                        if (host != null) {
                            cookieText
                                .split(';', '\n')
                                .map { it.trim() }
                                .filter { it.contains('=') && !it.startsWith('#') }
                                .forEach { pair ->
                                    runCatching {
                                        cm.setCookie("https://$host", "$pair; Path=/")
                                    }
                                }
                            runCatching { cm.flush() }
                            Diag.log(
                                "COOKIE_IMPORT",
                                "host=$host pairs=${cookieText.count { it == '=' }}",
                            )
                        }
                        cookieImport = false
                        webView?.reload()
                    },
                ) { Text("导入并刷新") }
            },
            dismissButton = {
                TextButton(onClick = { cookieImport = false }) { Text("取消") }
            },
        )
    }

    // Two-step verification for accounts the site refuses to show a code box for.
    val sid = mfaSid
    if (sid != null) {
        AlertDialog(
            onDismissRequest = { mfaSid = null },
            title = { Text("两步验证") },
            text = {
                Column {
                    Text(
                        "这个账号开了两步验证。点「发送验证码」后查收邮箱；" +
                            "没收到也可以填之前那条（有效期较长）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mfaCode,
                        onValueChange = { mfaCode = it },
                        label = { Text("验证码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        webView?.evaluateJavascript(
                            "window.__freedmMfaSend && window.__freedmMfaSend()",
                            null,
                        )
                    }) { Text("发送验证码") }
                    // Digits and letters only: the value is inlined into a JS call.
                    val clean = mfaCode.filter { it.isLetterOrDigit() }
                    TextButton(
                        enabled = clean.isNotEmpty(),
                        onClick = {
                            webView?.evaluateJavascript(
                                "window.__freedmMfaCheck && window.__freedmMfaCheck('$clean')",
                                null,
                            )
                        },
                    ) { Text("验证") }
                }
            },
            dismissButton = {
                TextButton(onClick = { mfaSid = null }) { Text("取消") }
            },
        )
    }

    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("标签页 ${tabs.size}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        tabs.add(TabEntry("", ""))
                        activeTab = tabs.lastIndex
                        persistTabs()
                        showTabs = false
                        showStart = true
                        address = ""
                        webView?.loadUrl("about:blank")
                    }) { Text("新建") }
                }
                HorizontalDivider()
                tabs.forEachIndexed { i, tab ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                showTabs = false
                                switchToTab(i)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.title.ifBlank { "新标签页" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (i == activeTab) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (tab.url.isNotBlank()) {
                                Text(
                                    tab.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (tabs.size > 1) {
                            IconButton(onClick = {
                                val wasActive = i == activeTab
                                tabs.removeAt(i)
                                if (activeTab > i || activeTab >= tabs.size) {
                                    activeTab = (activeTab - 1).coerceAtLeast(0)
                                }
                                persistTabs()
                                // Closing the tab on screen has to load the survivor,
                                // otherwise the closed tab's page stays visible.
                                if (wasActive) switchToTab(activeTab)
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭标签")
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showMedia) {
        ModalBottomSheet(onDismissRequest = { showMedia = false }) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                                    "检测到 ${media.size} 个文件",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (selected.size == media.size) selected.clear()
                        else {
                            selected.clear()
                            media.forEach { selected.add(it.key) }
                        }
                            }) { Text(if (selected.size == media.size && media.isNotEmpty()) "取消全选" else "全选") }
                }
                if (media.isNotEmpty()) {
                    val known = media.filter { it.key in selected && it.sizeHint > 0 }
                        .sumOf { it.sizeHint }
                    val unknown = media.count { it.key in selected && it.sizeHint <= 0 }
                    Text(
                        buildString {
                            append("选择要下载的文件")
                            if (selected.isNotEmpty()) {
                                    append(" · 已选 ${selected.size}")
                                if (known > 0) append(" · ${formatBytes(known)}")
                                    if (unknown > 0) append(if (known > 0) " + $unknown 项未知大小" else " · $unknown 项未知大小")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (media.isEmpty()) {
                    Text(
                        "当前页面还没检测到媒体。试着播放一下视频，或滚动页面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(media.toList(), key = { it.key }) { item ->
                            val isSel = item.key in selected
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSel) selected.remove(item.key) else selected.add(item.key)
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = isSel,
                                        onCheckedChange = {
                                            if (it) selected.add(item.key) else selected.remove(item.key)
                                        },
                                    )
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .padding(start = 4.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AssistChip(
                                                onClick = {},
                                                label = {
                                                    Text(
                                                        when (item.kind) {
                                                            SniffedMedia.Kind.VIDEO -> "VIDEO"
                                                            SniffedMedia.Kind.AUDIO -> "AUDIO"
                                                            SniffedMedia.Kind.HLS -> "HLS"
                                                            SniffedMedia.Kind.DASH -> "DASH"
                                                            SniffedMedia.Kind.FILE -> "FILE"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                },
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                item.title.take(60),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            item.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Row {
                                            Text(
                                                if (item.sizeHint > 0) {
                                                    formatBytes(item.sizeHint)
                                                } else {
                                                    "大小未知"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (item.fromNetwork) {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "来自网络请求",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        if (item.kind == SniffedMedia.Kind.HLS ||
                                            item.kind == SniffedMedia.Kind.DASH
                                        ) {
                                            Text(
                                            "流媒体清单，按单个文件下载",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (media.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            showMedia = false
                            selected.clear()
                        }) { Text("关闭") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { addSelected() },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Text(
                                        if (selected.isEmpty()) "下载" else "下载 ${selected.size} 项",
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** evaluateJavascript hands back a JSON-encoded string, not raw HTML. */
private fun unquoteJs(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    val body = raw.removeSurrounding("\"")
    val sb = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            when (val n = body[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    val hex = body.substring(i + 2, minOf(i + 6, body.length))
                    runCatching { sb.append(hex.toInt(16).toChar()) }.onFailure { sb.append(hex) }
                    i += 4
                }

                else -> sb.append(n)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
