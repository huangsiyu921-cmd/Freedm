package app.freedm.ui.screens

import android.net.Uri
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Job
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
        SiteStore.migrateSeed(prefs)
        mutableStateListOf<QuickSite>().also { it.addAll(SiteStore.load(prefs)) }
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
    // The animation currently driving `unfold` / `listOffset` / `pullY` / `dragX`.
    // Every settle and fold registers here so a new touch can cancel it — without
    // this, an in-flight fling kept writing `listOffset` while the finger dragged,
    // which is the twitch-then-snap-back the user saw.
    var animJob by remember { mutableStateOf<Job?>(null) }
    // Card-area height in px (needed by both the layout and the recover gesture),
    // how far the open list has been scrolled, and how far the cards have been
    // pulled off their slots. `pullY` is signed: positive = dragged up (the grab
    // bar pushing the list away), negative = dragged down past the top of the list.
    // Only the down direction is a recover zone; scrolling to the last card just
    // stops there.
    //
    // These three lived twice for a while — an inner copy in the layout scope
    // shadowed this one, so the gestures moved the inner state while the fold
    // animated the outer state and nothing visibly moved. One copy only.
    var areaHeightPx by remember { mutableFloatStateOf(0f) }
    var listOffset by remember { mutableFloatStateOf(0f) }
    var pullY by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val pitchPx = with(density) { 160.dp.toPx() + Space.md.toPx() }
    // How far the cards have to be pulled before letting go folds them. Kept short:
    // a long threshold reads as "the gesture does nothing".
    val recoverPx = with(density) { 64.dp.toPx() }
    // A pull this much shorter than recoverPx still folds if the release was a flick.
    val recoverFlickVelocity = 700f

    // Deck = shortcuts + a trailing "add" card.
    //
    // Deliberately not `remember(sites.size)`: that cached the list and only rebuilt
    // it when a shortcut was added or removed, so a colour/name edit was saved but
    // the card kept painting the old tint until the list size changed.
    val deck: List<QuickSite?> = sites.toList() + listOf(null)
    val count = deck.size.coerceAtLeast(1)
    val cardWidth = remember { mutableFloatStateOf(900f) }

    fun persist() = SiteStore.save(prefs, sites)

    fun upsert(old: QuickSite?, label: String, url: String, color: Int?) {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: url
        // Keeping the current tint when the user did not pick one: renaming a
        // shortcut should not throw away the colour read from its icon.
        val tint = color ?: old?.color
            ?: FaviconColor.brand(host) ?: FaviconColor.fromString(host)
        val provisional = QuickSite(label, url, tint)
        val at = old?.let { o -> sites.indexOfFirst { it.url == o.url } } ?: -1
        if (at >= 0) sites[at] = provisional else sites.add(provisional)
        if (index >= sites.size + 1) index = 0
        persist()
        // A hand-picked colour is final; only chase the site's own icon otherwise.
        if (color != null) return
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
        animJob?.cancel()
        animJob = scope.launch {
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

    /** Scroll range of the open list, in px. Re-read every call: areaHeightPx is state. */
    fun maxScrollNow() = (count * pitchPx - areaHeightPx).coerceAtLeast(0f)

    /** The scroll the open list should sit at for the current card. */
    fun restScroll() = (index * pitchPx).coerceIn(0f, maxScrollNow())

    fun settleUnfold() {
        // A fold triggered from the list is already animating; re-settling here
        // would read unfold > 0.4 mid-animation and bounce it straight back open.
        if (folding) return
        animJob?.cancel()
        animJob = scope.launch {
            val open = unfold > 0.4f
            val start = unfold
            val target = if (open) 1f else 0f
            val from = listOffset
            val to = if (open) restScroll() else 0f
            animate(0f, 1f, animationSpec = Motion.unfold) { p: Float, _: Float ->
                unfold = start + (target - start) * p
                // Carried along by the same progress: the list offset has to move with
                // `unfold`, otherwise the cards snap to their slot the moment the
                // animation starts.
                listOffset = from + (to - from) * p
            }
        }
    }

    /**
     * The cards were pulled off their slots far enough: send the list away and drop
     * the deck back in.
     *
     * Two beats on purpose. The pull finishes first, so the list is already clipped
     * off screen before `unfold` starts falling — otherwise the cards would jump from
     * list slots to stack slots in plain sight. Then `pullY` unwinds back to zero
     * while the deck settles, which reads as the deck landing back rather than the
     * list snapping shut. The direction follows whichever way it was pulled: down
     * past the top of the list, or up off the grab bar.
     */
    fun foldByPull() {
        if (folding) return
        folding = true
        Diag.log("DECK_FOLD", "pull=$pullY unfold=$unfold")
        animJob?.cancel()
        animJob = scope.launch {
            val sign = if (pullY < 0f) -1f else 1f
            val target = sign * areaHeightPx.coerceAtLeast(1f)
            if (abs(pullY) < abs(target)) {
                animate(
                    pullY,
                    target,
                    animationSpec = tween(200, easing = Motion.emphasized),
                ) { v: Float, _: Float -> pullY = v }
            }
            val fromPull = pullY
            val fromOffset = listOffset
            animate(0f, 1f, animationSpec = Motion.unfold) { p: Float, _: Float ->
                pullY = fromPull * (1f - p)
                unfold = 1f - p
                // Unwound together with the fold: leaving the scroll behind would put
                // the deck back on screen already shifted off its slot.
                listOffset = fromOffset * (1f - p)
            }
            pullY = 0f
            unfold = 0f
            listOffset = 0f
            folding = false
        }
    }

    /**
     * Release of a vertical drag inside the open list.
     *
     * Distance *and* velocity decide, in both directions: pulled far enough (or
     * flicked while already pulling) folds the deck; otherwise whatever was pulled
     * springs back and the list keeps its inertia. Scrolling to the last card is not
     * a recover gesture — it just stops there.
     */
    fun settleRecover(vy: Float) {
        val pulled = abs(pullY) >= recoverPx ||
            (abs(pullY) > recoverPx * 0.25f && abs(vy) > recoverFlickVelocity && pullY * vy < 0f)
        if (pulled) {
            foldByPull()
            return
        }
        animJob?.cancel()
        animJob = scope.launch {
            if (abs(pullY) > 0.5f) {
                // A tween, not a spring: a spring starts at zero velocity, which reads
                // as a hitch before the pull travels back.
                animate(pullY, 0f, animationSpec = Motion.enter) { v: Float, _: Float ->
                    pullY = v
                }
            } else if (abs(vy) > 150f) {
                val maxOffset = (count * pitchPx - areaHeightPx).coerceAtLeast(0f)
                // listOffset grows as the content moves up, so the fling runs the
                // other way round from the finger. It stops dead at either end — no
                // extra travel is invented after release, because that is what made
                // the list twitch and land somewhere else.
                val state = AnimationState(listOffset, -vy)
                state.animateDecay(
                    exponentialDecay(frictionMultiplier = 1.6f, absVelocityThreshold = 20f),
                ) {
                    listOffset = value.coerceIn(0f, maxOffset)
                    if (value <= 0f || value >= maxOffset) cancelAnimation()
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            // No background of its own: the WebView under it is transparent and the
            // Scaffold already paints `background`. Painting `surface` here made the
            // start page read as a panel with a frame around it.
            //
            // No horizontal padding either — the cards run edge to edge; only the
            // search field keeps its own margin. That gap on both sides was what
            // looked like a frame.
    ) {
        Spacer(Modifier.height(24.dp))
        SearchField(
            value = text,
            onValueChange = { text = it },
            onSubmit = { onSubmit(text) },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(30.dp))

        val listMode = unfold > 0.99f
        // The card area fills everything between the search field and the bottom
        // bar. It used to be a fixed 520dp viewport, which meant a scrolled card was
        // cut flat at the top of that box and only its arc showed at the bottom.
        val tp = 1f - (1f - unfold) * (1f - unfold)
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
                    // Half-extent of the grab bar's hit area (160×48dp, centred on the
                    // bottom edge). Anything starting inside it is the bar's gesture.
                    val barHalfWidth = 80.dp.toPx()
                    val barBandHeight = 44.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // The grab bar rides on the bottom edge; a touch that starts
                        // on it belongs to the bar, not to the list behind it. Bailing
                        // out here (instead of leaning on the "consumed" check below)
                        // is what keeps a fast bar drag from turning into a scroll.
                        if (listMode &&
                            down.position.y > size.height - barBandHeight &&
                            abs(down.position.x - size.width / 2f) < barHalfWidth
                        ) {
                            return@awaitEachGesture
                        }
                        // Take over from whatever was animating. Two writers on the same
                        // offset is exactly what made the list twitch and land elsewhere.
                        animJob?.cancel()
                        animJob = null
                        folding = false
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var lastSampleAt = down.uptimeMillis
                        var dx = 0f
                        var dy = 0f
                        var axis = 0 // 0 undecided, 1 sideways, 2 vertical
                        val slop = 6.dp.toPx()
                        while (true) {
                            val event = awaitPointerEvent()
                            // Sample before the release check: the up frame carries the
                            // last position, and leaving it out under-reported velocity —
                            // a hard flick then missed the flip threshold after release.
                            val change = event.changes.firstOrNull()
                            if (change != null && change.uptimeMillis > lastSampleAt) {
                                tracker.addPosition(change.uptimeMillis, change.position)
                                lastSampleAt = change.uptimeMillis
                            }
                            if (event.changes.none { it.pressed }) break
                            // A card may own this gesture; touching one must not also
                            // scroll the list underneath it.
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
                                    // Read it like a feed: dragging up moves the content
                                    // up (later cards), dragging down walks it back. The
                                    // last card is a hard stop — scrolling there is not a
                                    // recover gesture. Past the first card the leftover
                                    // travel drags the whole list down instead, and that
                                    // overshoot above the list is the recover zone.
                                    if (pan.y < 0f) {
                                        listOffset = (listOffset - pan.y)
                                            .coerceAtMost(maxListOffset)
                                    } else {
                                        val scroll = pan.y.coerceAtMost(listOffset)
                                        listOffset -= scroll
                                        val rest = pan.y - scroll
                                        if (rest > 0f) {
                                            pullY = (pullY - rest).coerceAtLeast(-areaHeightPx)
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                // Up = unfold, down = fold back. The travel is longer than
                                // the card height on purpose: the gesture felt twitchy when
                                // a short flick was enough.
                                val travel = if (pan.y < 0f) 1400f else 900f
                                unfold = (unfold - pan.y / travel).coerceIn(0f, 1f)
                                // The scroll rides along with the unfold: with only one
                                // basis for it, the cards keep a fixed relationship to the
                                // deck instead of sliding against it mid-gesture.
                                listOffset = (unfold * index * pitchPx).coerceIn(
                                    0f,
                                    (count * pitchPx - areaHeightPx).coerceAtLeast(0f),
                                )
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
                                    abs(dragX) > 1f -> {
                                        animJob?.cancel()
                                        animJob = scope.launch {
                                            animate(
                                                dragX,
                                                0f,
                                                animationSpec = Motion.enter,
                                            ) { v: Float, _: Float -> dragX = v }
                                        }
                                    }
                                }
                            }

                            2 -> if (listMode) settleRecover(velocity.y) else settleUnfold()
                        }
                    }
                },
            // Top-start, not centred: every card positions itself with an absolute
            // offset, and a centred parent would shift all of them by the leftover.
            contentAlignment = Alignment.TopStart,
        ) {
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
            // One scroll basis, never two. `scrollPx` used to switch between
            // `listOffset` (open) and `unfold * index * pitchPx` (folding), and the
            // two disagree as soon as the user has scrolled — the cards jumped the
            // moment the fold started. Now `listOffset` carries the unfolding offset
            // too, so it is continuous in every direction.
            val scrollPx = listOffset
            // No LaunchedEffect re-aligning `listOffset` here: the unfold gesture and
            // `settleUnfold()` already land it on `restScroll()`. The old side effect
            // fired whenever `listMode` flipped and could overwrite a drag that had
            // just started — one of the "twitch, then snap back" reports.

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
                        topPx = gridTop + (1f - appear) * slideIn - scrollPx - pullY,
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
                        scrollPx - pullY,
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
                            // The bar rides the bottom edge of the list, so it travels
                            // with the push instead of sitting still while the cards
                            // slide out from under it.
                            .offset { IntOffset(0, -pullY.roundToInt()) }
                            .graphicsLayer { alpha = gripAlpha }
                            .pointerInput(Unit) {
                                // Up pushes the list away — the same direction, and the
                                // same recover zone, as pushing the list itself past its
                                // top. Down unwinds a push that is already showing.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    animJob?.cancel()
                                    animJob = null
                                    folding = false
                                    val tracker = VelocityTracker()
                                    tracker.addPosition(down.uptimeMillis, down.position)
                                    var lastSampleAt = down.uptimeMillis
                                    var dragged = 0f
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull()
                                        if (change != null && change.uptimeMillis > lastSampleAt) {
                                            tracker.addPosition(change.uptimeMillis, change.position)
                                            lastSampleAt = change.uptimeMillis
                                        }
                                        if (event.changes.none { it.pressed }) break
                                        val pan = event.calculatePan()
                                        if (pan.y != 0f) {
                                            dragged += pan.y
                                            pullY = (pullY - pan.y)
                                                .coerceIn(0f, areaHeightPx)
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    val up = -tracker.calculateVelocity().y
                                    when {
                                        // Barely moved: a tap folds the list away.
                                        abs(dragged) < 8.dp.toPx() && up < 400f ->
                                            foldByPull()

                                        pullY >= recoverPx || up > recoverFlickVelocity * 1.3f ->
                                            foldByPull()

                                        else -> {
                                            animJob?.cancel()
                                            animJob = scope.launch {
                                                animate(
                                                    pullY,
                                                    0f,
                                                    animationSpec = Motion.enter,
                                                ) { v: Float, _: Float -> pullY = v }
                                            }
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
            onSave = { label, url, color ->
                upsert(editTarget, label, url, color)
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
            onSave = { label, url, color ->
                upsert(null, label, url, color)
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

/** Card tints offered in the shortcut editor. Kept to hues that survive the
 *  light/dark clamping in [FaviconColor.containerColor]. */
private val CARD_TINTS = listOf(
    0xFF2F6FEB, 0xFF008373, 0xFF2E963D, 0xFFB8860B, 0xFFD2691E,
    0xFFC62828, 0xFFFB7299, 0xFF7B4BD6, 0xFF55607A, 0xFF24292F,
).map { it.toInt() }

@Composable
private fun SiteEditor(
    initial: QuickSite?,
    onDismiss: () -> Unit,
    onSave: (String, String, Int?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    // Opens on the card's current tint, so the row shows what the card looks like
    // now; tapping 自动 drops back to following the site's icon.
    var picked by remember { mutableStateOf(initial?.color) }
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme

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
                Spacer(Modifier.height(16.dp))
                Text(
                    "卡片颜色",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TintChip(selected = picked == null, fill = scheme.surfaceVariant, dark = dark) {
                            picked = null
                        }
                        CARD_TINTS.take(4).forEach { tint ->
                            TintChip(selected = picked == tint, fill = Color(
                                FaviconColor.containerColor(tint, dark),
                            ), dark = dark) { picked = tint }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CARD_TINTS.drop(4).forEach { tint ->
                            TintChip(selected = picked == tint, fill = Color(
                                FaviconColor.containerColor(tint, dark),
                            ), dark = dark) { picked = tint }
                        }
                    }
                }
            }
        },
        // One row in the confirm slot so 删除 can sit at the far left, away from
        // 取消/确认 where it was one mis-tap from saving.
        dismissButton = null,
        confirmButton = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        val clean = normalizeUrl(url)
                        onSave(label.ifBlank { hostOf(clean) }, clean, picked)
                    },
                ) { Text("确认") }
            }
        },
    )
}

@Composable
private fun TintChip(
    selected: Boolean,
    fill: Color,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary
    else if (dark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.12f)
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            // clickable, not a raw tap detector: it gives the ripple that says the
            // swatch was actually taken.
            .clickable { onClick() },
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
