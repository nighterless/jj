package com.n1ght.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

class N1ghtVpnService : VpnService() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunnel: SshTunnel? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(Status.IDLE)
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        startForeground(1, notification())
        val cfg = Store(this).load()
        worker = Thread { loop(cfg) }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        shutdown(Status.IDLE)
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown(Status.IDLE)
        super.onDestroy()
    }

    private fun loop(cfg: TunnelConfig) {
        var backoff = 2_000L
        while (running) {
            VpnState.status.value = Status.CONNECTING
            val t = SshTunnel(cfg) { protect(it) }
            tunnel = t
            var fatal = false
            try {
                t.start()
                if (tun == null) tun = establish()
                VpnState.status.value = Status.CONNECTED
                backoff = 2_000L
                while (running && t.alive()) Thread.sleep(2_000)
                if (running) VpnState.log("tunnel dropped")
            } catch (e: Exception) {
                if (running) {
                    val msg = e.message ?: e.javaClass.simpleName
                    VpnState.log("err: $msg")
                    VpnState.status.value = Status.RETRY
                    if (msg.contains("Auth", ignoreCase = true)) fatal = true
                }
            } finally {
                t.stop()
            }
            if (fatal) {
                shutdown(Status.FAILED)
                return
            }
            if (!running) break
            VpnState.status.value = Status.RETRY
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = minOf(backoff * 2, 30_000L)
        }
    }

    private fun establish(): ParcelFileDescriptor {
        val b = Builder()
            .setSession("n1ghtVPN")
            .setMtu(1500)
            .setBlocking(true)
            .addAddress("10.8.0.2", 32)
            .addDnsServer(Const.DNS)
            .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", Const.LOCAL_PORT))
        b.addDisallowedApplication(packageName)
        if (Const.BLOCK_NON_PROXY) {
            b.addRoute("0.0.0.0", 0)
            b.addAddress("fd00:1::2", 128)
            b.addRoute("::", 0)
        }
        val fd = b.establish() ?: throw IllegalStateException("vpn permission revoked")
        // nothing reads the tun on purpose; drain it so the queue never backs up
        Thread {
            val fis = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32_767)
            try { while (fis.read(buf) >= 0) { /* drop */ } } catch (_: Exception) {}
        }.start()
        VpnState.log("vpn up (http proxy 127.0.0.1:${Const.LOCAL_PORT})")
        return fd
    }

    private fun shutdown(final: Status) {
        val was = running
        running = false
        if (was) worker?.interrupt()
        tunnel?.stop()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        VpnState.status.value = final
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("n1ght", "n1ghtVPN", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, N1ghtVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "n1ght")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("n1ghtVPN")
            .setContentText("tunnel active")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "disconnect", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.n1ght.vpn.STOP"
    }
}
