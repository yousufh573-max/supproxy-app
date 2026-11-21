package com.supproxy.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private val BG = Color.parseColor("#12151F")
private val SURFACE = Color.parseColor("#1B2030")
private val SURFACE2 = Color.parseColor("#252C42")
private val ACCENT = Color.parseColor("#6C5CE7")
private val GREEN = Color.parseColor("#2ED47A")
private val AMBER = Color.parseColor("#F2B84B")
private val RED = Color.parseColor("#FF6B7A")
private val TEXT = Color.parseColor("#F2F4FA")
private val MUTED = Color.parseColor("#8D96AD")
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private class PowerButton(c: Context) : View(c) {
    var state = 0 // 0 off, 1 busy, 2 on
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        val col = when (state) { 2 -> GREEN; 1 -> AMBER; else -> ACCENT }
        val rgb = col and 0x00FFFFFF
        p.style = Paint.Style.FILL
        p.color = rgb or 0x22000000
        cv.drawCircle(cx, cy, r, p)
        p.color = rgb or 0x44000000
        cv.drawCircle(cx, cy, r * 0.82f, p)
        p.color = col
        cv.drawCircle(cx, cy, r * 0.62f, p)
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.07f
        p.strokeCap = Paint.Cap.ROUND
        val ir = r * 0.24f
        val oy = r * 0.03f
        cv.drawArc(RectF(cx - ir, cy - ir + oy, cx + ir, cy + ir + oy), -55f, 290f, false, p)
        cv.drawLine(cx, cy - ir * 1.1f, cx, cy - ir * 0.1f, p)
    }
}

class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var navItems: List<TextView>
    private var tab = 0
    private var profile = Profile()
    private val ui = Handler(Looper.getMainLooper())

    // Home screen views (null while another tab is showing)
    private var power: PowerButton? = null
    private var statusTv: TextView? = null
    private var subTv: TextView? = null
    private var statsTv: TextView? = null
    private var ipVals: Map<String, TextView> = emptyMap()
    private var ipLoaded = false
    private var fName: EditText? = null
    private var fHost: EditText? = null
    private var fPort: EditText? = null
    private var fUser: EditText? = null
    private var fPass: EditText? = null

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
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun vbox(pad: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun card() = vbox(16).apply {
        background = round(SURFACE, 20)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
    }

    private fun page(title: String, sub: String) = vbox(20).also {
        it.addView(tv(title, 28f, TEXT, true))
        it.addView(tv(sub, 14f, MUTED))
    }

    private fun scroll(inner: View) = ScrollView(this).apply { addView(inner) }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        setHint(hint)
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 16f
        inputType = type
        setSingleLine(true)
        background = round(SURFACE2, 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun button(t: String, filled: Boolean, onClick: () -> Unit) =
        tv(t, 15f, if (filled) Color.WHITE else ACCENT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            background = round(if (filled) ACCENT else SURFACE2, 14)
            setOnClickListener { onClick() }
        }

    private fun divider() = View(this).apply { setBackgroundColor(SURFACE2) }

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
        window.statusBarColor = BG
        window.navigationBarColor = SURFACE
        profile = Store.active(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))

        val nav = LinearLayout(this).apply {
            setBackgroundColor(SURFACE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        navItems = listOf("Home", "Saved", "Apps", "Settings").mapIndexed { i, n ->
            tv(n, 14f, MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { show(i) }
            }
        }
        navItems.forEach { nav.addView(it, lp(0, WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        root.addView(nav, MATCH, WRAP)
        setContentView(root)

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
        if (tab == 3) show(3) else refreshHome()
    }

    override fun onPause() {
        super.onPause()
        collectForm()
        Store.setActive(this, profile)
        unregisterReceiver(receiver)
        ui.removeCallbacks(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) startVpn()
        else if (requestCode == 1) toast("VPN permission denied")
    }

    private fun show(i: Int) {
        collectForm()
        tab = i
        power = null; statusTv = null; subTv = null; statsTv = null
        fName = null; fHost = null; fPort = null; fUser = null; fPass = null
        ipVals = emptyMap()
        navItems.forEachIndexed { k, v ->
            v.setTextColor(if (k == i) Color.WHITE else MUTED)
            v.background = if (k == i) round(ACCENT, 14) else null
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
        if (profile.host.isBlank() || profile.port !in 1..65535) { toast("Enter a valid server and port"); return }
        if (profile.auth && profile.user.isBlank()) { toast("Enter the username"); return }
        Store.setActive(this, profile)
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 1) else startVpn()
    }

    private fun startVpn() {
        ProxyVpnService.error = null
        ProxyVpnService.status = "Connecting"
        startForegroundService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_START))
        refreshHome()
    }

    private fun reconnect() {
        startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
        ui.postDelayed({ if (ProxyVpnService.status == "Disconnected") startVpn() }, 900)
        toast("Reconnecting...")
    }

    private fun refreshHome() {
        val s = statusTv ?: return
        val st = ProxyVpnService.status
        power?.state = when (st) { "Connected" -> 2; "Connecting", "Reconnecting" -> 1; else -> 0 }
        s.text = when (st) { "Connecting" -> "Connecting..."; "Reconnecting" -> "Reconnecting..."; else -> st }
        val err = ProxyVpnService.error
        subTv?.apply {
            if (st == "Disconnected" && err != null) {
                text = "Error: $err"
                setTextColor(RED)
            } else {
                setTextColor(MUTED)
                text = if (st == "Disconnected") "Tap the button to connect"
                else "${profile.host}:${profile.port} \u00B7 ${profile.protocol}"
            }
        }
        if (st == "Connected") {
            if (!ipLoaded) { ipLoaded = true; lookupIp() }
        } else {
            ipLoaded = false
            ipVals.values.forEach { it.text = "--" }
        }
        updateStats()
    }

    private fun updateStats() {
        val t = statsTv ?: return
        if (ProxyVpnService.status != "Connected") { t.text = ""; return }
        try {
            val st = TProxyService.TProxyGetStats()
            t.text = "Down ${fmt(st[3])}   \u00B7   Up ${fmt(st[1])}"
        } catch (_: Throwable) {}
    }

    private fun lookupIp() {
        val pr = Store.active(this)
        ipVals["IP address"]?.text = "Checking..."
        Thread {
            var res: Map<String, String>? = null
            try {
                // The app itself is excluded from the tunnel, so go through the proxy explicitly
                val raw: Socket = Upstream.open(pr, "ipwho.is", 443)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, "ipwho.is", 443, true) as SSLSocket
                ssl.soTimeout = 12000
                ssl.startHandshake()
                ssl.outputStream.write("GET / HTTP/1.0\r\nHost: ipwho.is\r\nConnection: close\r\n\r\n".toByteArray())
                ssl.outputStream.flush()
                val body = ssl.inputStream.bufferedReader().readText()
                ssl.close()
                val j = JSONObject(body.substring(body.indexOf('{'), body.lastIndexOf('}') + 1))
                res = mapOf(
                    "IP address" to j.optString("ip"),
                    "Country" to j.optString("country"),
                    "Region" to j.optString("region"),
                    "City" to j.optString("city"),
                    "ISP" to (j.optJSONObject("connection")?.optString("isp") ?: ""),
                    "ZIP" to j.optString("postal")
                )
            } catch (_: Throwable) {}
            runOnUiThread {
                val r = res
                ipVals.forEach { (k, v) ->
                    v.text = if (r == null) (if (k == "IP address") "Unavailable" else "--")
                    else r[k].orEmpty().ifBlank { "--" }
                }
            }
        }.start()
    }

    private fun applyPasted(raw: String) {
        var s = raw.trim().removePrefix("socks5://").removePrefix("http://")
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
            collectForm()
            show(0)
        }
    }

    // ---------- Home ----------

    private fun homeView(): View {
        val p = vbox(20)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, lp(dp(38), dp(38)))
        head.addView(tv("Supproxy", 22f, TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, lp(0, WRAP, 1f))
        p.addView(head)

        power = PowerButton(this).apply { setOnClickListener { toggle() } }
        p.addView(power, lp(dp(200), dp(200)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(24) })
        statusTv = tv("", 24f, TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        subTv = tv("", 14f, MUTED).apply { gravity = Gravity.CENTER }
        statsTv = tv("", 13f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        p.addView(statusTv, MATCH, WRAP)
        p.addView(subTv, MATCH, WRAP)
        p.addView(statsTv, MATCH, WRAP)

        val ipc = card()
        ipc.addView(tv("Connection details", 13f, MUTED, true))
        val m = LinkedHashMap<String, TextView>()
        for (k in listOf("IP address", "Country", "Region", "City", "ISP", "ZIP")) {
            val r = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
            r.addView(tv(k, 14f, MUTED), lp(0, WRAP, 1f))
            val v = tv("--", 14f, TEXT, true).apply { gravity = Gravity.END }
            m[k] = v
            r.addView(v, lp(0, WRAP, 2f))
            ipc.addView(r)
        }
        ipVals = m
        p.addView(ipc)

        val sc = card()
        sc.addView(tv("Server", 13f, MUTED, true))
        val seg = LinearLayout(this).apply {
            background = round(SURFACE2, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val opts = listOf("SOCKS5", "HTTP")
        val segViews = opts.map { o ->
            tv(o, 14f, TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)) }
        }
        fun paintSeg() {
            segViews.forEachIndexed { i, v -> v.background = if (opts[i] == profile.protocol) round(ACCENT, 11) else null }
        }
        segViews.forEachIndexed { i, v ->
            v.setOnClickListener { profile.protocol = opts[i]; paintSeg() }
            seg.addView(v, lp(0, WRAP, 1f))
        }
        paintSeg()
        sc.addView(seg, lp(MATCH, WRAP).apply { topMargin = dp(10) })

        fName = field("Profile name", profile.name)
        sc.addView(fName, lp(MATCH, WRAP).apply { topMargin = dp(10) })

        fHost = field("proxy.example.com", profile.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        fPort = field("1080", if (profile.port > 0) profile.port.toString() else "", InputType.TYPE_CLASS_NUMBER)
        val hp = LinearLayout(this)
        hp.addView(fHost, lp(0, WRAP, 1f))
        hp.addView(fPort, lp(dp(100), WRAP).apply { leftMargin = dp(8) })
        sc.addView(hp, lp(MATCH, WRAP).apply { topMargin = dp(10) })

        sc.addView(link("Paste server from clipboard").apply {
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                val t = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (t.isNullOrBlank()) toast("Clipboard is empty") else applyPasted(t)
            }
        })

        val authBox = vbox()
        fUser = field("Username", profile.user)
        fPass = field("Password", profile.pass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        authBox.addView(fUser, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        authBox.addView(fPass, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        authBox.addView(link("Show / hide password").apply {
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                val e = fPass ?: return@setOnClickListener
                val hidden = (e.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
                e.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                e.setSelection(e.text.length)
            }
        })
        authBox.visibility = if (profile.auth) View.VISIBLE else View.GONE
        sc.addView(Switch(this).apply {
            text = "Requires login"
            textSize = 15f
            setTextColor(TEXT)
            isChecked = profile.auth
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c ->
                profile.auth = c
                authBox.visibility = if (c) View.VISIBLE else View.GONE
            }
        }, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        sc.addView(authBox)

        sc.addView(button("Save profile", false) {
            collectForm()
            if (profile.host.isBlank()) { toast("Enter a server first"); return@button }
            val l = Store.saved(this)
            val idx = l.indexOfFirst { it.name == profile.name }
            if (idx >= 0) l[idx] = profile.copy() else l.add(profile.copy())
            Store.setSaved(this, l)
            toast("Saved \"${profile.name}\"")
        }, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        p.addView(sc)
        p.addView(View(this), lp(MATCH, dp(16)))

        refreshHome()
        return scroll(p)
    }

    // ---------- Saved ----------

    private fun savedView(): View {
        val p = page("Saved", "Your proxy profiles")
        val list = Store.saved(this)
        if (list.isEmpty()) {
            val c = card()
            c.addView(tv("No saved profiles", 18f, TEXT, true))
            c.addView(tv("Fill in a server on Home and tap Save profile.", 14f, MUTED))
            p.addView(c)
        }
        list.forEachIndexed { idx, pr ->
            val c = card()
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val t = vbox()
            t.addView(tv(pr.name, 17f, TEXT, true))
            t.addView(tv("${pr.protocol} \u00B7 ${pr.host}:${pr.port}", 13f, MUTED))
            r.addView(t, lp(0, WRAP, 1f))
            r.addView(link("Delete", RED).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    val l = Store.saved(this@MainActivity)
                    if (idx < l.size) { l.removeAt(idx); Store.setSaved(this@MainActivity, l) }
                    show(1)
                }
            })
            c.addView(r)
            c.setOnClickListener {
                profile = pr.copy()
                Store.setActive(this, profile)
                show(0)
                toast("Loaded ${pr.name}")
            }
            p.addView(c)
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
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .mapNotNull {
                    try {
                        val ai = pm.getApplicationInfo(it, 0)
                        Triple(it, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
                    } catch (_: Throwable) { null }
                }
                .sortedBy { it.second.lowercase() }

            runOnUiThread {
                fun updateCount() {
                    count.text = "Installed apps \u00B7 ${apps.count { it.first !in excluded }}/${apps.size} use the proxy"
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
                            isChecked = a.first !in excluded
                            setOnCheckedChangeListener { _, on ->
                                if (on) excluded.remove(a.first) else excluded.add(a.first)
                                Store.setExcluded(this@MainActivity, excluded)
                                updateCount()
                            }
                        }
                        r.addView(cb)
                        c.addView(r)
                        c.setOnClickListener { cb.toggle() }
                        listBox.addView(c)
                    }
                }
                updateCount()
                bar.addView(button("Select all", false) {
                    excluded.clear(); Store.setExcluded(this, excluded); fill(search.str())
                    updateCount()
                }, lp(0, WRAP, 1f).apply { rightMargin = dp(4) })
                bar.addView(button("Clear all", false) {
                    excluded.addAll(apps.map { it.first }); Store.setExcluded(this, excluded); fill(search.str())
                    updateCount()
                }, lp(0, WRAP, 1f).apply { leftMargin = dp(4) })
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

    private fun settingsView(): View {
        val p = page("Settings", "Connection and app preferences")

        val c1 = card()
        c1.addView(tv("Connection", 13f, MUTED, true))
        c1.addView(row("Leak protection", "Turn on Always-on VPN and Block connections without VPN in system settings",
            link("Configure")) {
            try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } catch (_: Throwable) { toast("Open Settings > Network > VPN") }
        })
        c1.addView(divider(), lp(MATCH, 1))
        c1.addView(row("Auto reconnect", "Recover the tunnel automatically if the proxy drops",
            Switch(this).apply {
                isChecked = Store.autoReconnect(this@MainActivity)
                setOnCheckedChangeListener { _, v -> Store.setAutoReconnect(this@MainActivity, v) }
            }, null))
        c1.addView(divider(), lp(MATCH, 1))
        c1.addView(row("Connection mode", "Choose what routes through the proxy",
            link(if (Store.wholePhone(this)) "Whole phone" else "Selected apps")) {
            AlertDialog.Builder(this).setTitle("Connection mode")
                .setSingleChoiceItems(arrayOf("Whole phone", "Selected apps"), if (Store.wholePhone(this)) 0 else 1) { d, w ->
                    Store.setWholePhone(this, w == 0)
                    d.dismiss()
                    show(3)
                }.show()
        })
        c1.addView(divider(), lp(MATCH, 1))
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

        val c2 = card()
        c2.addView(tv("About", 13f, MUTED, true))
        val n = Store.saved(this).size
        c2.addView(row("Saved profiles", "$n profile(s) saved on this device", link(if (n == 0) "Empty" else "View")) { show(1) })
        c2.addView(divider(), lp(MATCH, 1))
        c2.addView(row("Privacy policy", "How routing and local storage are handled", link(">")) {
            info("Privacy policy",
                "Supproxy stores your proxy profiles and settings only on this device. It has no accounts, " +
                    "analytics or ads.\n\nTraffic from the apps you select is sent to the proxy server you enter, " +
                    "and that server's operator can see it. The Home screen's connection details are fetched " +
                    "from ipwho.is through your proxy.\n\nProxy passwords are kept in the app's private storage.")
        })
        c2.addView(divider(), lp(MATCH, 1))
        c2.addView(row("Terms of use", "Acceptable use and your responsibilities", link(">")) {
            info("Terms of use",
                "Use Supproxy only with proxy servers you are allowed to use, and in line with the law and the " +
                    "terms of your network and proxy provider. The app is provided as is, without warranty.")
        })
        c2.addView(divider(), lp(MATCH, 1))
        c2.addView(row("App version", "Android 8+ \u00B7 HTTP and SOCKS5 client", link("1.0"), null))
        p.addView(c2)

        val c3 = card()
        c3.addView(tv("Support", 17f, TEXT, true))
        c3.addView(tv("When reporting a problem, include your device model, Android version, the protocol you chose " +
            "(SOCKS5 or HTTP) and a screenshot. Never share your proxy password.", 13f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        p.addView(c3)
        p.addView(View(this), lp(MATCH, dp(16)))
        return scroll(p)
    }
}
