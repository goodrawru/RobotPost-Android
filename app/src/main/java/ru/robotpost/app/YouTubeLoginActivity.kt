package ru.robotpost.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class YouTubeLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var saving = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 16, 25))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "Вход в аккаунт YouTube"
            setTextColor(Color.rgb(244, 245, 250))
            textSize = 20f
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        status = TextView(this).apply {
            text = "Войди на YouTube в окне ниже, затем нажми «Сохранить вход»."
            setTextColor(Color.rgb(164, 174, 193))
            textSize = 14f
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "https" && scheme != "http"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (saving && url?.contains("/robots.txt") == true) {
                    saving = false
                    saveSession(cookieManager)
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val closeButton = Button(this).apply {
            text = "Закрыть"
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        val saveButton = Button(this).apply {
            text = "Сохранить вход"
            setOnClickListener {
                if (saving) return@setOnClickListener
                saving = true
                status.text = "Проверяю сессию YouTube…"
                cookieManager.flush()
                webView.loadUrl("https://www.youtube.com/robots.txt")
            }
        }
        actions.addView(closeButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(saveButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        webView.loadUrl("https://www.youtube.com/")
    }

    private fun saveSession(cookieManager: CookieManager) {
        cookieManager.flush()
        val cookies = mapOf(
            "youtube.com" to cookieManager.getCookie("https://www.youtube.com").orEmpty(),
            "google.com" to cookieManager.getCookie("https://accounts.google.com").orEmpty()
        )
        if (YouTubeAuthStore.saveFromWebView(this, cookies)) {
            status.text = "Сессия сохранена локально. Можно вернуться в RobotPost."
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            status.text = "Сессия аккаунта не найдена. Проверь вход на YouTube и попробуй снова."
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
