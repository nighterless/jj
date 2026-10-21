package com.n1ght.vpn

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object Const {
    /** true = every packet that doesn't go through the local proxy is dropped (no leaks, apps that ignore system proxy get nothing) */
    const val BLOCK_NON_PROXY = true
    const val LOCAL_PORT = 10809
    const val CONNECT_TIMEOUT_MS = 15_000
    const val KEEPALIVE_MS = 15_000
    const val DNS = "1.1.1.1"
}

data class TunnelConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val pass: String = "",
    val payload: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val sni: String = "",
)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("n1ght", Context.MODE_PRIVATE)

    fun load() = TunnelConfig(
        host = sp.getString("host", "") ?: "",
        port = sp.getInt("port", 22),
        user = sp.getString("user", "") ?: "",
        pass = sp.getString("pass", "") ?: "",
        payload = sp.getString("payload", "") ?: "",
        proxyHost = sp.getString("proxyHost", "") ?: "",
        proxyPort = sp.getInt("proxyPort", 8080),
        sni = sp.getString("sni", "") ?: "",
    )

    fun save(c: TunnelConfig) {
        sp.edit()
            .putString("host", c.host).putInt("port", c.port)
            .putString("user", c.user).putString("pass", c.pass)
            .putString("payload", c.payload)
            .putString("proxyHost", c.proxyHost).putInt("proxyPort", c.proxyPort)
            .putString("sni", c.sni)
            .apply()
    }
}

enum class Status { IDLE, CONNECTING, CONNECTED, RETRY, FAILED }

object VpnState {
    val status = MutableStateFlow(Status.IDLE)
    val lines = MutableStateFlow<List<String>>(emptyList())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        lines.update { (it + line).takeLast(200) }
    }
}
