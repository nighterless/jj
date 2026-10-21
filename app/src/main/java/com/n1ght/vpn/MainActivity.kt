package com.n1ght.vpn

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startSvc()
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        val store = Store(this)
        setContent {
            N1ghtTheme {
                Screen(
                    initial = store.load(),
                    onConnect = { cfg ->
                        store.save(cfg)
                        val i = VpnService.prepare(this)
                        if (i != null) vpnPerm.launch(i) else startSvc()
                    },
                    onDisconnect = {
                        startService(Intent(this, N1ghtVpnService::class.java).setAction(N1ghtVpnService.ACTION_STOP))
                    },
                )
            }
        }
    }

    private fun startSvc() {
        VpnState.status.value = Status.CONNECTING
        startForegroundService(Intent(this, N1ghtVpnService::class.java))
    }
}
