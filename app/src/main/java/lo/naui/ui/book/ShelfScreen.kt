@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package lo.naui.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lo.naui.book.Book
import lo.naui.book.BookStore
import lo.naui.sys.UiState
import lo.naui.ui.component.GlassCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/**
 * 雫的书柜。
 *
 * 一次把所有书竖着排下来，滚到最底下是那个「+」——
 * 点它进新建页。
 */
@Composable
fun ShelfScreen(
    onOpenBook: (String) -> Unit,
    onNewBook: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val ctx = LocalContext.current
    BookStore.init(ctx)
    UiState.init(ctx)

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var deleteTarget by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(tick) {
        books = withContext(Dispatchers.IO) { BookStore.list() }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(18.dp))
            Text("雫的书柜", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (books.isEmpty()) "还一本书都没有，往下滑点那个 +"
                else books.size.toString() + " 本 · 点一本翻开",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(14.dp))
        }

        deleteTarget?.let { target ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { deleteTarget = null },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 28.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MiuixTheme.colorScheme.surface)
                        .clickable(enabled = false) { }
                        .padding(20.dp),
                ) {
                    Text("删掉这本书？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "《" + target.title + "》和它的 " + target.chapters.size +
                            " 章正文、朗读音频都会一起删掉，删了拿不回来",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .clickable { deleteTarget = null }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("算了", fontSize = 13.sp) }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.error.copy(alpha = 0.16f))
                                .clickable {
                                    val t = target
                                    deleteTarget = null
                                    BookStore.delete(ctx, t.id)
                                    tick++
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("删掉", fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(books, key = { _, b -> b.id }) { i, book ->
                BookRow(
                    book = book,
                    enterIndex = i,
                    backdrop = backdrop,
                    onClick = { UiState.saveBook(book.id); onOpenBook(book.id) },
                    onLongClick = { deleteTarget = book },
                )
            }

            // 最下面那个单独的 +
            item(key = "__add__") {
                GlassCard(
                    backdrop = backdrop,
                    enterIndex = books.size,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onNewBook() },
                    contentPadding = 26.dp,
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "+",
                                    fontSize = 26.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Light,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("写一本新的", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    enterIndex: Int = -1,
    backdrop: com.kyant.backdrop.Backdrop?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    GlassCard(
        backdrop = backdrop,
        enterIndex = enterIndex,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentPadding = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 书脊
            Box(
                Modifier
                    .size(width = 34.dp, height = 48.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    book.title.take(1),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    book.chapters.size.toString() + " 章 · " +
                        if (book.characters.isEmpty()) "没人物" else book.characters.joinToString("、") { it.name }.take(24),
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.updatedAt > 0) {
                    Text(
                        stamp.format(Date(book.updatedAt)),
                        fontSize = 10.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
