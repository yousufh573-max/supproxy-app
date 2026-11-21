package com.supproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class ProxyVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.supproxy.app.START"
        const val ACTION_STOP = "com.supproxy.app.STOP"
        const val ACTION_STATE = "com.supproxy.app.STATE"
        private const val CHANNEL = "vpn"

        /** Disconnected, Connecting, Connected or Reconnecting. */
        @Volatile var status = "Disconnected"
        @Volatile var error: String? = null
    }

    private var tun: ParcelFileDescriptor? = null
    private var bridge: HttpBridge? = null
    private var cfg: File? = null
    private var hevStarted = false
    @Volatile private var watching = false
    @Volatile private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = intent?.action
        if (a == ACTION_STOP) {
            error = null
            stopVpn()
            return START_NOT_STICKY
        }
        if (a != ACTION_START && a != SERVICE_INTERFACE) return START_NOT_STICKY
        if (tun != null) return START_NOT_STICKY

        try {
            showForeground()
        } catch (e: RuntimeException) {
            error = "Could not start the VPN service: ${e.message ?: e.javaClass.simpleName}"
            stopVpn()
            return START_NOT_STICKY
        }
        val pr = Store.active(this)
        if (pr.host.isBlank()) {
            error = "No server configured"
            stopVpn()
            return START_NOT_STICKY
        }
        error = null
        setStatus("Connecting")
        val gen = ++generation

        Thread {
            try {
                // Resolve before the tunnel exists so DNS still works
                val upIp = InetAddress.getByName(pr.host).hostAddress ?: throw IOException("Cannot resolve ${pr.host}")
                var th = upIp
                var tp = pr.port
                var tu = ""
                var tpw = ""
                val http = pr.protocol == "HTTP"
                if (http) {
                    val br = HttpBridge(upIp, pr.port, if (pr.auth) pr.user else null, pr.pass)
                    br.start()
                    bridge = br
                    th = "127.0.0.1"
                    tp = br.localPort
                } else if (pr.auth) {
                    tu = pr.user
                    tpw = pr.pass
                }

                val b = Builder()
                    .setSession("Supproxy")
                    .setMtu(1500)
                    .addAddress("198.18.0.1", 32)
                    .addAddress("fc00::1", 128)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("198.18.0.2")
                    .addDisallowedApplication(packageName) // prevents routing loop
                if (!Store.wholePhone(this)) {
                    for (pkg in Store.excluded(this)) {
                        try { b.addDisallowedApplication(pkg) } catch (_: Throwable) {}
                    }
                }
                val pfd = b.establish() ?: throw IOException("VPN permission revoked")
                if (gen != generation) {
                    try { pfd.close() } catch (_: Throwable) {}
                    return@Thread
                }
                tun = pfd

                val f = File(filesDir, "tproxy.yaml")
                f.writeText(config(th, tp, tu, tpw, !http))
                cfg = f
                hevStarted = TProxyService.TProxyStartService(f.absolutePath, pfd.fd)
                if (!hevStarted || !TProxyService.TProxyIsRunning()) {
                    throw IOException("Could not start the proxy tunnel")
                }

                setStatus("Connected")
                showForeground("Connected to ${pr.host}:${pr.port}")
                startWatchdog(upIp, pr.port, gen)
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
                stopVpn()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    private fun config(host: String, port: Int, user: String, pass: String, udp: Boolean) = buildString {
        append("tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n")
        append("socks5:\n  port: $port\n  address: ${q(host)}\n  udp: '${if (udp) "udp" else "tcp"}'\n")
        if (user.isNotEmpty()) append("  username: ${q(user)}\n  password: ${q(pass)}\n")
        // DNS is answered locally and resolved by the proxy, so lookups never leak
        append("mapdns:\n  address: 198.18.0.2\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 10000\n")
        append("misc:\n  log-level: warn\n")
    }

    private fun startWatchdog(host: String, port: Int, gen: Int) {
        if (!Store.autoReconnect(this)) return
        watching = true
        Thread {
            var fails = 0
            while (watching && gen == generation) {
                try { Thread.sleep(8000) } catch (_: InterruptedException) { return@Thread }
                if (!watching || gen != generation) return@Thread
                val ok = try {
                    Socket().use { it.connect(InetSocketAddress(host, port), 4000) }
                    true
                } catch (_: Throwable) { false }
                if (!ok) {
                    fails++
                    if (fails == 2) setStatus("Reconnecting")
                } else {
                    if (fails >= 2) {
                        if (!restartTunnel(gen)) return@Thread
                        setStatus("Connected")
                    }
                    fails = 0
                }
            }
        }.start()
    }

    private fun restartTunnel(gen: Int): Boolean {
        try {
            TProxyService.TProxyStopService()
            Thread.sleep(400)
            if (gen != generation) return false
            val t = tun ?: return false
            val f = cfg ?: return false
            if (!TProxyService.TProxyStartService(f.absolutePath, t.fd)) {
                throw IOException("Could not restart the proxy tunnel")
            }
            return true
        } catch (e: Exception) {
            if (gen == generation) {
                error = e.message ?: "Could not restart the proxy tunnel"
                stopVpn()
            }
            return false
        }
    }

    private fun setStatus(s: String) {
        status = s
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun showForeground(text: String = "Starting tunnel") {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Supproxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun stopVpn() {
        generation++
        watching = false
        if (hevStarted) {
            try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
            hevStarted = false
        }
        try { bridge?.stop() } catch (_: Throwable) {}
        bridge = null
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        setStatus("Disconnected")
    }

    override fun onRevoke() {
        error = null
        stopVpn()
    }

    override fun onDestroy() {
        if (tun != null) stopVpn()
        super.onDestroy()
    }
}
