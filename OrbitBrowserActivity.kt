package __PACKAGE__

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.*
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class OrbitBrowserActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("orbit_profiles", Context.MODE_PRIVATE) }
    private val profileNames = mutableListOf<String>()
    private var activeProfile = "Account 1"
    private var webView: WebView? = null
    private lateinit var root: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var webHost: FrameLayout
    private var profileSupported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 15, 24)
        window.navigationBarColor = Color.rgb(12, 15, 24)
        profileSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        loadProfiles()
        buildUi()
        if (profileSupported) showProfile(activeProfile) else showUnsupported()
    }

    private fun loadProfiles() {
        val saved = prefs.getString("profiles", "Account 1") ?: "Account 1"
        profileNames.clear()
        profileNames.addAll(saved.split("\n").filter { it.isNotBlank() }.distinct())
        if (profileNames.isEmpty()) profileNames.add("Account 1")
        activeProfile = prefs.getString("active", profileNames.first()) ?: profileNames.first()
        if (activeProfile !in profileNames) activeProfile = profileNames.first()
    }

    private fun saveProfiles() {
        prefs.edit().putString("profiles", profileNames.joinToString("\n"))
            .putString("active", activeProfile).apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 14f): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(235, 238, 248)); gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.rgb(39, 45, 64))
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 15, 24)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.rgb(19, 23, 36))
        }
        header.addView(label("◈  ORBIT BROWSER", 16f), LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(button("＋ Account") { addProfile() })
        header.addView(button("✕") { finish() }, LinearLayout.LayoutParams(dp(44), dp(42)))
        root.addView(header)

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Color.rgb(16, 20, 31)) }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        scroll.addView(tabs)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(54)))

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        address = EditText(this).apply {
            setSingleLine(true); textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            hint = "https://claude.ai/"; setText("https://claude.ai/"); setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(25, 29, 43)); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { navigate(address.text.toString()); true } else false
            }
        }
        addressRow.addView(address, LinearLayout.LayoutParams(0, dp(44), 1f))
        addressRow.addView(button("Go") { navigate(address.text.toString()) }, LinearLayout.LayoutParams(dp(56), dp(44)))
        addressRow.addView(button("↻") { webView?.reload() }, LinearLayout.LayoutParams(dp(46), dp(44)))
        root.addView(addressRow)

        status = label("Profile: $activeProfile", 12f).apply { setPadding(dp(12), dp(5), dp(12), dp(5)); setTextColor(Color.rgb(155, 165, 188)) }
        root.addView(status)
        webHost = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(webHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderTabs()
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        profileNames.forEach { name ->
            val selected = name == activeProfile
            val b = button((if (selected) "●  " else "○  ") + name) { switchProfile(name) }
            b.setBackgroundColor(if (selected) Color.rgb(92, 69, 158) else Color.rgb(31, 36, 52))
            val lp = LinearLayout.LayoutParams(-2, dp(38)); lp.setMargins(dp(3), 0, dp(3), 0); tabs.addView(b, lp)
        }
    }

    private fun addProfile() {
        var i = 1
        while (profileNames.contains("Account $i")) i++
        val name = "Account $i"
        profileNames.add(name); activeProfile = name; saveProfiles(); renderTabs()
        if (profileSupported) showProfile(name)
    }

    private fun switchProfile(name: String) {
        if (name == activeProfile) return
        activeProfile = name; saveProfiles(); renderTabs()
        if (profileSupported) showProfile(name)
    }

    private fun showProfile(name: String) {
        try {
            webView?.let { old -> webHost.removeView(old); old.stopLoading(); old.loadUrl("about:blank"); old.destroy() }
            webView = null
            val view = WebView(this)
            // Must be assigned before settings, clients, or attaching to the view hierarchy.
            WebViewCompat.setProfile(view, profileKey(name))
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.loadsImagesAutomatically = true
            view.settings.javaScriptCanOpenWindowsAutomatically = false
            view.settings.setSupportMultipleWindows(false)
            view.webChromeClient = WebChromeClient()
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "http" || uri.scheme == "https") return false
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) address.setText(url)
                    status.text = "Profile: $activeProfile  •  Loading…"
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "Profile: $activeProfile  •  Session isolated"
                }
            }
            webView = view
            webHost.removeAllViews()
            webHost.addView(view, FrameLayout.LayoutParams(-1, -1))
            address.setText("https://claude.ai/")
            view.loadUrl("https://claude.ai/")
            renderTabs()
        } catch (e: Exception) {
            status.text = "Unable to create isolated profile: ${e.message ?: "unsupported WebView"}"
            showMessage("Could not open profile. Update Android System WebView and try again.\n\n${e.message ?: "Unknown error"}")
        }
    }

    private fun profileKey(name: String): String = "orbit_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun navigate(raw: String) {
        var url = raw.trim()
        if (url.isEmpty()) return
        if (!url.contains("://")) url = "https://$url"
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") {
            showMessage("Only HTTP and HTTPS pages are supported."); return
        }
        address.setText(url)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(address.windowToken, 0)
        webView?.loadUrl(url)
    }

    private fun showUnsupported() {
        webHost.removeAllViews()
        val msg = TextView(this).apply {
            text = "Isolated browser profiles are not supported by the installed Android System WebView.\n\nUpdate Android System WebView from Google Play, then reopen Orbit Browser. No shared-session fallback is used, so accounts won't accidentally share cookies."
            textSize = 17f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(Color.WHITE)
        }
        webHost.addView(msg, FrameLayout.LayoutParams(-1, -1))
        status.text = "Multi-profile feature unavailable"
    }

    private fun showMessage(message: String) {
        if (!isFinishing) android.app.AlertDialog.Builder(this).setTitle("Orbit Browser").setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun onDestroy() {
        webView?.let { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }
        webView = null
        super.onDestroy()
    }
}
