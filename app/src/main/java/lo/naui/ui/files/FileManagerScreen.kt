@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package lo.naui.ui.files

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lo.naui.sys.FileStore
import lo.naui.sys.FsEntry
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege
import lo.naui.ui.component.GlassCard
import lo.naui.ui.component.glassShape
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 进来默认看这里 */
const val DEFAULT_DIR = "/storage/emulated/0/"

private const val SAF_PREFIX = "saf|"

private fun safKey(treeUri: String, docId: String): String = SAF_PREFIX + treeUri + "|" + docId

private fun parseSaf(key: String): Pair<String, String>? {
    if (!key.startsWith(SAF_PREFIX)) return null
    val rest = key.removePrefix(SAF_PREFIX)
    val i = rest.lastIndexOf('|')
    if (i <= 0) return null
    return rest.substring(0, i) to rest.substring(i + 1)
}

private fun parentOf(path: String): String {
    val t = path.trimEnd('/')
    val i = t.lastIndexOf('/')
    return if (i <= 0) "/" else t.substring(0, i)
}

/** 字节数写成人看的 */
fun readableSize(b: Long): String = when {
    b < 1024L -> b.toString() + " B"
    b < 1024L * 1024L -> (b / 1024.0).let { String.format("%.1f KB", it) }
    b < 1024L * 1024L * 1024L -> (b / 1024.0 / 1024.0).let { String.format("%.1f MB", it) }
    else -> (b / 1024.0 / 1024.0 / 1024.0).let { String.format("%.2f GB", it) }
}

/**
 * 文件管理。
 *
 * 顶部**没有返回键也没有标题**：左边是「⋮」，点开是「加目录 + 已添加的目录」；
 * 右边一块液态玻璃卡显示当前在哪。列表是左右两排，每张卡都是玻璃卡。
 *
 * 返回键 / 从左往右划 → 退上一级；已经到 `/` 了再退才回功能页。
 *
 * 列表按当前最高权限去读：root 走 su，Shizuku 走它的 shell，都没有就 java.io.File。
 */
@Composable
fun FileManagerScreen(
    onBack: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cardShape = glassShape()
    FileStore.init(ctx)

    var path by remember { mutableStateOf(DEFAULT_DIR) }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(PrivLevel.Normal) }
    var note by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var remarkTarget by remember { mutableStateOf<String?>(null) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            FileStore.addSafDir(uri.toString())
        }
    }

    fun load(target: String) {
        path = target
        note = null
        scope.launch {
            loading = true
            level = Privilege.level(ctx)
            val saf = parseSaf(target)
            entries = if (saf != null) {
                runCatching { querySaf(ctx, saf.first, saf.second) }
                    .onFailure { note = "这个目录读不了：" + (it.message ?: "") }
                    .getOrDefault(emptyList())
            } else {
                runCatching { Privilege.listDir(ctx, target) }
                    .onFailure { note = "读不了：" + (it.message ?: "") }
                    .getOrDefault(emptyList())
            }
            loading = false
        }
    }

    fun goUp() {
        val saf = parseSaf(path)
        if (saf != null) {
            runCatching {
                load(safKey(saf.first, DocumentsContract.getTreeDocumentId(Uri.parse(saf.first))))
            }
            return
        }
        if (path.isBlank() || path == "/") {
            onBack()
        } else {
            load(parentOf(path))
        }
    }

    LaunchedEffect(Unit) { load(DEFAULT_DIR) }

    // 返回键：先退目录，退到 / 才退出这一页
    BackHandler(enabled = true) { goUp() }

    Box(
        Modifier
            .fillMaxSize()
            // 从左往右划 = 返回上一级
            .pointerInput(path) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 140f) goUp() },
                    onHorizontalDrag = { _, drag -> total += drag },
                )
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 三个点
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { menuOpen = !menuOpen },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "⋮",
                        fontSize = 20.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                Spacer(Modifier.weight(1f))

                // 当前目录 —— 顶在最右边的液态玻璃卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.widthIn(max = 220.dp),
                    contentPadding = 12.dp,
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            path.ifBlank { "/" },
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            level.label + " · " + entries.size + " 项" +
                                if (loading) " · 读取中…" else "",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }

            note?.let {
                Text(
                    it,
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (entries.isEmpty() && !loading) {
                    item(key = "__empty__") {
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = 14.dp,
                        ) {
                            Column {
                                Text("这里是空的", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "没条目，或者这个权限读不到",
                                    fontSize = 11.5.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }

                items(entries, key = { it.path }) { e ->
                    GlassCard(
                        backdrop = backdrop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .clickable {
                                if (e.isDir) {
                                    load(e.path)
                                } else {
                                    note = e.name + " · " + readableSize(e.size)
                                }
                            },
                        contentPadding = 14.dp,
                    ) {
                        Column {
                            Text(
                                e.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (e.isDir) "目录" else readableSize(e.size),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        // ---- 三点菜单 ----
        if (menuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { menuOpen = false }
            )

            GlassCard(
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, top = 56.dp)
                    .widthIn(max = 320.dp),
                contentPadding = 14.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                menuOpen = false
                                treePicker.launch(null)
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("加目录", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    if (FileStore.safDirs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已添加（长按可改备注 / 删除）",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                        )
                        FileStore.safDirs.forEach { uri ->
                            val remark = FileStore.remarkOf(uri)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = {
                                            menuOpen = false
                                            runCatching {
                                                load(
                                                    safKey(
                                                        uri,
                                                        DocumentsContract.getTreeDocumentId(Uri.parse(uri)),
                                                    )
                                                )
                                            }
                                        },
                                        onLongClick = { remarkTarget = uri },
                                    )
                                    .padding(vertical = 9.dp, horizontal = 6.dp),
                            ) {
                                Text(
                                    remark.ifBlank { FileStore.label(uri) },
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (remark.isNotBlank()) {
                                    Text(
                                        FileStore.label(uri),
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 长按某个已添加目录 → 改备注 / 删掉
    val target = remarkTarget
    if (target != null) {
        AlertDialog.Builder(ctx)
            .setTitle(FileStore.remarkOf(target).ifBlank { FileStore.label(target) })
            .setItems(arrayOf("设置备注", "删除这个目录")) { _, which ->
                if (which == 0) {
                    val input = EditText(ctx).apply {
                        setText(FileStore.remarkOf(target))
                        setSelection(FileStore.remarkOf(target).length)
                        hint = "给这个目录起个名"
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle("备注")
                        .setView(input)
                        .setPositiveButton("保存") { _, _ ->
                            FileStore.setRemark(target, input.text.toString())
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    FileStore.removeSafDir(target)
                    runCatching {
                        ctx.contentResolver.releasePersistableUriPermission(
                            Uri.parse(target),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                remarkTarget = null
            }
            .setNegativeButton("取消") { _, _ -> remarkTarget = null }
            .show()
    }
}

/** 用系统文档接口列一个 SAF 目录 */
private fun querySaf(ctx: Context, treeUri: String, parentDocId: String): List<FsEntry> {
    val tree = Uri.parse(treeUri)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
    val out = mutableListOf<FsEntry>()
    ctx.contentResolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        ),
        null, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            val name = c.getString(1) ?: continue
            val mime = c.getString(2).orEmpty()
            val size = c.getLong(3)
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
            out += FsEntry(name, safKey(treeUri, id), isDir, if (isDir) 0L else size)
        }
    }
    return out.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
}
