package io.github.justlikecheese.xiaoyuan.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.APP_AUTHOR
import io.github.justlikecheese.xiaoyuan.APP_LICENSE
import io.github.justlikecheese.xiaoyuan.APP_NAME
import io.github.justlikecheese.xiaoyuan.BuildConfig
import io.github.justlikecheese.xiaoyuan.APP_URL
import io.github.justlikecheese.xiaoyuan.portal.SELF_SERVICE_URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Share
import androidx.core.content.FileProvider
import androidx.compose.material.icons.outlined.Notifications
import io.github.justlikecheese.xiaoyuan.core.Guard

private val THEMES = listOf("浅色" to "Light", "深色" to "Dark", "跟随系统" to "Auto")

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
fun SettingsPage(scroll: ScrollState) {
    val b = LocalBackend.current
    val context = LocalContext.current
    val s by b.settingsState.collectAsStateWithLifecycle()
    val logs by b.logInfo.collectAsStateWithLifecycle()
    var editHost by rememberSaveable { mutableStateOf(false) }
    var editLook by rememberSaveable { mutableStateOf(false) }
    var showPerms by rememberSaveable { mutableStateOf(false) }
    val perms = rememberPermissions()
    LaunchedEffect(Unit) { b.refreshLogInfo() }   // 切到设置页时更新日志占用

    // 分享日志：导出到 cache/share/ 再交给系统的分享面板（发微信、存文件都在那里选）
    val shareLog = {
        b.prepareLogShare { f ->
            if (f != null) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", f)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, "分享日志")) }
            }
        }
    }

    Page("设置", scroll, spacing = 8.dp) {
        PrefGroup("登录") {
            PrefRow(Icons.Outlined.PlayArrow, "启动时自动登录", "没在线时用最近使用的账号登录") {
                Switch(checked = s.autoLogin, onCheckedChange = { b.setSetting("autoLogin", it) })
            }
            PrefDivider()
            PrefRow(Icons.Outlined.PowerSettingsNew, "自动登录后关闭界面", "打开应用、登录完自动退出") {
                Switch(checked = s.closeAfterAutoLogin, onCheckedChange = { b.setSetting("closeAfterAutoLogin", it) })
            }
        }

        PrefGroup("门户") {
            PrefRow(Icons.Outlined.Language, "门户地址", s.portalHost, onClick = { editHost = true })
            PrefDivider()
            PrefSelect(Icons.Outlined.Smartphone, "默认终端类型", b.terminalTypes,
                b.terminalTypes.firstOrNull { it.value == s.terminalType } ?: b.terminalTypes[0],
                { it.name }, { b.setSetting("terminalType", it.value) },
                itemIcon = { Icon(terminalIcon(it.value), contentDescription = null) })
            PrefDivider()
            PrefRow(Icons.AutoMirrored.Outlined.OpenInNew, "自助服务平台", "修改密码、查看账单和在线设备",
                onClick = { openUrl(context, SELF_SERVICE_URL) })
        }

        PrefGroup("其他") {
            val g by b.guard.collectAsStateWithLifecycle()
            val notifyPerm = perms.first()   // 通知权限
            PrefRow(
                Icons.Outlined.Notifications, "自动重连通知",
                when {
                    g.mode == Guard.SERVICE -> "前台服务方式一直显示；用 Root / ADB 守护时看这个开关"
                    s.guardNotify && notifyPerm.granted == false -> "通知被关闭了，点这里打开"
                    else -> "用 Root / ADB 守护时也在通知栏显示在线状态"
                },
                onClick = if (s.guardNotify && notifyPerm.granted == false) ({ notifyPerm.toggle(true) }) else null,
            ) {
                Switch(checked = s.guardNotify, onCheckedChange = { on ->
                    b.setSetting("guardNotify", on)
                    if (on && notifyPerm.granted == false) notifyPerm.toggle(true)   // 没给通知权限：去授权
                })
            }
            PrefDivider()
            val denied = perms.count { it.granted == false }
            PrefRow(Icons.Outlined.Shield, "权限管理", if (denied == 0) "已全部授权，可在这里撤销" else "$denied 项未授权",
                onClick = { showPerms = true }) {
                if (perms.any { it.essential && it.granted == false }) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = "有权限未授权", tint = LocalStatusColors.current.warning)
                }
            }
            PrefDivider()
            val themeName = THEMES.firstOrNull { it.second == s.theme }?.first ?: THEMES[2].first
            val colorName = THEME_COLORS.firstOrNull { it.first == s.color }?.second ?: THEME_COLORS[0].second
            PrefRow(Icons.Outlined.Palette, "外观", "$themeName · $colorName", onClick = { editLook = true }) {
                Swatch(s.color)
            }
            PrefDivider()
            PrefRow(
                Icons.Outlined.Share, "分享日志",
                "${logs.files} 个文件 · ${logs.sizeText} · 保留 ${logs.keepDays} 天",
                onClick = shareLog,
            )
            PrefDivider()
            PrefRow(Icons.Outlined.Info, "$APP_NAME v${BuildConfig.VERSION_NAME}", "$APP_AUTHOR · $APP_LICENSE",
                onClick = { openUrl(context, APP_URL) }) {
                IconButton(onClick = { b.copy(APP_URL, "项目地址") }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制项目地址")
                }
            }
        }
    }

    if (editHost) HostDialog(s.portalHost, b.defaultHost, { b.setSetting("portalHost", it) }) { editHost = false }
    if (showPerms) PermissionsDialog { showPerms = false }
    if (editLook) LookDialog(s.theme, s.color, { b.setSetting("theme", it) }, { b.setSetting("color", it) }) { editLook = false }
}

/** 外观：深浅色和主题颜色，选了立即生效。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookDialog(theme: String, color: String, onTheme: (String) -> Unit, onColor: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外观") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("深浅色", fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    THEMES.forEachIndexed { i, (name, value) ->
                        SegmentedButton(
                            selected = theme == value,
                            onClick = { onTheme(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, THEMES.size),
                        ) { Text(name) }
                    }
                }
                Text("主题颜色", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((value, name) in THEME_COLORS) {
                        val selected = value == color
                        Column(
                            Modifier.width(64.dp).clickable { onColor(value) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Swatch(value, 44.dp)
                                if (selected) {
                                    Icon(Icons.Outlined.Check, contentDescription = "已选",
                                        tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                            }
                            Text(name, fontSize = 12.sp, maxLines = 1,
                                fontWeight = if (selected) FontWeight.SemiBold else null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 色块：预设色画实心圆，壁纸取色画当前配色的主色。 */
@Composable
private fun Swatch(value: String, size: Dp = 22.dp) {
    val color = if (value == "dynamic") MaterialTheme.colorScheme.primary else seedOf(value)
    Box(Modifier.size(size).background(color, CircleShape))
}

/** 门户地址，一般不需要改；留空恢复默认。 */
@Composable
private fun HostDialog(value: String, default: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("门户地址") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("默认 $default，一般不需要修改；留空恢复默认", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
