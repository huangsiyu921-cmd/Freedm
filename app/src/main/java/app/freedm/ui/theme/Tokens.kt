package app.freedm.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the Compose surface.
 *
 * One value per role, chosen once: a radius, a spacing step or a duration written
 * inline at a call site is how a UI drifts into looking hand-assembled. Material 3
 * supplies colour and type; these three fill the gaps it does not cover.
 */

/** Corner radii. Small for dense rows, large for the cards that lead the screen. */
object Radius {
    val sm = 12.dp // download rows
    val md = 18.dp // grid cells, inputs
    val lg = 26.dp // shortcut cards
    val pill = 28.dp // search field
}

/** Spacing, 4dp base: xs 6 · sm 10 · md 18 · lg 28. */
object Space {
    val xs = 6.dp
    val sm = 10.dp
    val md = 18.dp
    val lg = 28.dp
}

/**
 * Elevation. Light comes from above: a raised surface needs a shadow *and* a
 * lighter top edge, otherwise a stack of cards reads as flat coloured blocks.
 */
object Elev {
    val card = 10.dp
    val stackMid = 5.dp
    val stackLow = 2.dp
}

/** Motion. Every duration sits in the 180–280 ms band and shares one easing family. */
object Motion {
    /** Material's emphasized-decelerate: brisk start, soft landing. */
    val emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Flipping the deck between cards. */
    val enter = tween<Float>(durationMillis = 220, easing = emphasized)

    /** Snapping back when a drag does not pass the threshold. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 700f)

    /** Unfolding the deck into the list (and folding it back). */
    val unfold = tween<Float>(420, easing = FastOutSlowInEasing)
}
