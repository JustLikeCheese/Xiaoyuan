package io.github.justlikecheese.xiaoyuan.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.justlikecheese.xiaoyuan.backend
import io.github.justlikecheese.xiaoyuan.core.Guard

/** 开机、应用更新后：之前开着自动重连就把守护或常驻服务拉起来（这两个广播里系统允许启动前台服务）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val b = context.backend
        if (!b.reconnect.value.enabled) return
        // 用 Root 守护保活时开机后重新拉起守护；前台服务方式、ADB（重启后要重新执行命令）或守护起不来时由 App 开前台服务兜底
        if (b.guardMode() == Guard.ROOT) {
            android.util.Log.i("BootReceiver", "${intent.action}：重新拉起 Root 守护")
            val pending = goAsync()
            b.restartGuard { pending.finish() }
        }
    }
}
