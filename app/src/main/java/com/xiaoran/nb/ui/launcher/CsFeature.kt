package com.xiaoran.nb.ui.launcher

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoran.nb.ui.model.BackendCs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 主页「小染客服」卡片：点开一个后端在线客服（可人工接管），离线/未接通时给出提示。 */
@Composable
fun CsServiceCard() {
    var open by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(
                Icons.Filled.Person,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("小染客服", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (BackendCs.enabled()) "在线人工 + 自助问答" else "后端未配置",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { open = true }) { Text("进入") }
        }
    }
    if (open) CsDialog(onDismiss = { open = false })
}

@Composable
private fun CsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cardKey = remember { context.getSharedPreferences("mikasa_prefs", Context.MODE_PRIVATE).getString("card_key", "") ?: "" }
    val device = remember { "${Build.MANUFACTURER} ${Build.MODEL}" }
    val session = remember { "cs_${System.currentTimeMillis()}" }
    val scope = rememberCoroutineScope()
    var msgs by remember {
        mutableStateOf<List<Pair<String, String>>>(listOf("assistant" to "你好，我是小染客服，有什么可以帮你？"))
    }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val listState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小染客服") },
        text = {
            Column(modifier = Modifier.verticalScroll(listState)) {
                msgs.forEach { (role, text) ->
                    Text(
                        (if (role == "user") "我：" else "客服：") + text,
                        fontSize = 13.sp,
                        color = if (role == "user") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (loading) {
                    Text("正在联系客服…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Row {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(
                    enabled = !loading,
                    onClick = {
                        val q = input.trim()
                        if (q.isEmpty()) return@TextButton
                        input = ""
                        msgs = msgs + ("user" to q)
                        loading = true
                        scope.launch {
                            val reply = withContext(Dispatchers.IO) { BackendCs.send(cardKey, device, q, "", session) }
                            loading = false
                            msgs = msgs + ("assistant" to (reply ?: "（后端未接通，请稍后再试）"))
                        }
                    }
                ) {
                    Text("发送")
                }
            }
        }
    )
}
