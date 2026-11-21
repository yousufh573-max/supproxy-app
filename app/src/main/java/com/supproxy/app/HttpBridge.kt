package com.supproxy.app

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Local SOCKS5 server (no login, TCP CONNECT only) that forwards every connection
 * through an upstream HTTP proxy. Lets the SOCKS5 tunnel engine work with HTTP proxies.
 */
class HttpBridge(private val host: String, private val port: Int, private val user: String?, private val pass: String?) {
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
                    Thread { handle(c) }.start()
                } catch (_: Throwable) { break }
            }
        }.start()
    }

    fun stop() {
        alive = false
        try { server?.close() } catch (_: Throwable) {}
    }

    private fun handle(c: Socket) {
        var up: Socket? = null
        try {
            val i = c.getInputStream()
            val o = c.getOutputStream()
            val g = Upstream.readN(i, 2)
            Upstream.readN(i, g[1].toInt() and 0xff)
            o.write(byteArrayOf(5, 0))
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
                up = Upstream.httpConnect(host, port, user, pass, dest, dport)
            } catch (e: Throwable) {
                o.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            o.flush()
            val u: Socket = up ?: return
            Thread { pump(u, c) }.start()
            pump(c, u)
        } catch (_: Throwable) {
        } finally {
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
