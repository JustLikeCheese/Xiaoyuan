package io.github.justlikecheese.xiaoyuan.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.justlikecheese.xiaoyuan.core.ImportItem
import io.github.justlikecheese.xiaoyuan.core.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.widthIn

/*
 * 账号迁移的界面：账号管理右上角的“导入”（扫码 / 从图片识别 / 从文件）和“导出”（二维码 / 保存文件 / 分享）。
 * 迁移数据用导入码加密（格式见 core/Transfer.kt，和电脑端互通）。
 */

/** 导入、导出对话框开着没有。importText 为扫到或读到的迁移数据。 */
@Stable
class TransferUi {
    var importText by mutableStateOf<String?>(null)
    var exporting by mutableStateOf(false)
}

/** 账号管理右上角的两个按钮：导入（弹菜单选来源）、导出。 */
@Composable
fun TransferActions(ui: TransferUi) {
    val b = LocalBackend.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val accounts by b.accounts.collectAsStateWithLifecycle()

    fun accept(text: String?) {
        when {
            text.isNullOrBlank() -> {}
            Transfer.looksLike(text) -> ui.importText = text
            else -> b.showToast("warning", "不是账号迁移数据", "请扫电脑端“导出到手机”显示的二维码", 4000)
        }
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> accept(r.contents) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.Default) { runCatching { decodeQrImage(context, uri) }.getOrNull() }
            if (text == null) b.showToast("warning", "图片里没找到二维码", "", 3000) else accept(text)
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes().take(1 shl 20).toByteArray()) } }.getOrNull()
            }
            accept(text)
        }
    }

    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.FileDownload, contentDescription = "导入账号") }
        DownMenu(menu, onDismissRequest = { menu = false }, minWidth = 200.dp) {
            DropdownMenuItem(text = { Text("扫描二维码") }, leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, null) }, onClick = {
                menu = false
                scan.launch(ScanOptions().setPrompt("扫描电脑端“导出到手机”显示的二维码").setBeepEnabled(false)
                    .setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE))
            })
            DropdownMenuItem(text = { Text("从图片识别") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = {
                menu = false
                pickImage.launch(arrayOf("image/*"))
            })
            DropdownMenuItem(text = { Text("从文件导入") }, leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = {
                menu = false
                pickFile.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            })
        }
    }
    IconButton(onClick = { ui.exporting = true }, enabled = accounts.isNotEmpty()) {
        Icon(Icons.Outlined.QrCode2, contentDescription = "导出账号")
    }

    ui.importText?.let { text -> ImportDialog(text) { ui.importText = null } }
    if (ui.exporting) ExportDialog { ui.exporting = false }
}

/** 图片里的二维码（截图、相册里的照片），太大的先缩小。找不到返回 null。 */
private fun decodeQrImage(context: Context, uri: Uri): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val pixels = IntArray(bmp.width * bmp.height)
    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
}

/** 迁移数据的二维码（白底黑块，带白边）。 */
private fun qrBitmap(text: String, size: Int = 720): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else -1 }
    Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** 勾选列表的一行。 */
@Composable
private fun CheckRow(checked: Boolean, title: String, subtitle: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = LocalStatusColors.current.textTertiary)
        }
    }
}

/** 导入：先输入导入码解开，再勾选要导入的账号（已有的会更新）。 */
@Composable
private fun ImportDialog(text: String, onDismiss: () -> Unit) {
    val b = LocalBackend.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ImportItem>?>(null) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun unlock() {
        busy = true
        error = ""
        scope.launch {
            val r = withContext(Dispatchers.Default) { b.previewImport(text, code) }
            busy = false
            r.onSuccess {
                items = it
                chosen = it.map { i -> i.account }.toSet()
            }.onFailure { error = it.message ?: "解不开" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入账号") },
        text = {
            val list = items
            if (list == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        code, { code = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("导入码") }, leadingIcon = { Icon(Icons.Outlined.Key, null) },
                        placeholder = { Text("导出时设的导入码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        isError = error.isNotEmpty(),
                        supportingText = { if (error.isNotEmpty()) Text(error) },
                    )
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("选要导入的账号（已有的会更新）", color = LocalStatusColors.current.textTertiary, fontSize = 13.sp)
                    for (i in list) {
                        CheckRow(i.account in chosen, i.label,
                            listOfNotNull("已有，将更新".takeIf { i.exists }, "没有密码".takeIf { !i.hasPassword }).joinToString(" · ")) {
                            chosen = if (it) chosen + i.account else chosen - i.account
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (items == null) {
                TextButton(enabled = !busy && code.length >= Transfer.MIN_CODE, onClick = ::unlock) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("解开")
                }
            } else {
                TextButton(enabled = chosen.isNotEmpty(), onClick = {
                    b.applyImport(chosen)
                    onDismiss()
                }) { Text("导入") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 导出：勾选账号、设导入码（可自定义），显示二维码给另一台手机扫，或保存成文件、分享发到电脑（电脑端从文件导入）。 */
@Composable
private fun ExportDialog(onDismiss: () -> Unit) {
    val b = LocalBackend.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by b.accounts.collectAsStateWithLifecycle()
    val revealed by b.revealed.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf(accounts.map { it.account }.toSet()) }
    var code by remember { mutableStateOf(Transfer.randomCode()) }
    var payload by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val peek = remember { MutableInteractionSource() }
    val peeking by peek.collectIsPressedAsState()

    // 改了勾选或导入码，之前生成的就作废
    fun reset() {
        payload = null
        qr = null
    }

    fun build(then: (String) -> Unit) {
        payload?.let { return then(it) }
        busy = true
        scope.launch {
            val p = withContext(Dispatchers.Default) { b.exportAccounts(chosen, code) }
            busy = false
            if (p != null) {
                payload = p
                then(p)
            }
        }
    }

    // 用 octet-stream，系统不会给文件名再加 .txt 之类的后缀
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = payload ?: return@rememberLauncherForActivityResult
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write((p + "\n").toByteArray()) }
            b.showToast("success", "已保存", "把这个文件发到电脑，在电脑端账号管理点“导入”", 4000)
        }.onFailure { b.showToast("error", "保存失败", it.message ?: "", 5000) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出账号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("保存的密码一起导出，用导入码加密", color = LocalStatusColors.current.textTertiary, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    for (a in accounts) {
                        CheckRow(a.account in chosen, accountLabel(a, revealed),
                            if (a.passwordState == "saved") "" else "没有保存密码") {
                            chosen = if (it) chosen + a.account else chosen - a.account
                            reset()
                        }
                    }
                }
                OutlinedTextField(
                    code, {
                        code = it.trim()
                        reset()
                    }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("导入码") }, leadingIcon = { Icon(Icons.Outlined.Key, null) },
                    // 导入码默认打码，按住小眼睛才显示
                    visualTransformation = if (peeking) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = {}, interactionSource = peek) {
                                Icon(if (peeking) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "按住显示导入码")
                            }
                            IconButton(onClick = {
                                code = Transfer.randomCode()
                                reset()
                            }) { Icon(Icons.Outlined.Casino, contentDescription = "换一个") }
                        }
                    },
                    supportingText = { Text("至少 ${Transfer.MIN_CODE} 位，可以改成自己好记的；按住眼睛查看，导入时要输入") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                )
                qr?.let { bmp ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(
                            bmp.asImageBitmap(), contentDescription = "账号迁移二维码", filterQuality = FilterQuality.None,
                            modifier = Modifier.size(240.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp),
                        )
                    }
                    Text("用另一台手机的“导入 → 扫描二维码”扫这里", fontSize = 12.sp, color = LocalStatusColors.current.textTertiary)
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在加密…", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !busy, onClick = { build { p -> qr = qrBitmap(p) } }) { Text("二维码") }
                TextButton(enabled = !busy, onClick = { build { save.launch("校园网账号迁移.xyacc") } }) { Text("保存") }
                TextButton(enabled = !busy, onClick = {
                    build { p ->
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p)
                        context.startActivity(Intent.createChooser(send, "分享账号迁移数据"))
                    }
                }) { Text("分享") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
