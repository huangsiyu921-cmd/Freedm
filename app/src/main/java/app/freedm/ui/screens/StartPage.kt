package app.freedm.ui.screens

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.freedm.browser.FaviconColor
import app.freedm.data.QuickSite
import app.freedm.data.Repo
import app.freedm.data.SiteStore
import app.freedm.engine.Prober
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Shared with SettingsScreen; DuckDuckGo is unreachable on mainland networks. */
internal const val SEARCH_DEFAULT = "https://cn.bing.com/search?q="

/**
 * Browser start page: a search field plus the shortcuts as a card deck.
 *
 * Sideways drag flips the deck, a downward drag unfolds it into the full grid
 * (upward folds it back), tap opens, long-press edits. The list is user-owned —
 * the last card in the deck adds a new one.
 */
@Composable
fun StartPage(
    onSubmit: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { Repo.prefs() }
    val scope = rememberCoroutineScope()
    val sites = remember { mutableStateListOf<QuickSite>().also { it.addAll(SiteStore.load(prefs)) } }

    var text by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var unfold by remember { mutableFloatStateOf(0f) }
    var editing by remember { mutableStateOf<QuickSite?>(null) }
    var adding by remember { mutableStateOf(false) }

    // Deck = shortcuts + a trailing "add" card.
    val deck: List<QuickSite?> = remember(sites.size) { sites.toList() + listOf(null) }
    val count = deck.size.coerceAtLeast(1)
    val cardWidth = remember { mutableFloatStateOf(900f) }

    fun persist() = SiteStore.save(prefs, sites)

    fun upsert(old: QuickSite?, label: String, url: String) {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: url
        val provisional = QuickSite(label, url, FaviconColor.fromString(host))
        val at = old?.let { o -> sites.indexOfFirst { it.url == o.url } } ?: -1
        if (at >= 0) sites[at] = provisional else sites.add(provisional)
        if (index >= sites.size + 1) index = 0
        persist()
        // Then upgrade the tint with the site's own icon, if reachable.
        scope.launch {
            val colour = withContext(Dispatchers.IO) {
                runCatching { FaviconColor.fetch(host, Prober.UA) }.getOrNull()
            }
            if (colour != null) {
                val i = sites.indexOfFirst { it.url == url }
                if (i >= 0) {
                    sites[i] = sites[i].copy(color = colour)
                    persist()
                }
            }
        }
    }

    fun flip(direction: Int) {
        scope.launch {
            val w = cardWidth.floatValue
            animate(
                dragX,
                -direction * w,
                animationSpec = tween(170, easing = FastOutSlowInEasing),
            ) { v: Float, _: Float -> dragX = v }
            index = ((index + direction) % count + count) % count
            // The neighbour card has already travelled to the centre, so reset
            // without jumping — that is what removes the cut between cards.
            dragX = 0f
        }
    }

    fun settleUnfold() {
        scope.launch {
            val open = unfold > 0.4f
            animate(
                unfold,
                if (open) 1f else 0f,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
            ) { v: Float, _: Float -> unfold = v }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        SearchField(value = text, onValueChange = { text = it }, onSubmit = { onSubmit(text) })
        Spacer(Modifier.height(30.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(250.dp)
                // One gesture handler for the whole deck area: vertical drags
                // unfold, horizontal drags flip. Keeping it on the container
                // (not the card) makes the touch target the full row.
                .pointerInput(count, index) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var dx = 0f
                        var dy = 0f
                        var axis = 0 // 0 undecided, 1 sideways, 2 vertical
                        val slop = 6.dp.toPx()
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val pan = event.calculatePan()
                            dx += pan.x
                            dy += pan.y
                            if (axis == 0) {
                                axis = when {
                                    abs(dy) > slop -> 2
                                    abs(dx) > slop -> 1
                                    else -> 0
                                }
                            }
                            if (axis == 1) {
                                dragX += pan.x
                                event.changes.forEach { it.consume() }
                            } else if (axis == 2) {
                                unfold = (unfold - pan.y / 320f).coerceIn(0f, 1f)
                                event.changes.forEach { it.consume() }
                            }
                        }
                        when (axis) {
                            1 -> {
                                val w = size.width * 0.93f
                                cardWidth.floatValue = w
                                when {
                                    dragX < -w * 0.18f -> flip(1)
                                    dragX > w * 0.18f -> flip(-1)
                                    else -> scope.launch {
                                        animate(
                                            dragX,
                                            0f,
                                            animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
                                        ) { v: Float, _: Float -> dragX = v }
                                    }
                                }
                            }

                            2 -> settleUnfold()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val deckAlpha = (1f - unfold * 2f).coerceIn(0f, 1f)
            val front = deck.getOrNull(index)

            if (deckAlpha > 0.02f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = deckAlpha },
                    contentAlignment = Alignment.Center,
                ) {
                    for (depth in 2 downTo 1) {
                        val back = deck.getOrNull((index + depth) % count) ?: continue
                        BackCard(
                            site = back,
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .height(148.dp)
                                .graphicsLayer {
                                    val s = 1f - depth * 0.05f
                                    scaleX = s
                                    scaleY = s
                                    translationY = depth * 17.dp.toPx()
                                    alpha = if (depth == 1) 0.9f else 0.65f
                                },
                        )
                    }

                    // The neighbour rides in from the far side while the front
                    // card leaves, so a flip has no gap in the middle.
                    if (abs(dragX) > 2f) {
                        val nbIndex = if (dragX < 0f) (index + 1) % count else (index - 1 + count) % count
                        val nb = deck.getOrNull(nbIndex)
                        val w = cardWidth.floatValue
                        val offset = if (dragX < 0f) dragX + w else dragX - w
                        val nbModifier = Modifier
                            .fillMaxWidth(0.93f)
                            .height(160.dp)
                            .graphicsLayer { translationX = offset }
                        if (nb == null) {
                            BackCard(site = null, modifier = nbModifier)
                        } else {
                            CardFace(site = nb, modifier = nbModifier)
                        }
                    }

                    val frontModifier = Modifier
                        .fillMaxWidth(0.93f)
                        .height(160.dp)
                        .graphicsLayer { translationX = dragX }

                    if (front == null) {
                        AddCard(frontModifier, onClick = { adding = true })
                    } else {
                        CardFace(
                            site = front,
                            modifier = frontModifier.pointerInput(front.url) {
                                detectTapGestures(
                                    onTap = { onOpen(front.url) },
                                    onLongPress = { editing = front },
                                )
                            },
                        )
                    }
                }
            }

            val gridAlpha = ((unfold - 0.15f) / 0.85f).coerceIn(0f, 1f)
            if (gridAlpha > 0.02f) {
                SiteGrid(
                    sites = sites,
                    onOpen = onOpen,
                    onEdit = { editing = it },
                    onAdd = { adding = true },
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = gridAlpha
                            translationY = (1f - gridAlpha) * 20.dp.toPx()
                        },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            // Unfolded: a grab bar that folds the grid back. Deck: page dots.
            if (unfold >= 0.25f) {
                Box(
                    Modifier
                        .size(40.dp, 6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    scope.launch {
                                        animate(
                                            unfold,
                                            0f,
                                            animationSpec = tween(280, easing = FastOutSlowInEasing),
                                        ) { v: Float, _: Float -> unfold = v }
                                    }
                                },
                            )
                        },
                )
            }
            if (unfold < 0.25f) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(count.coerceAtMost(12)) { i ->
                        val active = i == index % count
                        val w by animateDpAsState(
                            targetValue = if (active) 18.dp else 6.dp,
                            animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
                            label = "dot",
                        )
                        Box(
                            Modifier
                                .size(w, 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(0.45f))
    }

    val editTarget = editing
    if (editTarget != null) {
        SiteEditor(
            initial = editTarget,
            onDismiss = { editing = null },
            onSave = { label, url ->
                upsert(editTarget, label, url)
                editing = null
            },
            onDelete = {
                sites.removeAll { it.url == editTarget.url }
                if (index >= sites.size + 1) index = 0
                persist()
                editing = null
            },
        )
    } else if (adding) {
        SiteEditor(
            initial = null,
            onDismiss = { adding = false },
            onSave = { label, url ->
                upsert(null, label, url)
                adding = false
            },
            onDelete = null,
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(28.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                width = 2.dp,
                color = if (focused) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = if (focused) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "搜索或输入网址",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = scheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (value.isNotBlank()) {
                            focusManager.clearFocus()
                            onSubmit()
                        }
                    },
                ),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CardFace(site: QuickSite, modifier: Modifier = Modifier) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val base = Color(FaviconColor.containerColor(site.color, dark))
    val content = Color(FaviconColor.onContainerColor(site.color, dark))

    Box(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .background(base)
            .padding(22.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                site.label,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hostOf(site.url),
                fontSize = 12.sp,
                color = content.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BackCard(site: QuickSite?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val base = site?.let {
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        Color(FaviconColor.containerColor(it.color, dark))
    } ?: scheme.surfaceVariant
    Box(modifier.clip(RoundedCornerShape(26.dp)).background(base))
}

@Composable
private fun AddCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .background(scheme.surfaceVariant)
            .border(
                BorderStroke(2.dp, scheme.outlineVariant),
                RoundedCornerShape(26.dp),
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = "添加快捷方式",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun SiteGrid(
    sites: List<QuickSite>,
    onOpen: (String) -> Unit,
    onEdit: (QuickSite) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells: List<QuickSite?> = sites + listOf(null)
    Column(
        modifier
            .fillMaxWidth()
            .then(
                // Only scroll when the grid cannot fit; a scrollable grid would
                // swallow the upward drag that folds it back into the deck.
                if (cells.size > 9) Modifier.verticalScroll(rememberScrollState()) else Modifier,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cells.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { site ->
                    if (site == null) {
                        AddCell(Modifier.weight(1f), onAdd)
                    } else {
                        GridCell(
                            site = site,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(site.url) },
                            onLongClick = { onEdit(site) },
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GridCell(
    site: QuickSite,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val base = Color(FaviconColor.containerColor(site.color, dark))
    val content = Color(FaviconColor.onContainerColor(site.color, dark))
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(base)
            .pointerInput(site.url) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            site.label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddCell(modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = "添加快捷方式",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SiteEditor(
    initial: QuickSite?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加快捷方式" else "编辑快捷方式") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址") },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        dismissButton = {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("删除") }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        val clean = normalizeUrl(url)
                        onSave(label.ifBlank { hostOf(clean) }, clean)
                    },
                ) { Text("确认") }
            }
        },
    )
}

private fun normalizeUrl(raw: String): String {
    val t = raw.trim()
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        t.contains('.') && !t.contains(' ') -> "https://$t"
        else -> t
    }
}

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
