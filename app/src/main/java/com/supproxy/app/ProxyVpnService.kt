package com.supproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.github.shadowsocks.bg.Tun2proxy
import hev.htproxy.TProxyService
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

class ProxyVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.supproxy.app.START"
        const val ACTION_STOP = "com.supproxy.app.STOP"
        const val ACTION_RESTART = "com.supproxy.app.RESTART"
        const val ACTION_STATE = "com.supproxy.app.STATE"
        private const val CHANNEL = "vpn"
        private const val MTU = 1500
        private const val MAPPED_DNS = "198.18.0.2"
        private const val TCP_DNS = "1.1.1.1"
        private const val CHECK_INTERVAL_MS = 8_000L
        private const val FIRST_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L

        @Volatile var status = "Disconnected"
        @Volatile var error: String? = null
        @Volatile var usingDnsOverTcp = false
        @Volatile var systemAlwaysOn: Boolean? = null
        @Volatile var systemLockdown: Boolean? = null
        @Volatile var sessionProfile: Profile? = null
            private set
        @Volatile var routedPackages: Set<String>? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    private var destroyed = false
    private var restartRequested = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                restartRequested = false
                error = null
                requestStop()
            }
            ACTION_RESTART -> {
                if (worker == null) launch() else {
                    restartRequested = true
                    requestStop()
                }
            }
            ACTION_START, SERVICE_INTERFACE -> if (worker == null) launch()
            null -> if (intent != null && worker == null &&
                (Build.VERSION.SDK_INT < 29 || isAlwaysOn)) launch()
        }
        return START_NOT_STICKY
    }

    private fun launch() {
        try {
            showForeground("Starting tunnel")
        } catch (e: RuntimeException) {
            finish("Could not start the VPN service: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        if (Build.VERSION.SDK_INT >= 29) {
            systemAlwaysOn = isAlwaysOn
            systemLockdown = isLockdownEnabled
        }
        error = null
        stopRequested = false
        setStatus("Connecting")
        worker = Thread({
            var failure: String? = null
            try {
                runVpn()
            } catch (e: InterruptedException) {
                if (!stopRequested) failure = "VPN startup interrupted"
            } catch (e: Exception) {
                if (!stopRequested) failure = if (e is IOException) e.message else "Could not start or maintain the VPN"
            } catch (_: LinkageError) {
                if (!stopRequested) failure = "Could not load the native tunnel engine"
            } finally {
                main.post { if (!destroyed) finish(if (stopRequested) null else failure) }
            }
        }, "Supproxy VPN").also { it.start() }
    }

    private fun runVpn() {
        java.io.File(filesDir, "tproxy.yaml").delete()
        val profile = Store.active(this)
        sessionProfile = profile
        if (Store.storageUnavailable) throw IOException("Encrypted profile storage is unavailable")
        if (profile.host.isBlank() || profile.port !in 1..65535) throw IOException("Enter a valid proxy server and port")
        if (profile.auth && profile.protocol == "SOCKS5" &&
            (profile.user.toByteArray().size !in 1..255 || profile.pass.toByteArray().size !in 1..255)) {
            throw IOException("SOCKS5 login must use 1–255 bytes per field")
        }
        val dnsOverTcp = Store.dnsOverTcp(this)
        val builder = vpnBuilder(dnsOverTcp)
        var address = waitForProxy(profile)
        if (stopRequested) return

        val descriptor = builder.establish() ?: throw IOException("VPN permission revoked")
        usingDnsOverTcp = dnsOverTcp
        descriptor.use { tun ->
            while (!stopRequested) {
                val bridge = if (profile.protocol != "SOCKS5") {
                    HttpBridge(address, profile.port, profile.user.takeIf { profile.auth }, profile.pass,
                        profile.host.takeIf { profile.protocol == "HTTPS" })
                } else null
                try {
                    bridge?.start()
                    val host = if (bridge == null) address else "127.0.0.1"
                    val port = bridge?.localPort ?: profile.port
                    val user = bridge?.localUser ?: profile.user.takeIf { profile.auth }.orEmpty()
                    val password = bridge?.localPassword ?: profile.pass.takeIf { profile.auth }.orEmpty()

                    if (dnsOverTcp) {
                        runTcpDnsTunnel(tun.fd, host, port, user, password, profile)
                    } else {
                        runMappedDnsTunnel(tun.fd, host, port, user, password, bridge == null, profile)
                    }
                } finally {
                    bridge?.stop()
                }
                if (stopRequested) return
                if (!Store.autoReconnect(this)) throw IOException("Proxy connection lost")
                address = waitForProxy(profile, reconnecting = true)
            }
        }
    }

    private fun vpnBuilder(dnsOverTcp: Boolean): Builder {
        val builder = Builder()
            .setSession("Supproxy")
            .setMtu(MTU)
            .addAddress("198.18.0.1", 32)
            .addAddress("fc00::1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(if (dnsOverTcp) TCP_DNS else MAPPED_DNS)

        if (Store.wholePhone(this)) {
            builder.addDisallowedApplication(packageName)
            routedPackages = null
        } else {
            val excluded = Store.excluded(this)
            val allowed = mutableSetOf<String>()
            for (app in Store.routablePackages(this)) {
                if (app in excluded) continue
                try {
                    builder.addAllowedApplication(app)
                    allowed.add(app)
                } catch (_: PackageManager.NameNotFoundException) {
                    // The package was removed after the app list was read.
                }
            }
            if (allowed.isEmpty()) throw IOException("Select at least one app before connecting")
            routedPackages = allowed.toSet()
        }
        return builder
    }

    private fun waitForProxy(profile: Profile, reconnecting: Boolean = false): String {
        var retryMs = FIRST_RETRY_MS
        var wait = reconnecting
        while (!stopRequested) {
            if (wait) {
                if (!Store.autoReconnect(this)) throw IOException("Proxy connection lost")
                setStatus("Reconnecting")
                showForeground("Retrying in ${retryMs / 1000} s")
                Thread.sleep(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
            try {
                return reachableAddress(profile)
            } catch (e: IOException) {
                if (!Store.autoReconnect(this)) throw IOException("Cannot reach the proxy server", e)
                wait = true
            }
        }
        throw InterruptedException()
    }

    private fun reachableAddress(profile: Profile): String {
        for (address in InetAddress.getAllByName(profile.host)) {
            if (stopRequested) throw InterruptedException()
            val ip = address.hostAddress ?: continue
            try {
                Socket().use { it.connect(InetSocketAddress(address, profile.port), 4_000) }
                return ip
            } catch (_: IOException) {
                // Try the other address family if this endpoint is unavailable.
            }
        }
        throw IOException("Cannot reach the proxy server")
    }

    private fun monitor(profile: Profile, isRunning: () -> Boolean) {
        if (stopRequested) return
        val connected = "VPN connected"
        showForeground(connected)
        setStatus("Connected")
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        var nextProbe = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS
        while (!stopRequested && isRunning()) {
            if (connectivity.activeNetwork != network) return
            if (SystemClock.elapsedRealtime() >= nextProbe) {
                val reachable = try {
                    reachableAddress(profile)
                    true
                } catch (_: IOException) { false }
                if (!reachable) return
                nextProbe = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS
            }
            Thread.sleep(500)
        }
    }

    private fun runMappedDnsTunnel(
        fd: Int, host: String, port: Int, user: String, pass: String, udp: Boolean, profile: Profile
    ) {
        val config = buildString {
            append("tunnel:\n  mtu: $MTU\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n")
            append("socks5:\n  port: $port\n  address: ${yamlString(host)}\n  udp: '${if (udp) "udp" else "tcp"}'\n")
            if (user.isNotEmpty()) append("  username: ${yamlString(user)}\n  password: ${yamlString(pass)}\n")
            append("mapdns:\n  address: $MAPPED_DNS\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 10000\n")
            append("misc:\n  log-level: error\n  log-file: /dev/null\n")
        }.toByteArray()
        if (config.size > 4096) throw IOException("Proxy settings are too long")
        val (input, output) = ParcelFileDescriptor.createPipe()
        input.use {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(config) }
            } finally {
                config.fill(0)
            }
            if (!TProxyService.TProxyStartService("/proc/self/fd/${input.fd}", fd)) {
                throw IOException("Could not start the proxy tunnel")
            }
            try {
                if (!TProxyService.TProxyIsRunning()) throw IOException("Could not start the proxy tunnel")
                monitor(profile, TProxyService::TProxyIsRunning)
            } finally {
                TProxyService.TProxyStopService()
            }
        }
    }

    private fun runTcpDnsTunnel(fd: Int, host: String, port: Int, user: String, pass: String, profile: Profile) {
        val authority = if (":" in host) "[$host]" else host
        val credentials = if (user.isEmpty()) "" else "${encode(user)}:${encode(pass)}@"
        val args = "tun2proxy --tun-fd $fd --close-fd-on-drop false --proxy socks5://$credentials$authority:$port " +
            "--dns over-tcp --dns-addr $TCP_DNS --ipv6-enabled --verbosity off"

        var nativeFailure: LinkageError? = null
        val engine = Thread({
            try {
                Tun2proxy.run(args, MTU.toChar())
            } catch (e: LinkageError) {
                nativeFailure = e
            }
        }, "Supproxy DNS tunnel").apply { start() }
        try {
            engine.join(200)
            if (!engine.isAlive) {
                nativeFailure?.let { throw it }
                throw IOException("Could not start the DNS-over-TCP tunnel")
            }
            monitor(profile) { engine.isAlive }
        } finally {
            // Clear a cancellation interrupt while waiting for the native engine to release the TUN.
            Thread.interrupted()
            while (engine.isAlive) {
                try { Tun2proxy.stop() } catch (_: LinkageError) { break }
                engine.join(100)
            }
        }
    }

    private fun yamlString(value: String) = "'" + value.replace("'", "''") + "'"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun setStatus(value: String) {
        status = value
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun showForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Supproxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun requestStop() {
        val activeWorker = worker
        if (activeWorker == null) {
            finish(null)
            return
        }
        if (stopRequested) return
        stopRequested = true
        setStatus("Disconnecting")
        activeWorker.interrupt()
    }

    private fun finish(failure: String?) {
        worker = null
        if (restartRequested) {
            restartRequested = false
            launch()
            return
        }
        usingDnsOverTcp = false
        systemAlwaysOn = null
        systemLockdown = null
        sessionProfile = null
        routedPackages = null
        error = failure
        setStatus("Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        main.post {
            restartRequested = false
            requestStop()
        }
    }

    override fun onDestroy() {
        destroyed = true
        val wasStopping = stopRequested
        stopRequested = true
        if (!wasStopping) worker?.interrupt()
        if (worker != null) {
            sessionProfile = null
            routedPackages = null
            systemAlwaysOn = null
            systemLockdown = null
            usingDnsOverTcp = false
            setStatus("Disconnected")
        }
        super.onDestroy()
    }
}