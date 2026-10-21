package io.github.justlikecheese.xiaoyuan.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.core.AccountView
import io.github.justlikecheese.xiaoyuan.core.MASK
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.foundation.layout.widthIn

/** 账号对话框开着没有：editing 为 null 不在编辑，"" 为添加，其他为要编辑的账号；deleting 为要确认删除的账号。 */
@Stable
class AccountsUi {
    var editing by mutableStateOf<String?>(null)
    var deleting by mutableStateOf<String?>(null)
    val transfer = TransferUi()
}

/**
 * 账号管理：当前登录的账号在列表里标“当前登录”；点账号编辑，长按出菜单，右滑切换、左滑删除；右下角添加。
 * 账号、密码跟随右上角眼睛打码。
 */
@Composable
fun AccountsPage(ui: AccountsUi, scroll: ScrollState) {
    val b = LocalBackend.current
    val accounts by b.accounts.collectAsStateWithLifecycle()
    val st by b.status.collectAsStateWithLifecycle()
    val revealed by b.revealed.collectAsStateWithLifecycle()
    // 当前在线的账号（门户返回的 uid 带运营商后缀，和已保存账号的“账号+后缀”比对）
    val onlineFull = if (st.online) st.account.lowercase() else ""
    val onlineAcc = accounts.firstOrNull { it.full.lowercase() == onlineFull }
    val tones = LocalStatusColors.current

    Page("账号管理", scroll, bottomPadding = 96.dp, actions = { TransferActions(ui.transfer) }) {
        // 当前登录的是哪个账号直接在列表里标出来；在线的是没保存过的账号时，列表最上面多一行
        val unsavedOnline = onlineFull.isNotEmpty() && onlineAcc == null
        if (accounts.isEmpty() && !unsavedOnline) {
            Text("还没有保存的账号，点右下角「添加账号」。", color = tones.textTertiary, modifier = Modifier.padding(4.dp))
        } else {
            XCard {
                if (unsavedOnline) {
                    UnsavedOnlineItem(if (revealed) st.account else MASK) { ui.editing = "" }
                    if (accounts.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
                accounts.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    AccountItem(
                        a,
                        isOnline = onlineFull.isNotEmpty() && a.full.lowercase() == onlineFull,
                        revealed = revealed,
                        onEdit = { ui.editing = a.account },
                        onSwitch = { b.switchTo(a.account) },
                        onDelete = { ui.deleting = a.account },
                    )
                }
            }
        }
    }
}

/** 当前在线、但没保存过的账号（比如在网页上登录的）：点一下添加。 */
@Composable
private fun UnsavedOnlineItem(account: String, onAdd: () -> Unit) {
    val tones = LocalStatusColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(tones.success.copy(alpha = 0.08f))
            .clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(tones.success.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = tones.success, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("未保存的账号", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Pill("当前登录", tones.success)
            }
            Text("$account · 点此保存", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 账号管理页右下角的“添加账号”。 */
@Composable
fun AddAccountFab(ui: AccountsUi) {
    XFab(
        onClick = { ui.editing = "" },
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
        expanded = true,
        text = "添加账号",
    )
}

private fun passwordHint(a: AccountView) = when (a.passwordState) {
    "lost" -> "需重新输入密码"
    "none" -> "未保存密码"
    else -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountItem(
    a: AccountView,
    isOnline: Boolean,
    revealed: Boolean,
    onEdit: () -> Unit,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
) {
    val tones = LocalStatusColors.current
    val b = LocalBackend.current
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState()
    var menu by remember { mutableStateOf(false) }
    val year = "${LocalDate.now().year}-"

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = !isOnline,   // 已经在线的不用切换
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onSwitch()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                else -> {}
            }
            scope.launch { state.snapTo(SwipeToDismissBoxValue.Settled) }   // 操作完弹回原位
        },
        backgroundContent = {
            val (color, icon, text, start) = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Quad(tones.successBg, SWITCH_ICON, "切换到此账号", true)
                SwipeToDismissBoxValue.EndToStart -> Quad(tones.dangerBg, Icons.Outlined.Delete, "删除", false)
                else -> Quad(MaterialTheme.colorScheme.surfaceContainerLow, null, "", true)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp),
                contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd) {
                if (icon != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(icon, contentDescription = null)
                        Text(text, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .then(if (isOnline) Modifier.background(tones.success.copy(alpha = 0.08f)) else Modifier)
                    .combinedClickable(onClick = onEdit, onLongClick = { menu = true })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 标题用备注，没写备注时用运营商（不显示打码的账号）
                val title = a.note.ifEmpty { a.carrier }
                Box(
                    Modifier.size(40.dp).background(
                        if (isOnline) tones.success.copy(alpha = 0.18f) else MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title.take(1), fontWeight = FontWeight.SemiBold,
                        color = if (isOnline) tones.success else MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (isOnline) Pill("当前登录", tones.success)
                        passwordHint(a).takeIf { it.isNotEmpty() }?.let { Pill(it, tones.warning) }
                    }
                    Text(
                        listOfNotNull(
                            if (revealed) a.account else MASK,
                            a.carrier.takeIf { a.note.isNotEmpty() },
                            a.lastUsed.takeIf { it != "-" }?.let { "上次使用 " + it.removePrefix(year) },
                        ).joinToString(" · "),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(terminalIcon(if (a.terminalType >= 0) a.terminalType else b.terminalType()),
                    contentDescription = a.terminalName, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
            }
            Box(Modifier.align(Alignment.BottomEnd)) {
                DownMenu(menu, onDismissRequest = { menu = false }, minWidth = 200.dp) {
                    DropdownMenuItem(text = { Text("切换到此账号") }, leadingIcon = { Icon(SWITCH_ICON, null) }, enabled = !isOnline,
                        onClick = { menu = false; onSwitch() })
                    DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private data class Quad(val color: androidx.compose.ui.graphics.Color, val icon: androidx.compose.ui.graphics.vector.ImageVector?,
                        val text: String, val start: Boolean)
