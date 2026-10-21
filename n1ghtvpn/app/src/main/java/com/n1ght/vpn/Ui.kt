package com.n1ght.vpn

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Dim = Color(0xFF8A8A93)
private val Line = Color(0xFF2F2F35)

private val Scheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0A0A0C),
    onSurface = Color.White,
    outline = Line,
    secondary = Dim,
)

@Composable
fun N1ghtTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.White,
    unfocusedBorderColor = Line,
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Dim,
    cursorColor = Color.White,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
)

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    secret: Boolean = false,
    lines: Int = 1,
    hint: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = lines == 1,
        minLines = lines,
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = hint?.let { { Text(it, color = Dim, fontSize = 11.sp) } },
    )
}

private fun label(s: Status) = when (s) {
    Status.IDLE -> "disconnected"
    Status.CONNECTING -> "connecting..."
    Status.CONNECTED -> "connected"
    Status.RETRY -> "reconnecting..."
    Status.FAILED -> "failed"
}

@Composable
fun Screen(initial: TunnelConfig, onConnect: (TunnelConfig) -> Unit, onDisconnect: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var user by rememberSaveable { mutableStateOf(initial.user) }
    var pass by rememberSaveable { mutableStateOf(initial.pass) }
    var payload by rememberSaveable { mutableStateOf(initial.payload) }
    var proxyHost by rememberSaveable { mutableStateOf(initial.proxyHost) }
    var proxyPort by rememberSaveable { mutableStateOf(initial.proxyPort.toString()) }
    var sni by rememberSaveable { mutableStateOf(initial.sni) }

    val status by VpnState.status.collectAsState()
    val log by VpnState.lines.collectAsState()
    val busy = status == Status.CONNECTING || status == Status.CONNECTED || status == Status.RETRY
    val logScroll = rememberScrollState()
    LaunchedEffect(log.size) { logScroll.animateScrollTo(logScroll.maxValue) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(104.dp))
        Text("n1ghtVPN", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text(
            label(status),
            color = if (status == Status.FAILED) Color(0xFFFF5555) else if (status == Status.CONNECTED) Color.White else Dim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(host, { host = it.trim() }, "ssh host", Modifier.weight(1f))
            Field(port, { port = it.filter(Char::isDigit).take(5) }, "port", Modifier.width(96.dp), number = true)
        }
        Field(user, { user = it.trim() }, "username")
        Field(pass, { pass = it }, "password", secret = true)
        Field(
            payload, { payload = it }, "payload", lines = 3,
            hint = "[crlf] [cr] [lf] [host] [port] [host_port] [split]",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(proxyHost, { proxyHost = it.trim() }, "proxy host", Modifier.weight(1f))
            Field(proxyPort, { proxyPort = it.filter(Char::isDigit).take(5) }, "port", Modifier.width(96.dp), number = true)
        }
        Field(sni, { sni = it.trim() }, "sni (tls host)", hint = "empty = no tls")

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                if (busy) {
                    onDisconnect()
                } else if (host.isBlank() || user.isBlank()) {
                    VpnState.log("host and username required")
                } else {
                    onConnect(
                        TunnelConfig(
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            user = user,
                            pass = pass,
                            payload = payload,
                            proxyHost = proxyHost,
                            proxyPort = proxyPort.toIntOrNull() ?: 8080,
                            sni = sni,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            Text(if (busy) "disconnect" else "connect", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 200.dp)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(10.dp)
                .verticalScroll(logScroll),
        ) {
            Text(
                if (log.isEmpty()) "log" else log.takeLast(60).joinToString("\n"),
                color = Dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
