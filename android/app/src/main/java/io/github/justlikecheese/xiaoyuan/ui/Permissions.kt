package io.github.justlikecheese.xiaoyuan.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AppSettingsAlt
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 应用用到的一项权限。granted 为 null 表示系统不让查（比如厂商的自启动开关），只能带用户去设置页。
 * toggle(true) 去授权，toggle(false) 去撤销：安卓不允许应用自己收回已授予的权限，撤销都是打开对应的系统设置页。
 */
class Perm(
    val icon: ImageVector,
    val title: String,
    val purpose: String,
    val granted: Boolean?,
    val essential: Boolean,   // 自动重连要靠它：没授权时自动重连页显示警告
    val toggle: (Boolean) -> Unit,
)

/** 回到界面时（从系统设置页回来）重新算一次；keys 变了也重算。 */
@Composable
fun <T> rememberOnResume(vararg keys: Any?, compute: () -> T): T {
    var value by remember(*keys) { mutableStateOf(compute()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) value = compute() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return value
}

private fun Context.open(intent: Intent): Boolean = runCatching {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

private fun Context.appDetails() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

/**
 * 厂商的“自启动”管理页（ColorOS / MIUI / EMUI / OriginOS 等），系统没有公开接口，按已知的页面逐个试；
 * 都打不开就去应用详情页。
 */
private fun Context.openAutoStart() {
    // 新版 ColorOS（OPPO、一加、realme）按 action 打开
    for (action in listOf(
        "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    )) {
        if (open(Intent(action))) return
    }
    val pages = listOf(
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.view.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
    )
    for ((pkg, cls) in pages) {
        if (open(Intent().setComponent(ComponentName(pkg, cls)))) return
    }
    appDetails()
}

/** 当前各项权限的状态，回到界面时刷新。 */
@Composable
fun rememberPermissions(): List<Perm> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }   // 运行时权限弹窗关掉后刷新
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) context.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        tick++
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) context.appDetails()   // 之前选过“不再询问”时系统不弹窗，只能去设置页
        tick++
    }
    val (notify, battery, camera) = rememberOnResume(tick) {
        Triple(
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifySettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    return listOf(
        Perm(Icons.Outlined.Notifications, "通知", "通知栏显示在线状态和登录、注销按钮", notify, true) { on ->
            if (on && Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.open(notifySettings)
        },
        Perm(Icons.Outlined.BatteryChargingFull, "后台运行", "不受省电限制，锁屏后也能自动重连", battery, true) { on ->
            if (on) {
                @Suppress("BatteryLife")
                context.open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            } else {
                context.open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        },
        Perm(Icons.Outlined.RestartAlt, "自启动", "开机、被清理后能自己启动；ColorOS 在「耗电管理」里", null, false) { context.openAutoStart() },
        Perm(Icons.Outlined.PhotoCamera, "相机", "扫描电脑端的二维码导入账号", camera, false) { on ->
            if (on) askCamera.launch(Manifest.permission.CAMERA) else context.appDetails()
        },
        Perm(Icons.Outlined.AppSettingsAlt, "全部权限", "打开系统的应用信息页", null, false) { context.appDetails() },
    )
}

/** 权限管理：每项一个开关，打开去授权，关掉去撤销；系统查不到状态的（自启动）点一下去设置页。 */
@Composable
fun PermissionsDialog(onDismiss: () -> Unit) {
    val perms = rememberPermissions()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限管理") },
        text = {
            Column {
                for (p in perms) PermRow(p)
                Text("安卓不允许应用自己收回权限：关掉开关会打开系统设置页，在那里撤销", fontSize = 12.sp,
                    color = LocalStatusColors.current.textTertiary, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun PermRow(p: Perm) {
    val tones = LocalStatusColors.current
    val granted = p.granted
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { p.toggle(granted != true) }
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(p.icon, contentDescription = null, modifier = Modifier.size(22.dp),
            tint = if (granted == false && p.essential) tones.warning else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(p.title, fontSize = 15.sp)
            Text(p.purpose, fontSize = 12.sp, color = tones.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (granted == null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = tones.textTertiary)
        } else {
            Switch(checked = granted, onCheckedChange = { p.toggle(it) })
        }
    }
}

/** 自动重连要用、却没授权的权限（通知、后台运行）：自动重连页据此显示警告。 */
fun missingEssential(perms: List<Perm>): List<String> = perms.filter { it.essential && it.granted == false }.map { it.title }

/** 一行警告：没授权时才显示，点一下打开权限管理。 */
@Composable
fun PermissionWarning(missing: List<String>, onClick: () -> Unit) {
    if (missing.isEmpty()) return
    val tones = LocalStatusColors.current
    XCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = tones.warning, modifier = Modifier.size(22.dp))
            Text("未授权：${missing.joinToString("、")}", fontSize = 14.sp, color = tones.warning, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = tones.textTertiary)
        }
    }
}
