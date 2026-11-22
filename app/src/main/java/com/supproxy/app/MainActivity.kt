package com.supproxy.app

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import hev.htproxy.TProxyService
import org.json.JSONObject
import java.net.Socket

private val BG = Color.parseColor("#F7F7F4")
private val SURFACE = Color.parseColor("#FFFFFF")
private val SURFACE2 = Color.parseColor("#F0F0EC")
private val OUTLINE = Color.parseColor("#DADBD5")
private val ACCENT = Color.parseColor("#5145AF")
private const val ON_ACCENT = Color.WHITE
private val GREEN = Color.parseColor("#176448")
private val AMBER = Color.parseColor("#825500")
private val RED = Color.parseColor("#B32642")
private val TEXT = Color.parseColor("#1C2030")
private val MUTED = Color.parseColor("#555C6C")
private val HOME_BG = Color.parseColor("#F5F8FB")
private val HOME_BORDER = Color.parseColor("#DCE7F2")
private val HOME_MUTED = Color.parseColor("#62748B")
private val HOME_BLUE = Color.parseColor("#1477EE")
private val HOME_GREEN = Color.parseColor("#12A16C")
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var dashboard: View
    private lateinit var lockScreen: View
    private var locked = false
    private var observingWifi = false
    private var lastWifi: Network? = null
    private var diagnosticsView: TextView? = null
    private val wifiObserver = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            ui.post { maybeProtectWifi() }
        }

        override fun onLost(network: Network) {
            ui.post { maybeProtectWifi() }
        }
    }
    private lateinit var navItems: List<Button>
    private var tab = 0
    private var profile = Profile()
    private val ui = Handler(Looper.getMainLooper())

    // Home screen views (null while another tab is showing)
    private var homeStatusDot: GradientDrawable? = null
    private var homeTraffic: View? = null
    private var exitMap: ExitMapView? = null
    private var exitLocation: TextView? = null
    private var exitAddress: TextView? = null
    private var connectButton: Button? = null
    private var statusTv: TextView? = null
    private var subTv: TextView? = null
    private var downloadTv: TextView? = null
    private var uploadTv: TextView? = null
    private var ipVals: Map<String, TextView> = emptyMap()
    private var detailsBadge: TextView? = null
    private var detailsStatus: TextView? = null
    private var detailsDot: GradientDrawable? = null
    private var detailsSummary: TextView? = null
    private var ipDetails: Map<String, String>? = null
    private var ipLookupInProgress = false
    private var ipLookupGeneration = 0
    private var fName: EditText? = null
    private var fHost: EditText? = null
    private var fPort: EditText? = null
    private var fUser: EditText? = null
    private var fPass: EditText? = null
    private var authSwitch: Switch? = null
    private var updateProtocolButtons: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshHome() }
    }

    private val tick = object : Runnable {
        override fun run() {
            updateStats()
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun EditText?.str() = this?.text?.toString()?.trim().orEmpty()

    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun vbox(pad: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun card() = vbox(16).apply {
        background = round(SURFACE, 20, OUTLINE)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
    }

    private fun page(title: String, sub: String) = vbox(20).also {
        it.addView(tv(title, 32f, TEXT, true))
        it.addView(tv(sub, 14f, MUTED), lp(MATCH, WRAP).apply { topMargin = dp(4) })
    }

    private fun scroll(inner: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(inner)
    }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        setHint(hint)
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 16f
        inputType = type
        setSingleLine(true)
        minHeight = dp(50)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = round(BG, 12, OUTLINE)
        setOnFocusChangeListener { _, focused ->
            background = round(BG, 12, if (focused) ACCENT else OUTLINE)
        }
    }

    private fun ripple(color: Int, radius: Int, border: Int = 0) = RippleDrawable(
        ColorStateList.valueOf(0x225145AF), round(color, radius, border), null
    )

    private fun button(t: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 15f
        isAllCaps = false
        letterSpacing = 0f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) ON_ACCENT else ACCENT)
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        minimumWidth = 0
        stateListAnimator = null
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = ripple(if (filled) ACCENT else SURFACE2, 12)
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply { setBackgroundColor(OUTLINE) }

    private fun row(title: String, desc: String, trailing: View?, onClick: (() -> Unit)?): View {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val t = vbox()
        t.addView(tv(title, 16f, TEXT, true))
        t.addView(tv(desc, 13f, MUTED))
        r.addView(t, lp(0, WRAP, 1f))
        if (trailing != null) r.addView(trailing, lp(WRAP, WRAP).apply { leftMargin = dp(12) })
        if (onClick != null) r.setOnClickListener { onClick() }
        return r
    }

    private fun link(t: String, color: Int = ACCENT) = tv(t, 14f, color, true)

    private fun info(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun fmt(n: Long): String = when {
        n >= 1L shl 30 -> String.format("%.2f GB", n / 1073741824.0)
        n >= 1L shl 20 -> String.format("%.1f MB", n / 1048576.0)
        n >= 1L shl 10 -> String.format("%.0f KB", n / 1024.0)
        else -> "$n B"
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = BG
        window.navigationBarColor = SURFACE
        profile = Store.active(this)
        Store.saved(this) // Migrate older saved profiles even if Profiles has not been opened.
        locked = Store.appLock(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))

        root.addView(divider(), lp(MATCH, dp(1)))
        val nav = LinearLayout(this).apply {
            setBackgroundColor(SURFACE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val navIcons = listOf(R.drawable.nav_home, R.drawable.nav_profiles, R.drawable.nav_apps, R.drawable.nav_settings)
        navItems = listOf("Home", "Profiles", "Apps", "Settings").mapIndexed { index, label ->
            button(label, false) { if (tab != index) show(index) }.apply {
                textSize = 13f
                setPadding(dp(3), dp(7), dp(3), dp(7))
                setCompoundDrawablesWithIntrinsicBounds(0, navIcons[index], 0, 0)
                compoundDrawablePadding = dp(3)
            }
        }
        navItems.forEach { nav.addView(it, lp(0, WRAP, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        root.addView(nav, MATCH, WRAP)
        dashboard = root
        lockScreen = vbox(24).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            addView(tv("Supproxy is locked", 22f, TEXT, true))
            addView(button("Unlock", true) {
                val prompt = getSystemService(KeyguardManager::class.java)
                    .createConfirmDeviceCredentialIntent("Unlock Supproxy", "Confirm your device screen lock")
                if (prompt == null) toast("Device authentication is unavailable")
                else startActivityForResult(prompt, 3)
            }, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        }
        setContentView(FrameLayout(this).apply {
            addView(dashboard, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(lockScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        })
        showLockState()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        show(0)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(ProxyVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        ui.post(tick)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(wifiObserver)
        observingWifi = true
        maybeProtectWifi()
        if (tab == 3) show(3) else refreshHome()
    }

    override fun onPause() {
        super.onPause()
        collectForm()
        Store.setActive(this, profile)
        unregisterReceiver(receiver)
        observingWifi = false
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiObserver)
        ui.removeCallbacks(tick)
    }

    override fun onStop() {
        super.onStop()
        if (Store.appLock(this)) {
            locked = true
            showLockState()
        }
    }

    private fun showLockState() {
        dashboard.visibility = if (locked) View.GONE else View.VISIBLE
        lockScreen.visibility = if (locked) View.VISIBLE else View.GONE
    }

    private fun maybeProtectWifi() {
        if (!observingWifi || locked || !Store.protectWifi(this)) return
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        if (network == null || manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            lastWifi = null
            return
        }
        if (network == lastWifi) return
        lastWifi = network
        try {
            if (ProxyVpnService.status == "Disconnected" && Store.active(this).host.isNotBlank() &&
                VpnService.prepare(this) == null) startVpn()
        } catch (_: RuntimeException) {
            toast("Android VPN permission is required")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3 && resultCode == RESULT_OK) {
            locked = false
            showLockState()
            maybeProtectWifi()
        } else if (requestCode == 1 && resultCode == RESULT_OK) startVpn()
        else if (requestCode == 1) toast("VPN permission denied")
    }

    private fun show(i: Int) {
        collectForm()
        tab = i
        homeStatusDot = null; homeTraffic = null
        exitMap = null; exitLocation = null; exitAddress = null
        connectButton = null; statusTv = null; subTv = null
        downloadTv = null; uploadTv = null
        fName = null; fHost = null; fPort = null; fUser = null; fPass = null
        authSwitch = null
        updateProtocolButtons = null
        ipVals = emptyMap()
        detailsBadge = null
        detailsStatus = null
        detailsDot = null
        detailsSummary = null
        diagnosticsView = null
        navItems.forEachIndexed { k, v ->
            v.isSelected = k == i
            val color = if (k == i) ACCENT else MUTED
            v.setTextColor(color)
            v.compoundDrawableTintList = ColorStateList.valueOf(color)
            v.background = ripple(if (k == i) SURFACE2 else SURFACE, 12, if (k == i) OUTLINE else 0)
        }
        content.removeAllViews()
        content.addView(
            when (i) {
                0 -> homeView()
                1 -> savedView()
                2 -> appsView()
                else -> settingsView()
            }
        )
    }

    // ---------- connection ----------

    private fun collectForm() {
        if (fHost == null) return
        profile.name = fName.str().ifEmpty { "New Profile" }
        profile.host = fHost.str()
        profile.port = fPort.str().toIntOrNull() ?: 0
        profile.user = fUser.str()
        profile.pass = fPass?.text?.toString().orEmpty()
    }

    private fun toggle() {
        if (ProxyVpnService.status != "Disconnected") {
            startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
            return
        }
        collectForm()
        if (profile.host.isBlank()) {
            fHost?.error = "Enter a server address"
            fHost?.requestFocus()
            return
        }
        if (profile.port !in 1..65535) {
            fPort?.error = "Enter a port from 1 to 65535"
            fPort?.requestFocus()
            return
        }
        if (profile.auth && profile.user.isBlank()) {
            fUser?.error = "Enter the username"
            fUser?.requestFocus()
            return
        }
        if (!Store.setActive(this, profile)) {
            toast("Encrypted profile storage is unavailable")
            return
        }
        try {
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, 1) else startVpn()
        } catch (e: RuntimeException) {
            ProxyVpnService.error = "Could not request VPN permission: ${e.message ?: e.javaClass.simpleName}"
            refreshHome()
        }
    }

    private fun startVpn() {
        ProxyVpnService.error = null
        ProxyVpnService.status = "Connecting"
        try {
            startForegroundService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_START))
        } catch (e: RuntimeException) {
            ProxyVpnService.status = "Disconnected"
            ProxyVpnService.error = "Could not start the VPN service: ${e.message ?: e.javaClass.simpleName}"
        }
        refreshHome()
    }

    private fun reconnect() {
        startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_RESTART))
    }

    private fun refreshHome() {
        val state = ProxyVpnService.status
        val connected = state == "Connected"
        val disconnected = state == "Disconnected"
        val error = ProxyVpnService.error
        homeStatusDot?.setColor(when {
            disconnected && error != null -> RED
            connected || disconnected -> HOME_GREEN
            else -> AMBER
        })
        homeTraffic?.visibility = if (connected) View.VISIBLE else View.GONE
        statusTv?.text = when {
            disconnected && error != null -> "Connection failed"
            disconnected -> "Ready to connect"
            else -> state
        }
        updateConnectionDetailsStatus(state)
        connectButton?.apply {
            text = when {
                connected -> "Disconnect"
                disconnected -> "\u25C9  Connect"
                state == "Disconnecting" -> "Disconnecting\u2026"
                else -> "Cancel"
            }
            contentDescription = if (disconnected) "Connect" else null
            isEnabled = state != "Disconnecting"
            background = if (disconnected) {
                val fill = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(HOME_BLUE, Color.parseColor("#105CB8"))
                ).apply { cornerRadius = dp(18).toFloat() }
                RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, null)
            } else {
                ripple(SURFACE, 18, HOME_BORDER)
            }
            setTextColor(if (disconnected) ON_ACCENT else HOME_BLUE)
        }

        subTv?.apply {
            setTextColor(if (disconnected && error != null) RED else HOME_MUTED)
            maxWidth = if (disconnected && error == null) Int.MAX_VALUE else dp(180)
            text = when {
                disconnected && error != null -> error
                disconnected -> "No active connection"
                else -> "${profile.host}:${profile.port} \u00B7 ${profile.protocol}"
            }
        }
        if (!connected) {
            ipDetails = null
            ipLookupInProgress = false
            ipLookupGeneration++
        } else if (!locked && (exitMap != null || ipVals.isNotEmpty() || diagnosticsView != null)
            && ipDetails == null && !ipLookupInProgress) {
            lookupIp()
        }
        renderIp()
        updateDiagnostics()
        updateStats()
    }

    private fun updateStats() {
        val down = downloadTv ?: return
        val up = uploadTv ?: return
        if (ProxyVpnService.status != "Connected") {
            down.text = "—"
            up.text = "—"
            return
        }
        if (ProxyVpnService.usingDnsOverTcp) {
            down.text = "—"
            up.text = "—"
            return
        }
        try {
            val stats = TProxyService.TProxyGetStats()
            down.text = fmt(stats[3])
            up.text = fmt(stats[1])
        } catch (_: Throwable) {
            down.text = "—"
            up.text = "—"
        }
    }

    private fun renderIp() {
        ipVals.forEach { (label, value) ->
            value.text = ipDetails?.get(label)?.takeIf { it.isNotBlank() }
                ?: if (ipLookupInProgress && label == "IP address") "Checking…" else "--"
        }
        val map = exitMap ?: return
        val state = ProxyVpnService.status
        val details = ipDetails.takeIf { state == "Connected" }
        val coordinates = ExitCoordinates.parse(details?.get("Latitude"), details?.get("Longitude"))
        val searching = state == "Connecting" || (state == "Connected" && ipLookupInProgress)
        val place = listOfNotNull(details?.get("City"), details?.get("Country"))
            .filter { it.isNotBlank() }.distinct().joinToString(", ")
        map.show(coordinates, searching)
        exitLocation?.text = when {
            state == "Connecting" -> "Connecting\u2026"
            state == "Disconnecting" -> "Disconnecting\u2026"
            searching -> "Locating exit IP\u2026"
            place.isNotEmpty() -> place
            state == "Connected" -> "Location unavailable"
            else -> "No active connection"
        }
        exitAddress?.apply {
            text = details?.get("IP address")?.takeUnless { it == "Unavailable" }.orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun lookupIp() {
        val pr = ProxyVpnService.sessionProfile ?: return
        val generation = ++ipLookupGeneration
        ipLookupInProgress = true
        renderIp()
        Thread {
            var res: Map<String, String>? = null
            try {
                val host = "ipwho.is"
                val response = Upstream.open(pr, host, 443).use { raw ->
                    Upstream.verifiedTls(raw, host, 443).use { ssl ->
                        ssl.outputStream.write("GET / HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                        ssl.outputStream.flush()
                        val buffer = ByteArray(32_768)
                        var count = 0
                        while (count < buffer.size) {
                            val read = ssl.inputStream.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        check(count < buffer.size)
                        String(buffer, 0, count, Charsets.UTF_8)
                    }
                }
                check(response.lineSequence().first().split(" ").getOrNull(1) == "200")
                val json = response.substringAfter("\r\n\r\n")
                val j = JSONObject(json)
                check(j.optBoolean("success") && j.optString("ip").isNotBlank())
                res = mapOf(
                    "IP address" to j.optString("ip"),
                    "Country" to j.optString("country"),
                    "Region" to j.optString("region"),
                    "City" to j.optString("city"),
                    "Latitude" to j.optDouble("latitude").toString(),
                    "Longitude" to j.optDouble("longitude").toString(),
                    "ISP" to (j.optJSONObject("connection")?.optString("isp") ?: ""),
                    "ZIP" to j.optString("postal")
                )
            } catch (_: Throwable) {}
            runOnUiThread {
                if (generation != ipLookupGeneration || ProxyVpnService.status != "Connected") return@runOnUiThread
                ipLookupInProgress = false
                ipDetails = res ?: mapOf("IP address" to "Unavailable")
                renderIp()
                updateDiagnostics()
            }
        }.start()
    }

    private fun applyPasted(raw: String) {
        val pasted = raw.trim()
        val scheme = pasted.substringBefore("://", "").uppercase()
        val hasProtocol = scheme in listOf("SOCKS5", "HTTP", "HTTPS")
        if (hasProtocol) {
            profile.protocol = scheme
            updateProtocolButtons?.invoke()
        }
        var s = if (hasProtocol) pasted.substringAfter("://") else pasted
        var user = ""
        var pass = ""
        var hp = s
        if ("@" in s) {
            val cred = s.substringBeforeLast("@")
            hp = s.substringAfterLast("@")
            user = cred.substringBefore(":")
            pass = cred.substringAfter(":", "")
        } else if (s.count { it == ':' } == 3) {
            val p = s.split(":")
            hp = p[0] + ":" + p[1]; user = p[2]; pass = p[3]
        }
        s = hp
        fHost?.setText(s.substringBeforeLast(":"))
        if (":" in s) fPort?.setText(s.substringAfterLast(":"))
        if (user.isNotEmpty()) {
            profile.auth = true
            fUser?.setText(user)
            fPass?.setText(pass)
            authSwitch?.isChecked = true
        }
    }

    // ---------- Home ----------

    private fun homeView(): View {
        val page = vbox().apply {
            setPadding(dp(4), dp(4), dp(4), dp(10))
            setBackgroundColor(HOME_BG)
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo)
                background = round(Color.parseColor("#1E2235"), 16)
                setPadding(dp(6), dp(5), dp(6), dp(5))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp(dp(46), dp(46)))
            addView(vbox().apply {
                addView(tv("Supproxy", 18f, TEXT).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    includeFontPadding = false
                    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                })
                addView(tv("Setup", 12f, HOME_MUTED, true).apply {
                    includeFontPadding = false
                    letterSpacing = 0.04f
                })
            }, lp(0, WRAP, 1f).apply { marginStart = dp(8) })
        }
        page.addView(header, lp(MATCH, WRAP))
        page.addView(exitMapCard(), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        val form = vbox(12).apply { background = round(SURFACE, 20, HOME_BORDER) }
        page.addView(form, lp(MATCH, WRAP).apply { topMargin = dp(8) })

        fun row() = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        fun tile() = vbox().apply {
            minimumHeight = dp(68)
            gravity = Gravity.CENTER_VERTICAL
            background = round(HOME_BG, 18, HOME_BORDER)
            setPaddingRelative(dp(13), dp(8), dp(13), dp(8))
        }
        fun caption(title: String) = tv(title, 12f, HOME_MUTED, true).apply {
            letterSpacing = 0.035f
        }
        fun input(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            field(hint, value, type).apply {
                id = View.generateViewId()
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setHintTextColor(HOME_MUTED)
                background = null
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
            }
        fun icon(res: Int) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(HOME_MUTED)
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun inputTile(title: String, edit: EditText, action: View? = null) = tile().apply {
            addView(caption(title).apply { labelFor = edit.id })
            addView(row().apply {
                addView(edit, lp(0, WRAP, 1f))
                action?.let { addView(it, lp(dp(36), dp(36))) }
            }, lp(MATCH, WRAP))
            edit.setOnFocusChangeListener { _, focused ->
                background = round(HOME_BG, 18, if (focused) HOME_BLUE else HOME_BORDER)
            }
        }
        fun addTile(view: View) {
            form.addView(view, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        }

        val protocols = arrayOf("SOCKS5", "HTTP", "HTTPS")
        val protocolValue = tv(profile.protocol, 17f, TEXT, true)
        val protocolTile = tile().apply {
            addView(row().apply {
                addView(vbox().apply {
                    addView(caption("Protocol"))
                    addView(protocolValue)
                }, lp(0, WRAP, 1f))
                addView(icon(R.drawable.home_chevron), lp(dp(24), dp(24)))
            }, lp(MATCH, WRAP))
            foreground = ripple(Color.TRANSPARENT, 18)
            isFocusable = true
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Proxy protocol")
                    .setSingleChoiceItems(protocols, protocols.indexOf(profile.protocol)) { dialog, selected ->
                        profile.protocol = protocols[selected]
                        updateProtocolButtons?.invoke()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        updateProtocolButtons = { protocolValue.text = profile.protocol }
        val profileName = input("New Profile", profile.name)
        fName = profileName
        form.addView(row().apply {
            addView(inputTile("Profile", profileName), lp(0, WRAP, 1f).apply { marginEnd = dp(9) })
            addView(protocolTile, lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP))

        val host = input("Server address", profile.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = input("Port", profile.port.takeIf { it > 0 }?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        fHost = host
        fPort = port
        val check = tv("\u2713", 21f, HOME_GREEN, true).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val separator = tv(":", 17f, TEXT, true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val serverAddress = row().apply {
            addView(host, lp(WRAP, WRAP))
            addView(separator, lp(WRAP, WRAP))
            addView(port, lp(WRAP, WRAP))
        }
        fun fitHost(availableWidth: Int) {
            if (availableWidth <= 0) return
            val portText = port.text.ifEmpty { port.hint }
            val reservedWidth = port.paint.measureText(portText.toString()) + separator.paint.measureText(":")
            host.maxWidth = (availableWidth - kotlin.math.ceil(reservedWidth).toInt()).coerceAtLeast(1)
        }
        serverAddress.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) fitHost(right - left)
        }
        val endpoint = tile().apply {
            addView(row().apply {
                addView(vbox().apply {
                    addView(caption("Server : Port").apply { labelFor = host.id })
                    addView(serverAddress, lp(MATCH, WRAP))
                }, lp(0, WRAP, 1f))
                addView(check, lp(dp(18), WRAP).apply { marginStart = dp(4) })
                addView(View(this@MainActivity).apply { setBackgroundColor(HOME_BORDER) },
                    lp(dp(1), dp(30)).apply { marginStart = dp(7); marginEnd = dp(7) })
                addView(button("Paste", false) {
                    val cm = getSystemService(ClipboardManager::class.java)
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
                    if (text.isNullOrBlank()) toast("Clipboard is empty") else applyPasted(text)
                }.apply {
                    background = ripple(Color.TRANSPARENT, 12)
                    setTextColor(HOME_BLUE)
                    setPaddingRelative(dp(3), 0, 0, 0)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.home_clipboard, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(HOME_BLUE)
                    compoundDrawablePadding = dp(5)
                    contentDescription = "Paste server from clipboard"
                }, lp(WRAP, WRAP))
            }, lp(MATCH, WRAP))
        }
        fun updateEndpointCheck() {
            val portNumber = port.str().toIntOrNull()
            check.visibility = if (host.str().isNotBlank() && portNumber != null && portNumber in 1..65535)
                View.VISIBLE else View.INVISIBLE
        }
        val endpointWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateEndpointCheck()
            override fun afterTextChanged(s: Editable?) {
                if (s === port.text) fitHost(serverAddress.width)
            }
        }
        for (edit in listOf(host, port)) {
            edit.addTextChangedListener(endpointWatcher)
            edit.setOnFocusChangeListener { _, _ ->
                endpoint.background = round(HOME_BG, 18, if (endpoint.hasFocus()) HOME_BLUE else HOME_BORDER)
            }
        }
        updateEndpointCheck()
        addTile(endpoint)

        val username = input("Username", profile.user)
        val password = input("Password", profile.pass,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        fUser = username
        fPass = password
        val visibilityButton = icon(R.drawable.home_eye_off).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Show password"
            background = ripple(Color.TRANSPARENT, 12)
            isFocusable = true
            setOnClickListener {
                val hidden = password.transformationMethod is android.text.method.PasswordTransformationMethod
                password.transformationMethod = if (hidden) null else
                    android.text.method.PasswordTransformationMethod.getInstance()
                setImageResource(if (hidden) R.drawable.home_eye else R.drawable.home_eye_off)
                contentDescription = if (hidden) "Hide password" else "Show password"
                password.setSelection(password.text.length)
            }
        }
        val authBox = vbox().apply {
            addView(inputTile("Username", username, icon(R.drawable.home_person)),
                lp(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(inputTile("Password", password, visibilityButton),
                lp(MATCH, WRAP).apply { topMargin = dp(10) })
            visibility = if (profile.auth) View.VISIBLE else View.GONE
        }
        authSwitch = Switch(this).apply {
            text = "Authentication required"
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.025f
            setTextColor(TEXT)
            isChecked = profile.auth
            minHeight = dp(48)
            switchMinWidth = dp(58)
            switchPadding = dp(8)
            splitTrack = false
            thumbDrawable = android.graphics.drawable.InsetDrawable(round(SURFACE, 20).apply {
                setSize(dp(27), dp(27))
            }, dp(4))
            trackDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), round(HOME_BLUE, 22).apply {
                    setSize(dp(58), dp(36))
                })
                addState(intArrayOf(), round(HOME_BORDER, 22).apply { setSize(dp(58), dp(36)) })
            }
            thumbTintList = null
            trackTintList = null
            setOnCheckedChangeListener { _, checked ->
                profile.auth = checked
                authBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        addTile(tile().apply { addView(authSwitch, lp(MATCH, WRAP)) })
        form.addView(authBox, lp(MATCH, WRAP))

        connectButton = button("Connect", true) { toggle() }.apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.015f
            minimumHeight = dp(54)
        }
        page.addView(connectButton, lp(MATCH, WRAP).apply { topMargin = dp(7) })

        val status = row().apply {
            minimumHeight = dp(44)
            background = round(SURFACE, 14, HOME_BORDER)
            setPaddingRelative(dp(15), dp(6), dp(15), dp(6))
        }
        val dot = round(HOME_GREEN, 8)
        homeStatusDot = dot
        status.addView(View(this).apply { background = dot }, lp(dp(10), dp(10)))
        statusTv = tv("", 15f, TEXT, true).apply {
            letterSpacing = 0.02f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        status.addView(statusTv, lp(0, WRAP, 1f).apply { marginStart = dp(9) })
        subTv = tv("", 13f, HOME_MUTED).apply {
            letterSpacing = 0.025f
            gravity = Gravity.END
        }
        status.addView(subTv, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        page.addView(status, lp(MATCH, WRAP).apply { topMargin = dp(7) })

        val traffic = row()
        fun addMetric(title: String, leading: Boolean): TextView {
            val value = tv("\u2014", 14f, TEXT, true)
            traffic.addView(tile().apply {
                addView(caption(title))
                addView(value)
            }, lp(0, WRAP, 1f).apply { if (leading) marginEnd = dp(9) })
            return value
        }
        downloadTv = addMetric("Received", true)
        uploadTv = addMetric("Sent", false)
        homeTraffic = traffic
        page.addView(traffic, lp(MATCH, WRAP).apply { topMargin = dp(7) })
        page.addView(button("Save profile", false) {
            collectForm()
            if (profile.host.isBlank()) { toast("Enter a server first"); return@button }
            val l = Store.saved(this)
            val idx = l.indexOfFirst { it.name == profile.name }
            if (idx >= 0) l[idx] = profile.copy() else l.add(profile.copy())
            if (!Store.setSaved(this, l)) {
                toast("Could not encrypt the profile")
                return@button
            }
            toast("Saved \"${profile.name}\"")
        }.apply {
            background = ripple(SURFACE, 14, HOME_BORDER)
            setTextColor(HOME_BLUE)
        }, lp(MATCH, WRAP).apply { topMargin = dp(7) })

        refreshHome()
        return scroll(page)
    }
    private fun exitMapCard() = vbox().apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1C2445.toInt(), 0xFF10162C.toInt())
        ).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), 0xFF303A63.toInt())
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(tv("Proxy exit location", 12f, 0xFFBDBAEF.toInt(), true).apply {
            letterSpacing = 0.035f
        })
        exitMap = ExitMapView(this@MainActivity)
        addView(exitMap, lp(MATCH, dp(128)))
        exitLocation = tv("", 17f, Color.WHITE, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(exitLocation, lp(MATCH, WRAP))
        exitAddress = tv("", 12f, 0xFFBDBAEF.toInt())
        addView(exitAddress, lp(MATCH, WRAP))
    }

    private fun updateConnectionDetailsStatus(state: String) {
        detailsBadge?.text = "•  $state"
        detailsDot?.setColor(when (state) {
            "Connected" -> GREEN
            "Disconnected" -> if (ProxyVpnService.error == null) GREEN else RED
            else -> AMBER
        })
        detailsStatus?.text = when (state) {
            "Disconnected" -> if (ProxyVpnService.error == null) "Ready to connect" else "Connection failed"
            else -> state
        }
        detailsSummary?.text = when (state) {
            "Connected" -> "Proxy active"
            "Disconnected" -> "No active connection"
            else -> "Please wait"
        }
    }

    private fun connectionDetails(): View {
        val navy = Color.parseColor("#092441")
        val blue = Color.parseColor("#153C65")
        val paleBlue = Color.parseColor("#C7D8EB")
        val mint = Color.parseColor("#57E5AA")
        val section = vbox().apply {
            layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(16) }
        }

        val strip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = round(SURFACE, 15, OUTLINE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minimumHeight = dp(48)
        }
        detailsStatus = tv("", 14f, TEXT, true)
        detailsSummary = tv("", 12f, MUTED).apply { gravity = Gravity.END }
        detailsDot = round(GREEN, 5)
        strip.addView(View(this).apply { background = detailsDot },
            lp(dp(9), dp(9)).apply { marginEnd = dp(8) })
        strip.addView(detailsStatus, lp(0, WRAP, 1f))
        strip.addView(detailsSummary, lp(0, WRAP, 1f).apply { marginStart = dp(8) })
        section.addView(strip, lp(MATCH, WRAP))

        val panel = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blue, navy)).apply {
                cornerRadius = dp(22).toFloat()
            }
            clipToOutline = true
        }
        panel.addView(View(this).apply {
            background = round(0x185F93C3, 72)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(144), dp(144), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(35)
        })
        panel.addView(View(this).apply {
            background = round(0x365F93C3, 24)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
            marginStart = dp(13)
            topMargin = dp(64)
        })

        panel.addView(View(this).apply {
            background = round(Color.TRANSPARENT, 6, paleBlue)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.START).apply {
            marginStart = dp(31)
            topMargin = dp(82)
        })

        val body = vbox().apply { setPadding(dp(13), dp(12), dp(13), dp(21)) }
        detailsBadge = tv("", 12f, paleBlue, true).apply {
            background = round(0x40577EA8, 20)
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }
        body.addView(detailsBadge, lp(WRAP, WRAP))
        val address = tv("--", 25f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }
        body.addView(address, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        body.addView(tv("Your current IP address", 14f, paleBlue, true).apply {
            gravity = Gravity.CENTER
            labelFor = View.generateViewId().also { address.id = it }
        }, lp(MATCH, WRAP).apply { topMargin = dp(2) })

        val values = linkedMapOf("IP address" to address)
        val fields = listOf("Country" to "Country", "State" to "Region", "ISP" to "ISP", "City" to "City", "ZIP" to "ZIP")
        val columns = if (resources.configuration.screenWidthDp >= 340 &&
            resources.configuration.fontScale <= 1.15f) 5 else 3
        fields.chunked(columns).forEach { group ->
            val row = LinearLayout(this).apply { isBaselineAligned = false }
            group.forEachIndexed { index, (label, key) ->
                if (index > 0) {
                    row.addView(View(this).apply { setBackgroundColor(0x505C86B2) }, lp(dp(1), MATCH))
                }
                val column = vbox().apply {
                    if (index > 0) setPaddingRelative(dp(5), 0, 0, 0)
                }
                column.addView(tv(label, 11f, paleBlue))
                val value = tv("--", 12f, Color.WHITE, true)
                column.addView(value, lp(MATCH, WRAP).apply { topMargin = dp(9) })
                row.addView(column, lp(0, WRAP, 1f))
                values[key] = value
            }
            body.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(15) })
        }
        ipVals = values
        panel.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        panel.addView(View(this).apply { setBackgroundColor(mint) },
            FrameLayout.LayoutParams(MATCH, dp(3), Gravity.BOTTOM))
        section.addView(panel, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        return section
    }

    // ---------- Saved ----------

    private fun savedView(): View {
        val p = page("Profiles", "Load a saved proxy server")
        val list = Store.saved(this)
        if (list.isEmpty()) {
            val c = card()
            c.addView(tv("No saved profiles", 18f, TEXT, true))
            c.addView(tv("Enter a proxy server to save your first profile.", 14f, MUTED))
            c.addView(button("Set up a server", true) { show(0) },
                lp(MATCH, WRAP).apply { topMargin = dp(12) })
            p.addView(c)
        }
        list.forEachIndexed { index, saved ->
            val profileCard = card()
            profileCard.addView(tv(saved.name, 19f, TEXT, true))
            profileCard.addView(tv("${saved.protocol} · ${saved.host}:${saved.port}", 14f, MUTED),
                lp(MATCH, WRAP).apply { topMargin = dp(4) })

            val actions = LinearLayout(this)
            actions.addView(button("Load profile", true) {
                profile = saved.copy()
                if (!Store.setActive(this, profile)) {
                    toast("Encrypted profile storage is unavailable")
                    return@button
                }
                show(0)
                toast("Loaded ${saved.name}")
            }, lp(0, WRAP, 1f).apply { marginEnd = dp(8) })
            actions.addView(button("Delete", false) {
                AlertDialog.Builder(this)
                    .setTitle("Delete profile?")
                    .setMessage(saved.name)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        val profiles = Store.saved(this)
                        if (profiles.getOrNull(index) == saved) {
                            profiles.removeAt(index)
                            if (!Store.setSaved(this, profiles)) toast("Could not update encrypted profiles")
                        }
                        show(1)
                    }
                    .show()
            }.apply { setTextColor(RED) }, lp(WRAP, WRAP))
            profileCard.addView(actions, lp(MATCH, WRAP).apply { topMargin = dp(14) })
            p.addView(profileCard)
        }
        return scroll(p)
    }

    // ---------- Apps ----------

    private fun appsView(): View {
        val p = page("Apps", "Choose which apps use the proxy")
        val excluded = Store.excluded(this)
        val whole = Store.wholePhone(this)

        val ic = card()
        if (whole) {
            ic.addView(tv("Connection mode is Whole phone, so every app uses the proxy and this list is ignored.", 14f, TEXT))
            ic.addView(button("Use selected apps instead", true) {
                Store.setWholePhone(this, false)
                show(2)
            }, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        } else {
            ic.addView(tv("Unticked apps bypass the proxy. Changes take effect on the next connection.", 14f, TEXT))
            if (ProxyVpnService.status != "Disconnected") {
                ic.addView(button("Reconnect now", true) { reconnect() }, lp(MATCH, WRAP).apply { topMargin = dp(12) })
            }
        }
        p.addView(ic)

        val count = tv("Loading apps...", 13f, MUTED).apply { setPadding(0, dp(14), 0, 0) }
        p.addView(count)
        val search = field("Search apps", "")
        p.addView(search, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        val bar = LinearLayout(this)
        p.addView(bar, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        val listBox = vbox()
        p.addView(listBox)

        Thread {
            val pm = packageManager
            val apps = Store.routablePackages(this)
                .mapNotNull {
                    try {
                        val ai = pm.getApplicationInfo(it, 0)
                        Triple(it, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
                    } catch (_: Throwable) { null }
                }
                .sortedBy { it.second.lowercase() }

            runOnUiThread {
                fun updateCount() {
                    val selected = if (whole) apps.size else apps.count { it.first !in excluded }
                    count.text = "$selected of ${apps.size} apps use the proxy"
                }
                fun fill(q: String) {
                    listBox.removeAllViews()
                    for (a in apps) {
                        if (q.isNotBlank() && !a.second.contains(q, true)) continue
                        val c = card().apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
                        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                        r.addView(ImageView(this).apply { setImageDrawable(a.third) }, lp(dp(40), dp(40)))
                        val t = vbox().apply { setPadding(dp(12), 0, dp(8), 0) }
                        t.addView(tv(a.second, 16f, TEXT, true))
                        t.addView(tv(a.first, 11f, MUTED))
                        r.addView(t, lp(0, WRAP, 1f))
                        val cb = CheckBox(this).apply {
                            contentDescription = a.second
                            isEnabled = !whole
                            isChecked = whole || a.first !in excluded
                            setOnCheckedChangeListener { _, on ->
                                if (on) excluded.remove(a.first) else excluded.add(a.first)
                                Store.setExcluded(this@MainActivity, excluded)
                                updateCount()
                            }
                        }
                        r.addView(cb)
                        c.addView(r)
                        if (!whole) c.setOnClickListener { cb.toggle() }
                        listBox.addView(c)
                    }
                }
                updateCount()
                if (!whole) {
                    bar.addView(button("Select all", false) {
                        excluded.clear()
                        Store.setExcluded(this, excluded)
                        fill(search.str())
                        updateCount()
                    }, lp(0, WRAP, 1f).apply { marginEnd = dp(4) })
                    bar.addView(button("Clear all", false) {
                        excluded.addAll(apps.map { it.first })
                        Store.setExcluded(this, excluded)
                        fill(search.str())
                        updateCount()
                    }, lp(0, WRAP, 1f).apply { marginStart = dp(4) })
                }
                search.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { fill(s?.toString().orEmpty()) }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                fill("")
            }
        }.start()
        return scroll(p)
    }

    // ---------- Settings ----------

    private fun updateDiagnostics() {
        val connected = ProxyVpnService.status == "Connected"
        val exit = ipDetails?.get("IP address")
        val verified = when {
            !connected -> "Waiting for connection"
            ipLookupInProgress -> "Checking HTTPS through the proxy"
            exit.isNullOrBlank() || exit == "Unavailable" -> "Proxy egress unverified"
            else -> "Verified HTTPS egress • $exit"
        }
        val lockdown = when (ProxyVpnService.systemLockdown) {
            true -> "Enabled"
            false -> "Disabled • traffic can bypass the VPN if it stops"
            null -> "Check Android VPN settings"
        }
        val routing = when {
            !connected -> "VPN routes inactive"
            ProxyVpnService.routedPackages == null -> "IPv4 and IPv6 routed for whole phone"
            else -> "IPv4 and IPv6 routed for selected apps"
        }
        val dns = when {
            !connected -> "Inactive"
            ProxyVpnService.usingDnsOverTcp -> "DNS-over-TCP configured • leak test available"
            else -> "Mapped DNS configured • leak test available"
        }
        val transport = when (ProxyVpnService.sessionProfile?.protocol) {
            "HTTPS" -> "TLS to the proxy with certificate and hostname validation"
            "SOCKS5", "HTTP" -> "Proxy link is unencrypted • credentials can be exposed"
            else -> "No active proxy"
        }
        diagnosticsView?.text = listOf(
            verified, "Kill switch: $lockdown", routing, "DNS: $dns", transport,
            "Always-on VPN: " + when (ProxyVpnService.systemAlwaysOn) {
                true -> "Enabled"
                false -> "Disabled"
                null -> "Check Android VPN settings"
            },
            if (Store.storageUnavailable) "Encrypted profile storage unavailable"
            else "Profile storage: Android Keystore"
        ).joinToString("\n\n")
    }

    private fun openLeakTest(path: String) {
        if (ProxyVpnService.status != "Connected") {
            toast("Connect the VPN before testing")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://browserleaks.com/$path"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val allowed = ProxyVpnService.routedPackages
        val browsers = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .distinctBy { it.activityInfo.packageName }
            .filter { allowed == null || it.activityInfo.packageName in allowed }
        if (browsers.isEmpty()) {
            toast("Enable a browser in your VPN app selection first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose a protected browser")
            .setItems(browsers.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index ->
                val activity = browsers[index].activityInfo
                val current = ProxyVpnService.routedPackages
                if (ProxyVpnService.status == "Connected" && (current == null || activity.packageName in current)) {
                    try {
                        startActivity(Intent(intent).setClassName(activity.packageName, activity.name))
                    } catch (_: android.content.ActivityNotFoundException) {
                        toast("The selected browser is unavailable")
                    }
                } else toast("VPN routing changed. Reconnect and try again.")
            }.show()
    }

    private fun settingsView(): View {
        val p = page("Settings", "Connection and app preferences")

        val c1 = card()
        c1.addView(tv("Connection", 14f, TEXT, true))
        c1.addView(row("VPN kill switch", "In Android VPN settings, enable Always-on VPN and Block connections without VPN.",
            link("Configure")) {
            try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } catch (_: Throwable) { toast("Open Settings > Network > VPN") }
        })
        c1.addView(divider(), lp(MATCH, dp(1)))
        c1.addView(row("Auto reconnect", "Recover the tunnel automatically if the proxy drops",
            Switch(this).apply {
                contentDescription = "Auto reconnect"
                isChecked = Store.autoReconnect(this@MainActivity)
                setOnCheckedChangeListener { _, v -> Store.setAutoReconnect(this@MainActivity, v) }
            }, null))
        c1.addView(divider(), lp(MATCH, dp(1)))
        c1.addView(row("DNS over TCP", "Route DNS through the proxy to 1.1.1.1 over TCP. Changes apply on reconnect.",
            Switch(this).apply {
                contentDescription = "DNS over TCP"
                isChecked = Store.dnsOverTcp(this@MainActivity)
                setOnCheckedChangeListener { _, enabled -> Store.setDnsOverTcp(this@MainActivity, enabled) }
            }, null))
        c1.addView(divider(), lp(MATCH, dp(1)))
        c1.addView(row("Connection mode", "Choose what routes through the proxy",
            link(if (Store.wholePhone(this)) "Whole phone" else "Selected apps")) {
            AlertDialog.Builder(this).setTitle("Connection mode")
                .setSingleChoiceItems(arrayOf("Whole phone", "Selected apps"), if (Store.wholePhone(this)) 0 else 1) { d, w ->
                    Store.setWholePhone(this, w == 0)
                    d.dismiss()
                    show(3)
                }.show()
        })
        c1.addView(divider(), lp(MATCH, dp(1)))
        val battOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        c1.addView(row("Background stability", "Allow unrestricted battery use so the tunnel stays alive",
            link(if (battOk) "OK" else "Fix", if (battOk) GREEN else ACCENT)) {
            if (!battOk) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Throwable) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) {}
                }
            }
        })
        p.addView(c1)

        val security = card()
        security.addView(tv("Security diagnostics", 14f, TEXT, true))
        diagnosticsView = tv("", 14f, TEXT).apply { setPadding(0, dp(12), 0, dp(12)) }
        security.addView(diagnosticsView, lp(MATCH, WRAP))
        security.addView(button("Verify proxy egress", false) {
            if (ProxyVpnService.status != "Connected") toast("Connect the VPN before verifying")
            else if (!ipLookupInProgress) {
                lookupIp()
                updateDiagnostics()
            }
        })
        security.addView(button("Run IP and IPv6 leak test", false) { openLeakTest("ip") },
            lp(MATCH, WRAP).apply { topMargin = dp(8) })
        security.addView(button("Run DNS leak test", false) { openLeakTest("dns") },
            lp(MATCH, WRAP).apply { topMargin = dp(8) })
        security.addView(tv("Browser tests send requests to BrowserLeaks and show results for the chosen browser.", 12f, MUTED),
            lp(MATCH, WRAP).apply { topMargin = dp(8) })
        p.addView(security)
        p.addView(connectionDetails())

        val privacy = card()
        privacy.addView(tv("Privacy controls", 14f, TEXT, true))
        privacy.addView(row("Device authentication", "Require your device screen lock to reopen Supproxy.",
            Switch(this).apply {
                contentDescription = "Device authentication"
                isChecked = Store.appLock(this@MainActivity)
                setOnCheckedChangeListener { toggle, enabled ->
                    if (enabled && !getSystemService(KeyguardManager::class.java).isDeviceSecure) {
                        toggle.isChecked = false
                        toast("Set a device screen lock first")
                    } else Store.setAppLock(this@MainActivity, enabled)
                }
            }, null))
        privacy.addView(divider(), lp(MATCH, dp(1)))
        privacy.addView(row("Protect on Wi-Fi", "Treat all Wi-Fi as untrusted and connect automatically while Supproxy is open. Requires VPN permission.",
            Switch(this).apply {
                contentDescription = "Protect on Wi-Fi"
                isChecked = Store.protectWifi(this@MainActivity)
                setOnCheckedChangeListener { _, enabled ->
                    Store.setProtectWifi(this@MainActivity, enabled)
                    lastWifi = null
                    maybeProtectWifi()
                }
            }, null))
        p.addView(privacy)
        refreshHome()

        val c2 = card()
        c2.addView(tv("About", 14f, TEXT, true))
        val n = Store.saved(this).size
        c2.addView(row("Saved profiles", "$n profile(s) saved on this device", link(if (n == 0) "Empty" else "View")) { show(1) })
        c2.addView(divider(), lp(MATCH, dp(1)))
        c2.addView(row("Privacy policy", "How routing and local storage are handled", link(">")) {
            info("Privacy policy",
                "Supproxy stores your proxy profiles and settings only on this device. It has no accounts, " +
                    "analytics or ads.\n\nTraffic from the apps you select is sent to the proxy server you enter, " +
                    "and that server's operator can see it. The Home screen's connection details are fetched " +
                    "from ipwho.is through your proxy.\n\nProfiles and credentials are encrypted with Android Keystore.")
        })
        c2.addView(divider(), lp(MATCH, dp(1)))
        c2.addView(row("Terms of use", "Acceptable use and your responsibilities", link(">")) {
            info("Terms of use",
                "Use Supproxy only with proxy servers you are allowed to use, and in line with the law and the " +
                    "terms of your network and proxy provider. The app is provided as is, without warranty.")
        })
        c2.addView(divider(), lp(MATCH, dp(1)))
        c2.addView(row("App version", "Android 8+ \u00B7 HTTP, HTTPS, and SOCKS5 client", link("1.0"), null))
        p.addView(c2)

        val c3 = card()
        c3.addView(tv("Support", 17f, TEXT, true))
        c3.addView(tv("When reporting a problem, include your device model, Android version, protocol, and the error shown. Never share your proxy password.", 13f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        p.addView(c3)
        p.addView(View(this), lp(MATCH, dp(16)))
        return scroll(p)
    }
}
