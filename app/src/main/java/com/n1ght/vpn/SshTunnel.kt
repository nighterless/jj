package com.n1ght.vpn

import android.annotation.SuppressLint
import com.jcraft.jsch.Channel
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class Conn(val ch: Channel, val input: InputStream, val output: OutputStream)

/**
 * ssh session whose transport socket is built by hand:
 * tcp -> (proxy | direct) -> optional tls with custom sni -> optional payload / CONNECT -> ssh
 * once up, a local http proxy hands every request to an ssh direct-tcpip channel.
 */
class SshTunnel(
    private val cfg: TunnelConfig,
    private val protect: (Socket) -> Boolean,
) {
    @Volatile private var session: Session? = null
    private var proxy: LocalProxy? = null

    fun alive() = session?.isConnected == true

    fun start() {
        val s = JSch().getSession(cfg.user, cfg.host, cfg.port)
        s.setPassword(cfg.pass)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.setServerAliveInterval(Const.KEEPALIVE_MS)
        s.setServerAliveCountMax(3)
        s.setSocketFactory(HopFactory())
        VpnState.log("ssh -> ${cfg.host}:${cfg.port}")
        s.connect(Const.CONNECT_TIMEOUT_MS * 2)
        session = s
        VpnState.log("ssh up")
        proxy = LocalProxy(Const.LOCAL_PORT) { h, p -> dial(h, p) }.also { it.start() }
    }

    fun stop() {
        proxy?.stop()
        proxy = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }

    private fun dial(host: String, port: Int): Conn {
        val s = session ?: throw IOException("no session")
        val ch = s.getStreamForwarder(host, port)
        val i = ch.inputStream
        val o = ch.outputStream
        ch.connect(Const.CONNECT_TIMEOUT_MS)
        return Conn(ch, i, o)
    }

    private inner class HopFactory : SocketFactory {
        private var inS: InputStream? = null
        private var outS: OutputStream? = null

        override fun createSocket(host: String, port: Int): Socket {
            val viaProxy = cfg.proxyHost.isNotBlank()
            val hop = if (viaProxy) cfg.proxyHost else cfg.host
            val hopPort = if (viaProxy) cfg.proxyPort else cfg.port

            val raw = Socket()
            protect(raw)
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(hop, hopPort), Const.CONNECT_TIMEOUT_MS)
            VpnState.log("tcp ok $hop:$hopPort")

            var sock: Socket = raw
            if (cfg.sni.isNotBlank()) {
                val ssl = trustAll.socketFactory.createSocket(raw, cfg.sni, hopPort, true) as SSLSocket
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(cfg.sni))
                ssl.sslParameters = params
                ssl.startHandshake()
                VpnState.log("tls ok sni=${cfg.sni}")
                sock = ssl
            }

            val pin = PushbackInputStream(sock.getInputStream(), 8)
            val out = sock.getOutputStream()

            val head: String? = when {
                cfg.payload.isNotBlank() -> render(cfg.payload, cfg.host, cfg.port)
                viaProxy -> "CONNECT ${cfg.host}:${cfg.port} HTTP/1.1\r\nHost: ${cfg.host}:${cfg.port}\r\n\r\n"
                else -> null
            }
            if (head != null) {
                sock.soTimeout = 10_000
                sendPayload(out, head)
                drainHead(pin)
                sock.soTimeout = 0
            }
            inS = pin
            outS = out
            return sock
        }

        override fun getInputStream(socket: Socket): InputStream = inS ?: socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = outS ?: socket.getOutputStream()
    }

    private fun render(p: String, h: String, prt: Int): String = p
        .replace("\r", "").replace("\n", "")
        .replace("[crlf]", "\r\n").replace("[cr]", "\r").replace("[lf]", "\n")
        .replace("[host_port]", "$h:$prt").replace("[host]", h).replace("[port]", prt.toString())

    private fun sendPayload(out: OutputStream, text: String) {
        text.split("[split]").forEachIndexed { idx, part ->
            if (idx > 0) Thread.sleep(300)
            VpnState.log("-> ${part.replace("\r\n", "⏎").take(120)}")
            out.write(part.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    /** eats http response heads (101 / 200 / chained) until the ssh banner shows up */
    private fun drainHead(inp: PushbackInputStream) {
        repeat(4) {
            val first = ByteArray(4)
            var n = 0
            while (n < 4) {
                val r = inp.read(first, n, 4 - n)
                if (r < 0) throw IOException("closed during payload")
                n += r
            }
            if (String(first, Charsets.ISO_8859_1) == "SSH-") {
                inp.unread(first)
                return
            }
            val sb = StringBuilder(String(first, Charsets.ISO_8859_1))
            while (!sb.endsWith("\r\n\r\n")) {
                val b = inp.read()
                if (b < 0) throw IOException("closed during payload")
                sb.append(b.toChar())
                if (sb.length > 16_384) throw IOException("response head too large")
            }
            val line = sb.lineSequence().first()
            VpnState.log("<- $line")
            val code = line.split(" ").getOrNull(1)?.toIntOrNull()
            if (line.startsWith("HTTP/") && code != null && code >= 300) throw IOException(line)
        }
    }

    companion object {
        // tls here is only a carrier for ssh (which does its own auth), cert checks would break sni fronting
        @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
        private val trustAll: SSLContext by lazy {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        }
    }
}
