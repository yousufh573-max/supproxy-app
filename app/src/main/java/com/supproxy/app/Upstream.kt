package com.supproxy.app

import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Opens a tunnelled TCP connection to dest:port through an upstream SOCKS5 or HTTP proxy. */
object Upstream {
    fun readN(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(b, off, n - off)
            if (r < 0) throw IOException("Connection closed")
            off += r
        }
        return b
    }

    fun open(p: Profile, dest: String, port: Int): Socket =
        if (p.protocol == "HTTP") httpConnect(p.host, p.port, if (p.auth) p.user else null, p.pass, dest, port)
        else socks5Connect(p.host, p.port, if (p.auth) p.user else null, p.pass, dest, port)

    fun httpConnect(ph: String, pp: Int, user: String?, pass: String?, dest: String, dport: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ph, pp), 10000)
            s.soTimeout = 15000
            val sb = StringBuilder("CONNECT $dest:$dport HTTP/1.1\r\nHost: $dest:$dport\r\n")
            if (!user.isNullOrEmpty()) {
                val cred = Base64.encodeToString("$user:${pass ?: ""}".toByteArray(), Base64.NO_WRAP)
                sb.append("Proxy-Authorization: Basic ").append(cred).append("\r\n")
            }
            sb.append("\r\n")
            val o = s.getOutputStream()
            o.write(sb.toString().toByteArray())
            o.flush()
            val i = s.getInputStream()
            val hdr = StringBuilder()
            while (!hdr.endsWith("\r\n\r\n")) {
                val b = i.read()
                if (b < 0) throw IOException("Proxy closed the connection")
                hdr.append(b.toChar())
                if (hdr.length > 8192) throw IOException("Bad proxy response")
            }
            val line = hdr.toString().substringBefore("\r\n")
            val code = line.split(" ").getOrNull(1)
            if (code == "407") throw IOException("Proxy authentication failed")
            if (code != "200") throw IOException("Proxy refused: $line")
            s.soTimeout = 0
            return s
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            throw e
        }
    }

    fun socks5Connect(ph: String, pp: Int, user: String?, pass: String?, dest: String, dport: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ph, pp), 10000)
            s.soTimeout = 15000
            val i = s.getInputStream()
            val o = s.getOutputStream()
            val useAuth = !user.isNullOrEmpty()
            o.write(if (useAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
            o.flush()
            val r = readN(i, 2)
            if (r[0].toInt() != 5) throw IOException("Not a SOCKS5 server")
            when (r[1].toInt() and 0xff) {
                0 -> {}
                2 -> {
                    val u = (user ?: "").toByteArray()
                    val pw = (pass ?: "").toByteArray()
                    o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw)
                    o.flush()
                    if (readN(i, 2)[1].toInt() != 0) throw IOException("Proxy authentication failed")
                }
                else -> throw IOException("No acceptable SOCKS5 login method")
            }
            val d = dest.toByteArray()
            o.write(byteArrayOf(5, 1, 0, 3, d.size.toByte()) + d + byteArrayOf((dport shr 8).toByte(), dport.toByte()))
            o.flush()
            val h = readN(i, 4)
            if (h[1].toInt() != 0) throw IOException("SOCKS5 connect failed (code ${h[1].toInt()})")
            when (h[3].toInt()) {
                1 -> readN(i, 6)
                4 -> readN(i, 18)
                3 -> readN(i, (readN(i, 1)[0].toInt() and 0xff) + 2)
            }
            s.soTimeout = 0
            return s
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            throw e
        }
    }
}
