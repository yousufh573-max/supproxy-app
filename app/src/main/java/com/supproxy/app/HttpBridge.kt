package com.supproxy.app

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local SOCKS5 server (session authentication, TCP CONNECT only) that forwards every connection
 * through an upstream HTTP proxy. Lets the SOCKS5 tunnel engine work with HTTP proxies.
 */
class HttpBridge(
    private val host: String,
    private val port: Int,
    private val user: String?,
    private val pass: String,
    private val tlsHost: String?
) {
    val localUser = "supproxy"
    val localPassword = UUID.randomUUID().toString()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var server: ServerSocket? = null
    @Volatile private var alive = false
    val localPort: Int get() = server?.localPort ?: 0

    fun start() {
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        alive = true
        Thread {
            while (alive) {
                try {
                    val c = s.accept()
                    clients.add(c)
                    if (!alive) {
                        c.close()
                        clients.remove(c)
                        break
                    }
                    Thread { handle(c) }.start()
                } catch (_: Throwable) { break }
            }
        }.start()
    }

    fun stop() {
        alive = false
        try { server?.close() } catch (_: IOException) {}
        for (client in clients) try { client.close() } catch (_: IOException) {}
    }

    private fun handle(c: Socket) {
        var up: Socket? = null
        try {
            val i = c.getInputStream()
            val o = c.getOutputStream()
            c.soTimeout = 10_000
            val greeting = Upstream.readN(i, 2)
            val methods = Upstream.readN(i, greeting[1].toInt() and 0xff)
            if (greeting[0] != 5.toByte() || 2.toByte() !in methods) {
                o.write(byteArrayOf(5, -1))
                return
            }
            o.write(byteArrayOf(5, 2))
            val auth = Upstream.readN(i, 2)
            val username = Upstream.readN(i, auth[1].toInt() and 0xff)
            val passwordSize = Upstream.readN(i, 1)[0].toInt() and 0xff
            val password = Upstream.readN(i, passwordSize)
            val authenticated = auth[0] == 1.toByte() &&
                MessageDigest.isEqual(username, localUser.toByteArray()) &&
                MessageDigest.isEqual(password, localPassword.toByteArray())
            o.write(byteArrayOf(1, if (authenticated) 0 else 1))
            if (!authenticated) return
            val h = Upstream.readN(i, 4)
            val dest = when (h[3].toInt()) {
                1 -> InetAddress.getByAddress(Upstream.readN(i, 4)).hostAddress ?: ""
                4 -> "[" + (InetAddress.getByAddress(Upstream.readN(i, 16)).hostAddress ?: "") + "]"
                3 -> String(Upstream.readN(i, Upstream.readN(i, 1)[0].toInt() and 0xff))
                else -> throw IOException("Unsupported address type")
            }
            val pb = Upstream.readN(i, 2)
            val dport = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)
            if (h[1].toInt() != 1) {
                o.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            try {
                up = Upstream.httpConnect(host, port, user, pass, dest, dport, tlsHost)
            } catch (e: Throwable) {
                o.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            o.flush()
            c.soTimeout = 0
            val u: Socket = up
            Thread { pump(u, c) }.start()
            pump(c, u)
        } catch (_: Throwable) {
        } finally {
            clients.remove(c)
            try { c.close() } catch (_: Throwable) {}
            try { up?.close() } catch (_: Throwable) {}
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(16384)
            val i = from.getInputStream()
            val o = to.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (_: Throwable) {}
        try { from.close() } catch (_: Throwable) {}
        try { to.close() } catch (_: Throwable) {}
    }
}
