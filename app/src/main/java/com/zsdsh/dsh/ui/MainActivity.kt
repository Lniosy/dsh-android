package com.zsdsh.dsh.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zsdsh.dsh.R
import com.zsdsh.dsh.ZsdshApp
import com.zsdsh.dsh.databinding.ActivityMainBinding
import com.zsdsh.dsh.engine.DshEngine
import com.zsdsh.dsh.engine.EngineReady
import com.zsdsh.dsh.engine.EngineService
import com.zsdsh.dsh.privilege.PrivilegeKind
import com.zsdsh.dsh.privilege.ShizukuChannel
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {
    private lateinit var binding: ActivityMainBinding
    private var uiLoaded = false
    private var waitingUi = false
    private var chromeExpanded = false
    private var preparing = false
    private var prepareHint: String? = null
    private var statusBarTop = 0
    private var navInsetCssPx = 0f
    private val mainHandler = Handler(Looper.getMainLooper())
    private val poller = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        requestStorage()
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        applyNativeTheme(systemNight(), null)
        setupWebView()
        binding.btnShizuku.setOnClickListener {
            ZsdshApp.instance.privilege.shizuku.requestPermission()
            refresh()
        }
        binding.btnEngine.setOnClickListener {
            if (ZsdshApp.instance.engine.isRunning()) return@setOnClickListener
            EngineService.start(this)
            waitForEngineUi()
            refresh()
        }
        binding.btnChrome.setOnClickListener {
            chromeExpanded = !chromeExpanded
            refresh()
        }
        binding.statusText.setOnClickListener {
            if (uiLoaded && ZsdshApp.instance.engine.isRunning()) {
                chromeExpanded = !chromeExpanded
                refresh()
            }
        }
        binding.peekChip.setOnClickListener {
            chromeExpanded = true
            refresh()
        }
        binding.btnSaveKey.setOnClickListener { saveApiKeyAndStart() }
        prepareRuntimeThenStart()
        refresh()
    }

    private fun prepareRuntimeThenStart() {
        if (ZsdshApp.instance.engine.paths.ready()) {
            startEngineIfReady()
            return
        }
        preparing = true
        prepareHint = "正在准备内置运行时…"
        refresh()
        poller.execute {
            val ok = try {
                ZsdshApp.instance.engine.paths.prepare { msg ->
                    mainHandler.post {
                        prepareHint = msg
                        binding.hintText.visibility = android.view.View.VISIBLE
                        binding.hintText.text = msg
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    prepareHint = e.message ?: "展开运行时失败"
                }
                false
            }
            mainHandler.post {
                preparing = false
                prepareHint = if (ok) null else (prepareHint ?: "展开运行时失败")
                startEngineIfReady()
                refresh()
            }
        }
    }

    private fun startEngineIfReady() {
        if (!ZsdshApp.instance.engine.paths.ready()) return
        if (ZsdshApp.instance.engine.isRunning()) {
            waitForEngineUi()
            return
        }
        EngineService.start(this)
        waitForEngineUi()
    }

    private fun saveApiKeyAndStart() {
        val key = binding.keyInput.text?.toString().orEmpty()
        if (!ZsdshApp.instance.engine.paths.saveApiKey(key)) {
            binding.hintText.visibility = android.view.View.VISIBLE
            binding.hintText.text = "Key 太短，请粘贴完整的 DeepSeek API Key。"
            return
        }
        binding.keyInput.text = null
        startEngineIfReady()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addRequestPermissionResultListener(this)
        refresh()
    }

    override fun onStop() {
        Shizuku.removeRequestPermissionResultListener(this)
        super.onStop()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == ShizukuChannel.REQUEST_CODE) {
            refresh()
        }
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun setupSystemBars() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            statusBarTop = status.top
            navInsetCssPx = if (ime.bottom > 0) 0f else nav.bottom / resources.displayMetrics.density
            applySystemBarPadding(ime.bottom)
            injectNavInset()
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applySystemBarPadding(bottom: Int) {
        val d = resources.displayMetrics.density
        binding.chromeBar.setPadding(
            (10 * d).toInt(),
            statusBarTop + (8 * d).toInt(),
            (8 * d).toInt(),
            (8 * d).toInt(),
        )
        binding.peekChip.setPadding(
            (12 * d).toInt(),
            statusBarTop + (3 * d).toInt(),
            (12 * d).toInt(),
            (3 * d).toInt(),
        )
        // 键盘起来时抬高页面；手势条不在这里留空，交给网页底色铺过去
        binding.root.setPadding(0, 0, 0, bottom)
    }

    private fun injectNavInset() {
        if (!::binding.isInitialized) return
        val px = String.format(java.util.Locale.US, "%.1f", navInsetCssPx)
        binding.webView.evaluateJavascript(
            """
            (function(){
              document.documentElement.style.setProperty('--zsdsh-nav', '${px}px');
            })();
            """.trimIndent(),
            null,
        )
    }

    private inner class ThemeBridge {
        @JavascriptInterface
        fun onTheme(scheme: String?, themeColor: String?) {
            val dark = scheme.equals("dark", ignoreCase = true)
            mainHandler.post { applyNativeTheme(dark, themeColor) }
        }
    }

    private fun systemNight(): Boolean {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyNativeTheme(dark: Boolean, themeColor: String?) {
        val bg = parseCssColor(themeColor)
            ?: ContextCompat.getColor(this, R.color.zsdsh_bg)
        val panel = if (dark) 0xFF1C1C1F.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFE8EEF8.toInt() else 0xFF1A1A1C.toInt()
        val muted = if (dark) 0xFF8A93A6.toInt() else 0xFF6B7280.toInt()
        binding.root.setBackgroundColor(bg)
        binding.chromeBar.setBackgroundColor(panel)
        binding.peekChip.setBackgroundColor(panel)
        binding.statusText.setTextColor(text)
        binding.peekChip.setTextColor(muted)
        binding.hintText.setTextColor(muted)
        binding.webView.setBackgroundColor(bg)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun parseCssColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.startsWith("#")) {
            val hex = s.removePrefix("#")
            return try {
                when (hex.length) {
                    6 -> (0xFF000000L or hex.toLong(16)).toInt()
                    8 -> hex.toLong(16).toInt()
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
        val m = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?""").find(s) ?: return null
        val alpha = m.groupValues.getOrNull(4)?.toFloatOrNull() ?: 1f
        if (alpha < 0.05f) return null
        return Color.rgb(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    @Suppress("DEPRECATION")
    private fun disableWebViewForceDark(settings: WebSettings) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val method = settings.javaClass.getMethod("setForceDark", Int::class.javaPrimitiveType)
                method.invoke(settings, 0)
            } catch (_: Exception) {
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            settings.isAlgorithmicDarkeningAllowed = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100
        disableWebViewForceDark(settings)
        binding.webView.setBackgroundColor(ContextCompat.getColor(this, R.color.zsdsh_bg))
        binding.webView.addJavascriptInterface(ThemeBridge(), "ZsdshChrome")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                injectMobileChrome(view ?: return)
            }
        }
        binding.webView.webChromeClient = WebChromeClient()
    }

    private fun injectMobileChrome(view: android.webkit.WebView) {
        val css = try {
            view.context.assets.open("mobile.css").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
        }
        val cssLiteral = JSONObject.quote(css)
        view.evaluateJavascript(
            """
            (function(){
              var css=$cssLiteral;
              var s=document.getElementById('zsdsh-mobile-css');
              if(!s){
                s=document.createElement('style');
                s.id='zsdsh-mobile-css';
                document.documentElement.appendChild(s);
              }
              s.textContent=css;
              var m=document.querySelector('meta[name=viewport]');
              if(!m){
                m=document.createElement('meta');
                m.name='viewport';
                document.head.appendChild(m);
              }
              m.content='width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover';
              document.documentElement.style.setProperty('--zsdsh-nav', '${String.format(java.util.Locale.US, "%.1f", navInsetCssPx)}px');
              if(window.__zsdshThemeWatch) return;
              window.__zsdshThemeWatch=true;
              function zsdshScheme(){
                if(document.body && document.body.hasAttribute('data-ds-dark-theme')) return 'dark';
                var cs=(document.documentElement.style.colorScheme||getComputedStyle(document.documentElement).colorScheme||'').toLowerCase();
                if(cs.indexOf('dark')>=0 && cs.indexOf('light')<0) return 'dark';
                if(cs.indexOf('light')>=0 && cs.indexOf('dark')<0) return 'light';
                return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
              }
              function zsdshReport(){
                if(!window.ZsdshChrome) return;
                var meta=document.querySelector('meta[name=theme-color]');
                var color=(meta && meta.content) || '';
                if(!color || color==='transparent' || /rgba\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0/.test(color)){
                  var el=document.body||document.documentElement;
                  color=getComputedStyle(el).getPropertyValue('--dsw-alias-bg') || getComputedStyle(el).backgroundColor || '';
                }
                window.ZsdshChrome.onTheme(zsdshScheme(), color||'');
              }
              var obs=new MutationObserver(zsdshReport);
              obs.observe(document.documentElement,{attributes:true,attributeFilter:['style','class']});
              if(document.body) obs.observe(document.body,{attributes:true,attributeFilter:['style','class','data-ds-dark-theme']});
              else document.addEventListener('DOMContentLoaded', function(){
                obs.observe(document.body,{attributes:true,attributeFilter:['style','class','data-ds-dark-theme']});
                zsdshReport();
              });
              try{ window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', zsdshReport); }catch(e){}
              zsdshReport();
              setTimeout(zsdshReport, 400);
              setTimeout(zsdshReport, 1200);
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun refresh() {
        val status = ZsdshApp.instance.privilege.status()
        val engine = ZsdshApp.instance.engine
        val activeColorHint = when (status.active) {
            PrivilegeKind.ROOT -> "ROOT 已接通"
            PrivilegeKind.SHIZUKU -> "Shizuku 已接通"
            PrivilegeKind.NONE -> "无特权通道"
        }
        val engineHint = when {
            preparing -> "正在展开运行时"
            engine.isRunning() -> "引擎运行中"
            engine.lastError != null -> "引擎失败：${engine.lastError}"
            engine.paths.ready() -> "引擎未启动"
            else -> "运行时未放入"
        }
        val canCollapse = uiLoaded && engine.isRunning() && engine.lastError == null
        if (!canCollapse) {
            chromeExpanded = true
        }
        val showFullChrome = !canCollapse || chromeExpanded
        binding.statusText.text = "$activeColorHint · $engineHint · ABI ${status.abi}"
        binding.peekChip.text = activeColorHint
        val needShizuku = status.active == PrivilegeKind.NONE
        binding.btnShizuku.visibility = if (needShizuku) android.view.View.VISIBLE else android.view.View.GONE
        when {
            engine.isRunning() -> {
                binding.btnEngine.visibility = android.view.View.GONE
            }
            engine.lastError != null -> {
                binding.btnEngine.visibility = android.view.View.VISIBLE
                binding.btnEngine.text = getString(R.string.restart_engine)
            }
            else -> {
                binding.btnEngine.visibility = android.view.View.VISIBLE
                binding.btnEngine.text = getString(R.string.start_engine)
            }
        }
        binding.btnChrome.visibility = android.view.View.GONE
        binding.btnChrome.text = getString(R.string.collapse_chrome)
        val needKey = !engine.paths.hasCredentials()
        binding.keyRow.visibility = if (needKey && !preparing) android.view.View.VISIBLE else android.view.View.GONE
        val showHint = preparing || !engine.isRunning() || engine.lastError != null || needKey
        binding.hintText.visibility = if (showHint) android.view.View.VISIBLE else android.view.View.GONE
        binding.chromeBar.visibility = if (showFullChrome) android.view.View.VISIBLE else android.view.View.GONE
        binding.peekChip.visibility = if (canCollapse && !chromeExpanded) android.view.View.VISIBLE else android.view.View.GONE
        if (!preparing) {
            binding.hintText.text = buildString {
                if (prepareHint != null && !engine.paths.ready()) {
                    append(prepareHint)
                    append('\n')
                }
                if (needKey) {
                    append("装好就能开界面。聊天前先在上面粘贴自己的 DeepSeek API Key。\n")
                }
                append("权限：Root 优先，Shizuku 兜底。\n")
                if (!engine.paths.ready() && !preparing) {
                    append("完整版 APK 会自带运行时；若仍缺，把 payload 放到 /sdcard/dsh/payload。")
                }
            }
        } else if (prepareHint != null) {
            binding.hintText.text = prepareHint
        }
        if (engine.isRunning() && !uiLoaded) {
            waitForEngineUi()
        }
    }

    private fun waitForEngineUi() {
        if (uiLoaded || waitingUi) return
        waitingUi = true
        binding.hintText.text = "正在等待 DSH Web UI（127.0.0.1:3080）就绪…"
        poller.execute {
            var attempts = 0
            while (attempts < 80 && !uiLoaded) {
                val ready = EngineReady.httpOk()
                val url = ZsdshApp.instance.engine.uiUrl()
                if (ready && url.contains("token=")) {
                    mainHandler.post { loadUi() }
                    return@execute
                }
                attempts += 1
                Thread.sleep(500)
            }
            mainHandler.post {
                waitingUi = false
                if (!uiLoaded) {
                    binding.hintText.text = "引擎进程在，但 3080 还没起来。看 files/payload/engine.log"
                    refresh()
                }
            }
        }
    }

    private fun loadUi() {
        if (uiLoaded) return
        uiLoaded = true
        waitingUi = false
        chromeExpanded = false
        binding.webView.loadUrl(ZsdshApp.instance.engine.uiUrl())
        refresh()
    }

    private fun requestStorage() {
        val need = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, need) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(need), 24)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
