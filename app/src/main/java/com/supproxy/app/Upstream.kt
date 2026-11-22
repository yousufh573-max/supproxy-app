package com.supproxy.app

import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Opens a tunnelled TCP connection to dest:port through an upstream SOCKS5 or HTTP proxy. */
object Upstream {
    internal const val EXIT_LOOKUP_HOST = "ipwho.is"

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
        if (p.protocol != "SOCKS5") httpConnect(p.host, p.port, if (p.auth) p.user else null, p.pass, dest, port,
            p.host.takeIf { p.protocol == "HTTPS" })
        else socks5Connect(p.host, p.port, if (p.auth) p.user else null, p.pass, dest, port)

    fun httpConnect(ph: String, pp: Int, user: String?, pass: String?, dest: String, dport: Int, tlsHost: String? = null): Socket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(ph, pp), 10000)
            val s = if (tlsHost == null) raw else verifiedTls(raw, tlsHost, pp)
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
            if (code != "200") throw IOException("Proxy refused the connection")
            s.soTimeout = 0
            return s
        } catch (e: Throwable) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
    }

    fun verifiedTls(raw: Socket, host: String, port: Int): SSLSocket {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, host, port, true) as SSLSocket
        try {
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.enabledProtocols = ssl.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            ssl.soTimeout = 10000
            ssl.startHandshake()
            return ssl
        } catch (e: Exception) {
            ssl.close()
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
                    if (u.size !in 1..255 || pw.size !in 1..255) throw IOException("SOCKS5 login must use 1–255 bytes per field")
                    o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw)
                    o.flush()
                    val auth = readN(i, 2)
                    if (auth[0] != 1.toByte() || auth[1] != 0.toByte()) throw IOException("Proxy authentication failed")
                }
                else -> throw IOException("No acceptable SOCKS5 login method")
            }
            val d = dest.toByteArray()
            if (d.size !in 1..255) throw IOException("Invalid destination address")
            o.write(byteArrayOf(5, 1, 0, 3, d.size.toByte()) + d + byteArrayOf((dport shr 8).toByte(), dport.toByte()))
            o.flush()
            val h = readN(i, 4)
            if (h[0] != 5.toByte()) throw IOException("Invalid SOCKS5 response")
            if (h[1].toInt() != 0) throw IOException("SOCKS5 connect failed (code ${h[1].toInt()})")
            when (h[3].toInt()) {
                1 -> readN(i, 6)
                4 -> readN(i, 18)
                3 -> readN(i, (readN(i, 1)[0].toInt() and 0xff) + 2)
                else -> throw IOException("Invalid SOCKS5 address type")
            }
            s.soTimeout = 0
            return s
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            throw e
        }
    }
}
