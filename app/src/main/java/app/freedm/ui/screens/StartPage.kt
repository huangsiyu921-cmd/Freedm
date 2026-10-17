package app.freedm.ui.screens

import android.net.Uri
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.freedm.browser.FaviconColor
import app.freedm.data.QuickSite
import app.freedm.data.Repo
import app.freedm.data.SiteStore
import app.freedm.diag.Diag
import app.freedm.engine.Prober
import app.freedm.ui.theme.Elev
import app.freedm.ui.theme.Motion
import app.freedm.ui.theme.Radius
import app.freedm.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val sites = remember {
        val loaded = SiteStore.load(prefs).toMutableList()
        // One-time migration: earlier builds painted monochrome-logo sites with a
        // hashed hue, so GitHub came out teal/green. Re-seed from brand colours.
        if (!prefs.getBoolean("site_color_v2", false)) {
            for (i in loaded.indices) {
                val host = runCatching { android.net.Uri.parse(loaded[i].url).host }
                    .getOrNull().orEmpty()
                val colour = FaviconColor.brand(host) ?: FaviconColor.fromString(host)
                loaded[i] = loaded[i].copy(color = colour)
            }
            SiteStore.save(prefs, loaded)
            prefs.edit().putBoolean("site_color_v2", true).apply()
        }
        mutableStateListOf<QuickSite>().also { it.addAll(loaded) }
    }

    var text by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var unfold by remember { mutableFloatStateOf(0f) }
    var editing by remember { mutableStateOf<QuickSite?>(null) }
    var adding by remember { mutableStateOf(false) }
    // True while a fold started from the open list is animating, so the release
    // handler does not settle it back open.
    var folding by remember { mutableStateOf(false) }
    // Card-area height in px (needed by both the layout and the grab-bar gesture),
    // how far the list has been scrolled, and how far the bar has been dragged down.
    var areaHeightPx by remember { mutableFloatStateOf(0f) }
    var listOffset by remember { mutableFloatStateOf(0f) }
    var gripDrag by remember { mutableFloatStateOf(0f) }

    // Deck = shortcuts + a trailing "add" card.
    val deck: List<QuickSite?> = remember(sites.size) { sites.toList() + listOf(null) }
    val count = deck.size.coerceAtLeast(1)
    val cardWidth = remember { mutableFloatStateOf(900f) }

    fun persist() = SiteStore.save(prefs, sites)

    fun upsert(old: QuickSite?, label: String, url: String) {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: url
        val provisional = QuickSite(
            label,
            url,
            FaviconColor.brand(host) ?: FaviconColor.fromString(host),
        )
        val at = old?.let { o -> sites.indexOfFirst { it.url == o.url } } ?: -1
        if (at >= 0) sites[at] = provisional else sites.add(provisional)
        if (index >= sites.size + 1) index = 0
        persist()
        // Then upgrade the tint with the site's own icon, if reachable.
        scope.launch {
            val colour = withContext(Dispatchers.IO) {
                runCatching { FaviconColor.fetch(host, Prober.UA) }.getOrNull()
                    ?: FaviconColor.brand(host)
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

    fun flip(direction: Int, speed: Float = 0f) {
        scope.launch {
            // Matches the travel used when laying the deck out (1.05 × width): the
            // card is fully off screen by then, so the animation has nothing to pad.
            val travel = cardWidth.floatValue * 1.05f
            // A hard flick finishes faster; a slow drag glides.
            val duration = 240 - (speed / 6000f).coerceIn(0f, 1f) * 100
            animate(
                dragX,
                -direction * travel,
                animationSpec = tween(duration.toInt(), easing = Motion.emphasized),
            ) { v: Float, _: Float -> dragX = v }
            index = ((index + direction) % count + count) % count
            // The neighbour card has already travelled to the centre, so reset
            // without jumping — that is what removes the cut between cards.
            dragX = 0f
        }
    }

    fun settleUnfold() {
        // A fold triggered from the list is already animating; re-settling here
        // would read unfold > 0.4 mid-animation and bounce it straight back open.
        if (folding) return
        scope.launch {
            val open = unfold > 0.4f
            animate(
                unfold,
                if (open) 1f else 0f,
                animationSpec = Motion.unfold,
            ) { v: Float, _: Float -> unfold = v }
        }
    }

    /** Folding back from the open list: one animation, whichever end was pushed. */
    fun foldFromList() {
        if (folding) return
        folding = true
        Diag.log("DECK_FOLD", "unfold=$unfold")
        scope.launch {
            animate(
                unfold,
                0f,
                animationSpec = Motion.unfold,
            ) { v: Float, _: Float -> unfold = v }
            folding = false
        }
    }

    /** The grab bar was dragged far enough: let the list fall away, then show the deck. */
    fun dropListFromDrag() {
        if (folding) return
        folding = true
        scope.launch {
            val target = areaHeightPx.coerceAtLeast(1f)
            animate(
                gripDrag,
                target,
                animationSpec = Motion.unfold,
            ) { v: Float, _: Float -> gripDrag = v }
            unfold = 0f
            gripDrag = 0f
            folding = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        SearchField(
            value = text,
            onValueChange = { text = it },
            onSubmit = { onSubmit(text) },
        )
        Spacer(Modifier.height(30.dp))

        val listMode = unfold > 0.99f
        // The card area fills everything between the search field and the bottom
        // bar. It used to be a fixed 520dp viewport, which meant a scrolled card was
        // cut flat at the top of that box and only its arc showed at the bottom.
        val tp = 1f - (1f - unfold) * (1f - unfold)
        var areaHeightPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val pitchPx = with(density) { 160.dp.toPx() + Space.md.toPx() }
        var listOffset by remember { mutableFloatStateOf(0f) }
        // How far the open list has been dragged down by the grab bar. It moves the
        // cards with the finger instead of folding them upwards.
        var gripDrag by remember { mutableFloatStateOf(0f) }
        val maxListOffset = (count * pitchPx - areaHeightPx).coerceAtLeast(0f)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .onSizeChanged {
                    areaHeightPx = it.height.toFloat()
                    cardWidth.floatValue = it.width.toFloat()
                }
                // One gesture handler for the whole card area: vertical drags
                // unfold, horizontal drags flip. Keeping it on the container
                // (not the card) makes the touch target the full row.
                // Keyed on the deck size only: re-keying on `index` restarted the
                // detector right after a flip, which is what made a tap flicker.
                .pointerInput(count, listMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        folding = false
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dx = 0f
                        var dy = 0f
                        var axis = 0 // 0 undecided, 1 sideways, 2 vertical
                        val slop = 6.dp.toPx()
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.firstOrNull()?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                            }
                            if (event.changes.none { it.pressed }) break
                            // The grab bar or a card may own this gesture; touching the
                            // bar must not also scroll the list underneath it.
                            if (event.changes.any { it.isConsumed }) continue
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
                                if (listMode) {
                                    // The open list only scrolls. Leaving it is the grab
                                    // bar's job — neither end of the list folds it.
                                    listOffset = (listOffset - pan.y).coerceIn(0f, maxListOffset)
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                // Up = unfold, down = fold back. The travel is longer than
                                // the card height on purpose: the gesture felt twitchy when
                                // a short flick was enough.
                                val travel = if (pan.y < 0f) 1400f else 900f
                                unfold = (unfold - pan.y / travel).coerceIn(0f, 1f)
                                event.changes.forEach { it.consume() }
                            }
                        }
                        val velocity = tracker.calculateVelocity()
                        when (axis) {
                            1 -> {
                                val w = size.width.toFloat()
                                cardWidth.floatValue = w
                                // A flick flips even without crossing the distance
                                // threshold, and the flick's speed shortens the
                                // animation — that is the drift after release.
                                when {
                                    dragX < -w * 0.20f || velocity.x < -900f ->
                                        flip(1, abs(velocity.x))

                                    dragX > w * 0.20f || velocity.x > 900f ->
                                        flip(-1, abs(velocity.x))

                                    // Only animate back when there is something to undo;
                                    // otherwise every tap started (and cancelled) an
                                    // animation, which read as a double flash.
                                    abs(dragX) > 1f -> scope.launch {
                                        animate(
                                            dragX,
                                            0f,
                                            animationSpec = Motion.enter,
                                        ) { v: Float, _: Float -> dragX = v }
                                    }
                                }
                            }

                            2 -> settleUnfold()
                        }
                    }
                },
            // Top-start, not centred: every card positions itself with an absolute
            // offset, and a centred parent would shift all of them by the leftover.
            contentAlignment = Alignment.TopStart,
        ) {
            val density = LocalDensity.current
            val w = cardWidth.floatValue
            // Just over one container width: the front card is 94% wide and centred,
            // so it is clear of the edge at 1.03w. Anything longer is dead travel —
            // the card is already gone while the animation runs.
            val travel = w * 1.05f
            // < 0 = pulling the next card in, > 0 = pulling the previous one.
            val pull = (dragX / travel).coerceIn(-1f, 1f)
            val q = abs(pull)
            val toNext = pull < 0f
            // 0 = the deck, 1 = the list of big cards. Every card interpolates
            // between its deck slot and its list slot, so the deck *unfolds* instead
            // of one view fading out while another fades in.
            val t = unfold

            // Geometry of a resting stack: each card below is narrower and sits
            // lower. 4% per step reads as a real stack; 3% was too subtle to see.
            val frontFrac = 0.94f
            val midFrac = 0.90f
            val lowFrac = 0.86f
            val midY = with(density) { Space.md.toPx() }
            val lowY = midY * 2
            val stackH = with(density) { 160.dp.toPx() }
            // One card per row, full width, same height as a deck card — the open
            // state is the same cards laid out as a feed, not a grid of small tiles.
            val gap = with(density) { Space.md.toPx() }
            val rowH = stackH
            val slideIn = with(density) { 26.dp.toPx() }
            // The deck sits centred in whatever height is left; the list starts at the
            // very top of the same area.
            val stackInset = ((areaHeightPx - stackH) / 2f).coerceAtLeast(0f)

            fun centered(widthPx: Float) = (w - widthPx) / 2f

            val pitch = rowH + gap
            // While unfolding, the list is offset by hand so the card on top of the
            // deck stays under the thumb; once open the drag offset takes over.
            val scrollPx = if (listMode) listOffset else unfold * index * pitchPx
            LaunchedEffect(listMode, index) {
                if (listMode) listOffset = (index * pitchPx).coerceAtMost(maxListOffset)
            }

            // Cards are drawn with offsets, which do not contribute to layout height,
            // so the scrollable area needs an explicit spacer to have any range.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { (160.dp + Space.md) * count }),
            )

            // Left in the deck / entering from a side / list-only.
            deck.forEachIndexed { i, site ->
                val offset = ((i - index) % count + count) % count
                val gridLeft = 0f
                val gridTop = i * (rowH + gap)

                var frac = 0f
                var xPx = 0f
                var yPx = 0f
                var z = 0f
                var elev: androidx.compose.ui.unit.Dp = 0.dp
                var stackAlpha = 1f
                var inStack = false

                when {
                    offset == 0 -> {
                        inStack = true
                        if (toNext) {
                            frac = frontFrac
                            xPx = dragX
                            z = 3f
                            elev = Elev.card
                        } else {
                            // Sinks into the middle slot while the previous card
                            // covers it; without this it snapped down on release.
                            frac = androidx.compose.ui.util.lerp(frontFrac, midFrac, q)
                            yPx = midY * q
                            z = 3f
                            elev = Elev.card
                        }
                    }

                    offset == 1 && count > 1 -> {
                        inStack = true
                        if (toNext) {
                            // Rises to take the front slot — the card that "replaces"
                            // the one being dragged away.
                            frac = androidx.compose.ui.util.lerp(midFrac, frontFrac, q)
                            yPx = androidx.compose.ui.util.lerp(midY, 0f, q)
                            z = 2f
                            elev = Elev.stackMid
                        } else {
                            frac = androidx.compose.ui.util.lerp(midFrac, lowFrac, q)
                            yPx = androidx.compose.ui.util.lerp(midY, lowY, q)
                            z = 2f
                            elev = Elev.stackMid
                        }
                    }

                    offset == 2 && count > 2 -> {
                        inStack = true
                        if (toNext) {
                            frac = androidx.compose.ui.util.lerp(lowFrac, midFrac, q)
                            yPx = androidx.compose.ui.util.lerp(lowY, midY, q)
                            z = 1f
                            elev = Elev.stackLow
                        } else {
                            frac = lowFrac
                            xPx = q * travel
                            yPx = lowY
                            z = 1f
                            elev = Elev.stackLow
                            stackAlpha = if (q > 0.9f) (1f - q) / 0.1f else 1f
                        }
                    }

                    // Dragging left: the next-but-two enters at the bottom of the
                    // stack, so the loop never runs out of cards.
                    toNext && offset == 3 && count > 3 -> {
                        inStack = true
                        frac = lowFrac
                        xPx = (1f - q) * travel
                        yPx = lowY
                        z = 0f
                        elev = Elev.stackLow
                    }

                    // Dragging right: the previous card comes in over the front.
                    !toNext && offset == count - 1 && count > 1 && q > 0.001f -> {
                        inStack = true
                        frac = frontFrac
                        xPx = -(1f - q) * travel
                        z = 4f
                        elev = Elev.card
                    }
                }

                if (!inStack) {
                    // Not part of the stack: it only exists once the list opens, and
                    // slides up from just below its slot as it appears.
                    val appear = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    if (appear <= 0.01f) return@forEachIndexed
                    DeckCardBox(
                        site = site,
                        leftPx = gridLeft,
                        topPx = gridTop + (1f - appear) * slideIn - scrollPx + gripDrag,
                        widthPx = w,
                        heightPx = rowH,
                        radiusDp = 26f,
                        labelSp = 26f,
                        hostSp = 12f,
                        alpha = appear,
                        elevation = 0.dp,
                        z = 0f,
                        onClick = { if (site == null) adding = true else onOpen(site.url) },
                        onLongClick = { site?.let { editing = it } },
                    )
                    return@forEachIndexed
                }

                val stackW = frac * w
                val stackLeft = centered(stackW) + xPx
                DeckCardBox(
                    site = site,
                    leftPx = androidx.compose.ui.util.lerp(stackLeft, gridLeft, tp),
                    topPx = androidx.compose.ui.util.lerp(stackInset + yPx, gridTop, tp) -
                        scrollPx + gripDrag,
                    widthPx = androidx.compose.ui.util.lerp(stackW, w, tp),
                    heightPx = androidx.compose.ui.util.lerp(stackH, rowH, tp),
                    radiusDp = 26f,
                    labelSp = 26f,
                    hostSp = 12f,
                    alpha = stackAlpha,
                    elevation = androidx.compose.ui.unit.lerp(elev, 0.dp, t),
                    z = androidx.compose.ui.util.lerp(z, 0f, t),
                    onClick = { if (site == null) adding = true else onOpen(site.url) },
                    onLongClick = { site?.let { editing = it } },
                )
            }

            // Grab bar and page dots live inside the card area, over the cards: a
            // separate row left a strip of background between the last card and the bar.
            val gripAlpha = (unfold / 0.3f).coerceIn(0f, 1f)
            val dotsAlpha = (1f - unfold / 0.3f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .fillMaxWidth()
                    .height(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (dotsAlpha > 0.01f) {
                    Row(
                        Modifier.graphicsLayer { alpha = dotsAlpha },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(count.coerceAtMost(12)) { i ->
                            val active = i == index % count
                            val w by animateDpAsState(
                                targetValue = if (active) 18.dp else 6.dp,
                                animationSpec = Motion.settle(),
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
                if (gripAlpha > 0.01f) {
                    // 6dp of visible bar, 48dp of tap target.
                    Box(
                        Modifier
                            .width(160.dp)
                            .height(48.dp)
                            .graphicsLayer { alpha = gripAlpha }
                            .pointerInput(Unit) {
                                // The list follows the finger: dragging the bar down moves
                                // the cards down with it, and letting go either springs
                                // back or drops the list out of the way.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    folding = false
                                    var dragged = 0f
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.none { it.pressed }) break
                                        val pan = event.calculatePan()
                                        if (pan.y != 0f) {
                                            dragged += pan.y
                                            gripDrag = (gripDrag + pan.y).coerceAtLeast(0f)
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    when {
                                        abs(dragged) < 8.dp.toPx() -> foldFromList()
                                        gripDrag > areaHeightPx * 0.25f -> dropListFromDrag()
                                        else -> scope.launch {
                                            animate(
                                                gripDrag,
                                                0f,
                                                animationSpec = Motion.enter,
                                            ) { v: Float, _: Float -> gripDrag = v }
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp, 6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
            }
        }
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
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(Radius.pill)

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.45f))
            // A 1dp idle border keeps the field quiet; only focus earns the weight.
            .border(
                width = if (focused) 2.dp else 1.dp,
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
private fun DeckCardBox(
    site: QuickSite?,
    leftPx: Float,
    topPx: Float,
    widthPx: Float,
    heightPx: Float,
    radiusDp: Float,
    labelSp: Float,
    hostSp: Float,
    alpha: Float,
    elevation: androidx.compose.ui.unit.Dp,
    z: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    if (alpha < 0.02f || widthPx < 2f || heightPx < 2f) return
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    // The "+" slot keeps its place in the stack: leaving it out made the deck read
    // as losing a card mid-swipe.
    val base = site?.let { Color(FaviconColor.containerColor(it.color, dark)) }
        ?: scheme.surfaceVariant.copy(alpha = 0.55f)
    val content = site?.let { Color(FaviconColor.onContainerColor(it.color, dark)) }
        ?: scheme.onSurfaceVariant
    val shape = RoundedCornerShape(radiusDp.dp)
    Box(
        Modifier
            .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .zIndex(z)
            .graphicsLayer { this.alpha = alpha }
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .background(base)
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.06f else 0.30f), shape)
            .pointerInput(site?.url) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (site == null) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加快捷方式",
                tint = content,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(26.dp),
            )
        } else {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxSize(),
                verticalArrangement = if (labelSp > 18f) {
                    Arrangement.SpaceBetween
                } else {
                    Arrangement.Center
                },
            ) {
                Text(
                    site.label,
                    fontSize = labelSp.sp,
                    fontWeight = if (labelSp > 18f) FontWeight.SemiBold else FontWeight.Medium,
                    color = content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hostSp > 2f) {
                    Text(
                        hostOf(site.url),
                        fontSize = hostSp.sp,
                        color = content.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
