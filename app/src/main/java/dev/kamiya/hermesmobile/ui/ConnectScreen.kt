package dev.kamiya.hermesmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kamiya.hermesmobile.ui.theme.HermesColors

@Composable
fun ConnectScreen(state: AuthState.NeedsLogin, onLogin: (String) -> Unit) {
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    Box(Modifier.fillMaxSize().background(HermesColors.Ground).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(
                Modifier.size(48.dp).background(HermesColors.Accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Chat, null, tint = HermesColors.OnAccent) }
            Text("Hermes に接続", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "Mac の Hermes に Tailscale 経由でつなぎます。Desktop で開いているセッションを、そのまま続きから見たり指示したりできます。",
                color = HermesColors.Muted, fontSize = 15.sp, lineHeight = 25.sp,
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("サーバー URL") },
                placeholder = { Text("https://[Mac のホスト名].ts.net") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
                    autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onGo = { onLogin(url) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onLogin(url) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HermesColors.Accent, contentColor = HermesColors.OnAccent),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = HermesColors.OnAccent, strokeWidth = 2.dp)
                else Text("Tailscale でログイン", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            state.error?.let { Text(it, color = HermesColors.Danger, fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            Text(
                "この端末の Tailscale アカウントで本人確認します。パスワードは要りません。ログインは 30 日ごとに自動で更新されます。",
                color = HermesColors.Muted, fontSize = 12.sp, lineHeight = 19.sp,
            )
        }
    }
}
