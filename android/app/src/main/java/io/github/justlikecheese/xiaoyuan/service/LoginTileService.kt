package io.github.justlikecheese.xiaoyuan.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.justlikecheese.xiaoyuan.R
import io.github.justlikecheese.xiaoyuan.backend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 快捷设置磁贴：亮着表示在线；点一下在线就注销，不在线就用最近使用的账号登录。 */
class LoginTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val b = backend
        b.startup()
        b.refresh(true)
        job?.cancel()
        job = scope.launch {
            combine(b.status, b.busy) { st, busy -> st to busy }.collect { (st, busy) -> update(st.online, st.state, busy) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val b = backend
        if (b.status.value.online) b.logout() else b.loginDefault()
    }

    private fun update(online: Boolean, state: String, busy: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_wifi)
        tile.label = "校园网"
        tile.state = when {
            online -> Tile.STATE_ACTIVE
            state == "error" -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        val sub = when {
            busy -> "处理中…"
            online -> "已在线"
            state == "offline" -> "未登录"
            state == "error" -> "连不上门户"
            else -> "查询中…"
        }
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.contentDescription = "校园网 $sub"
        tile.updateTile()
    }
}
