package com.n1ght.vpn

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

/** loopback http proxy (CONNECT + plain http). every upstream connection is an ssh channel, dns resolves on the server side. */
class LocalProxy(
    private val port: Int,
    private val dial: (String, Int) -> Conn,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 64)
        server = s
        VpnState.log("proxy 127.0.0.1:$port")
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(c: Socket) {
        c.use {
            val cin = BufferedInputStream(c.getInputStream(), 16_384)
            val cout = c.getOutputStream()
            val reqLine = readLine(cin) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 3) return
            val headers = ArrayList<String>()
            while (true) {
                val l = readLine(cin) ?: return
                if (l.isEmpty()) break
                headers += l
            }

            if (parts[0].equals("CONNECT", ignoreCase = true)) {
                val (h, p) = hostPort(parts[1], 443)
                val conn = try {
                    dial(h, p)
                } catch (e: Exception) {
                    cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                    return
                }
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                cout.flush()
                pipe(c, cin, cout, conn)
            } else {
                val uri = try { URI(parts[1]) } catch (_: Exception) { null }
                val h = uri?.host
                if (uri == null || h == null) {
                    cout.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                    return
                }
                val p = if (uri.port > 0) uri.port else 80
                val path = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") +
                    (uri.rawQuery?.let { "?$it" } ?: "")
                val conn = try {
                    dial(h, p)
                } catch (e: Exception) {
                    cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                    return
                }
                val sb = StringBuilder("${parts[0]} $path ${parts[2]}\r\n")
                for (line in headers) {
                    val k = line.substringBefore(':').trim().lowercase()
                    if (k == "proxy-connection" || k == "proxy-authorization" || k == "connection") continue
                    sb.append(line).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                conn.output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                pipe(c, cin, cout, conn)
            }
        }
    }

    private fun pipe(c: Socket, cin: InputStream, cout: OutputStream, conn: Conn) {
        val up = pool.submit {
            try { cin.copyTo(conn.output) } catch (_: Exception) {}
            finally { closeBoth(c, conn) }
        }
        try {
            val buf = ByteArray(16_384)
            while (true) {
                val n = conn.input.read(buf)
                if (n < 0) break
                cout.write(buf, 0, n)
                cout.flush()
            }
        } catch (_: Exception) {
        } finally {
            closeBoth(c, conn)
            up.cancel(true)
        }
    }

    private fun closeBoth(c: Socket, conn: Conn) {
        try { conn.ch.disconnect() } catch (_: Exception) {}
        try { c.close() } catch (_: Exception) {}
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16_384) return null
        }
        return sb.toString()
    }

    private fun hostPort(s: String, def: Int): Pair<String, Int> {
        val t = s.trim()
        if (t.startsWith("[")) {
            val e = t.indexOf(']')
            val h = t.substring(1, e)
            val p = t.substring(e + 1).removePrefix(":").toIntOrNull() ?: def
            return h to p
        }
        val c = t.lastIndexOf(':')
        return if (c > 0) t.substring(0, c) to (t.substring(c + 1).toIntOrNull() ?: def) else t to def
    }
}
