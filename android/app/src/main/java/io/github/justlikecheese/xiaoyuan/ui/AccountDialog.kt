package io.github.justlikecheese.xiaoyuan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.core.AccountView

/**
 * 添加 / 编辑账号。acc 为 null 时添加。右上角的眼睛统一控制账号、密码是否显示，默认跟随页面右上角的眼睛。
 * 左下角“测试登录”用填好的内容真的登录一次（不保存），确认配置可用再保存，不用反复建账号试。
 */
@Composable
fun AccountDialog(acc: AccountView?, onDismiss: () -> Unit, onDelete: (String) -> Unit) {
    val b = LocalBackend.current
    val busy by b.busy.collectAsStateWithLifecycle()
    val throttle by b.throttle.collectAsStateWithLifecycle()
    var account by rememberSaveable { mutableStateOf(acc?.account ?: "") }
    var password by rememberSaveable { mutableStateOf(acc?.password ?: "") }
    var isp by rememberSaveable { mutableStateOf(acc?.isp?.takeIf { s -> b.carriers.any { it.second == s } } ?: "") }
    var terminal by rememberSaveable { mutableIntStateOf(acc?.terminalType ?: -1) }
    var note by rememberSaveable { mutableStateOf(acc?.note ?: "") }
    var shown by rememberSaveable { mutableStateOf(b.revealed.value) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val tones = LocalStatusColors.current
    val options = b.terminalOptions

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (acc != null) "编辑账号" else "添加账号", modifier = Modifier.weight(1f))
                IconButton(onClick = { shown = !shown }) {
                    Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (shown) "隐藏账号和密码" else "显示账号和密码")
                }
                if (acc != null) {
                    IconButton(onClick = { onDelete(acc.account) }) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretField(account, { account = it }, "", shown, null, title = "账号", leadingIcon = Icons.Outlined.Person)
                SecretField(password, { password = it }, "初始密码为身份证后六位", shown, null, password = true,
                    title = "密码", leadingIcon = Icons.Outlined.Password)
                Dropdown(b.carriers, b.carriers.firstOrNull { it.second == isp }, { it.first }, { isp = it.second },
                    Modifier.fillMaxWidth(), title = "运营商", leadingIcon = Icons.Outlined.SimCard,
                    itemIcon = { CarrierIcon(it.second) })
                Dropdown(options, options.firstOrNull { it.value == terminal } ?: options[0], { it.name },
                    { terminal = it.value }, Modifier.fillMaxWidth(), title = "终端类型",
                    itemIcon = { Icon(if (it.value >= 0) terminalIcon(it.value) else Icons.Outlined.Devices, contentDescription = null) })
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("备注") }, leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                    placeholder = { Text("可选，例如：室友") })
                // 测试登录的进度和结果
                val status = when {
                    testing && throttle > 0 -> Triple("门户限制请求频率，$throttle 秒后发送…", null, true)
                    testing -> Triple("正在登录…", null, true)
                    else -> result?.let { Triple(it.second, it.first, false) }
                }
                status?.let { (text, ok, running) ->
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            ok == true -> Icon(Icons.Outlined.CheckCircle, null, tint = tones.success, modifier = Modifier.size(18.dp))
                            else -> Icon(Icons.Outlined.ErrorOutline, null, tint = tones.danger, modifier = Modifier.size(18.dp))
                        }
                        Text(text, fontSize = 13.sp, color = when (ok) {
                            true -> tones.success
                            false -> tones.danger
                            null -> tones.textTertiary
                        })
                    }
                }
            }
        },
        // 按钮一行：左下角测试登录，右侧取消、保存
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !testing && !busy,
                    onClick = {
                        testing = true
                        result = null
                        b.testLogin(account, password, isp, terminal) { ok, msg ->
                            testing = false
                            result = ok to msg
                        }
                    },
                ) { Text("测试登录") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    enabled = account.isNotBlank(),
                    onClick = {
                        b.saveAccount(acc?.account ?: "", account, isp, note, password, terminal)
                        onDismiss()
                    },
                ) { Text("保存") }
            }
        },
    )
}
