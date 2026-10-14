// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.prefs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of the two floating keyboard halves ("floating halves" mode).
 *
 * All rectangles are in window coordinates, which is also the coordinate space of the one and only
 * [helium314.keyboard.keyboard.MainKeyboardView]: the keyboard is as large as the whole IME window,
 * and its keys are laid out in two blocks inside [left] and [right]. Because there is a single
 * keyboard with a single coordinate space, the gesture library (and PointerTracker's shared input
 * point list) sees both halves as one connected keyboard.
 *
 * A panel rect includes the drag handle strip at its top ([handleHeight]). The keys are inside the
 * rest of the panel, shrunk by [innerPadding].
 *
 * Terms:
 *  - user rects ([userLeft], [userRight]): what the user chose, saved per orientation, relative to the window and to the normal panel size
 *  - active rects ([left], [right]): what is currently shown. Same as the user rects, except in emoji mode.
 *  - built rects ([builtLeft], [builtRight]): the rects the current keyboard's keys were built for. They differ from the
 *    active rects only while a drag / resize is in progress, and are used to preview the keys at the new position.
 */
object HalvesLayout {
    @JvmField val left = Rect()
    @JvmField val right = Rect()
    /** the suggestion strip / toolbar, placed directly above the higher of the two panels */
    @JvmField val strip = Rect()

    private val userLeft = Rect()
    private val userRight = Rect()
    @JvmField val builtLeft = Rect()
    @JvmField val builtRight = Rect()

    @JvmField var windowWidth = 0
    @JvmField var windowHeight = 0
    /** navigation bar overlap at the bottom of the window (only needed with edge-to-edge) */
    @JvmField var bottomInset = 0
    /** height of the top row of both halves: drag handle (left half), toolbar and handle (right half), opacity slider */
    @JvmField var handleHeight = 0
    /** width of the (fixed size) drag handle */
    @JvmField var handleWidth = 0
    @JvmField var gripSize = 0
    @JvmField var stripHeight = 0
    @JvmField var innerPadding = 0
    @JvmField var cornerRadius = 0
    private var gap = 0
    private var density = 1f
    private var landscape = false

    // sizes of the key blocks in the normal keyboard (from the last build), used to derive minimum panel sizes
    private var minWidthLeft = 0
    private var minWidthRight = 0
    private var minHeightPanel = 0

    /** increased whenever the layout changes in a way that needs the keyboard to be rebuilt (part of the keyboard cache key) */
    @JvmField var version = 0

    // The layout is saved relative to the settings: for each half a position (left edge of the left half / right edge of the
    // right half as a fraction of the window width, bottom as a fraction of the window height) and a size as a factor of the
    // normal size of a half. The normal size comes from the keyboard height, key size, ... settings, so when those change
    // the halves change too, instead of the keys being stretched to a panel size that was fixed on first use.
    private class HalfState(var edge: Float, var bottom: Float, var widthFactor: Float, var heightFactor: Float)
    private val states = arrayOfNulls<Array<HalfState>>(2)
    // Normal size of the key area of a half = exactly the size the keys of the docked keyboard have with the user's settings
    // (keyboard height, key size, gaps, split spacer, ...), set when building the keyboard. Keys are scaled relative to it.
    @JvmField var refKeyAreaWidth = 0
    @JvmField var refKeyAreaHeight = 0
    private val refPanelWidth get() = refKeyAreaWidth + 2 * innerPadding
    private val refPanelHeight get() = refKeyAreaHeight + handleHeight + 2 * innerPadding
    // default distance of the halves to the window edges (side padding setting)
    private var defaultLeftEdge = 0
    private var defaultRightEdge = 0
    private var loadedFromPrefs = booleanArrayOf(false, false)

    // ---- emoji mode: the emoji palette is one view, shown scaled in the space of the two panels ----
    @JvmField var emojiMode = false
    @JvmField val emojiContent = Rect()
    @JvmField var emojiScale = 1f
    private var emojiDesignWidth = 0
    private var emojiDesignHeight = 0
    /** width of the empty columns in the middle of the emoji grid / of the gap in its bottom row (design pixels); < 0 if unknown */
    @JvmField var emojiGapDesign = -1f
    @JvmField var emojiBottomGapDesign = -1f
    /** called (on any thread) when the measured gaps of the emoji palette changed while it is shown over the halves */
    @JvmField var emojiListener: Runnable? = null

    @JvmStatic
    fun publishEmojiGap(design: Float) {
        if (emojiGapDesign == design) return
        emojiGapDesign = design
        if (emojiMode) emojiListener?.run()
    }

    @JvmStatic
    fun publishEmojiBottomGap(design: Float) {
        if (emojiBottomGapDesign == design) return
        emojiBottomGapDesign = design
        if (emojiMode) emojiListener?.run()
    }

    /**
     * Width of the gap the emoji grid should have (in its own, unscaled pixels) so that it is as wide as the gap between the key
     * areas of the letters halves once the palette is scaled. -1 if the layout of the halves is not known yet.
     */
    @JvmStatic
    fun emojiGapTarget(designWidth: Int): Float {
        if (userLeft.isEmpty || userRight.isEmpty || designWidth <= 0) return -1f
        val keyAreaGap = (userRight.left + innerPadding) - (userLeft.right - innerPadding)
        val span = userRight.right - userLeft.left - 2 * innerPadding
        val scale = (span / designWidth.toFloat()).coerceIn(0.3f, 1.5f)
        return max(0f, keyAreaGap / scale)
    }

    // ---- toolbar layout: "linked" = left handle moves both halves vertically, toolbar only above the right half ----
    @JvmField var linked = false

    // ---- opacity ----
    @JvmField var opacity = 1f
    private var opacityLoaded = false
    /** the slider for the opacity, shown above the left half (where its toolbar would be) */
    @JvmField val opacityBar = Rect()
    @JvmField var opacityBarVisible = false

    // ---- interaction (drag / resize) ----
    private val startRect = Rect()
    private val startRectOther = Rect()
    private var startActiveTop = 0
    private var startActiveBottom = 0
    @JvmField var previewActive = false
    private val previewMatrixLeft = Matrix()
    private val previewMatrixRight = Matrix()

    private const val MIN_KEY_SIZE_DP = 44
    /** gap between the emoji halves, relative to the emoji palette width (also used for the empty columns of the emoji grid) */
    // keys starting with "floating_pos" don't make Settings reload all values when written (see Settings.reloadOnChanged)
    private const val PREF_LAYOUT_PORTRAIT = "floating_pos_halves_layout2_portrait"
    private const val PREF_LAYOUT_LANDSCAPE = "floating_pos_halves_layout2_landscape"
    private const val PREF_OPACITY = "floating_pos_halves_opacity"
    const val MIN_OPACITY = 0.3f // never completely transparent
    const val LEFT = 0
    const val RIGHT = 1

    private val orientation get() = if (landscape) 1 else 0

    /**
     * Update metrics that depend on the screen / window. [viewWidth] and [viewHeight] are the size of the
     * input view if it has been laid out already, otherwise 0 and an estimate from the display is used.
     * (If the estimate is wrong, the input view reports its real size and the keyboard is reloaded.)
     */
    @JvmStatic
    fun refreshMetrics(context: Context, viewWidth: Int, viewHeight: Int) {
        val res = context.resources
        density = res.displayMetrics.density
        landscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        gripSize = (28 * density).toInt()
        innerPadding = (3 * density).toInt()
        cornerRadius = (12 * density).toInt()
        gap = (6 * density).toInt()
        stripHeight = if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
            else res.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        linked = Settings.getValues().mFloatingHalvesLinked
        // The top row of both halves is as high as the toolbar, which is in the top row of the right half (next to its drag handle).
        // With both rows equal, the keys of the halves stay aligned.
        val normalHandleHeight = res.getDimensionPixelSize(R.dimen.config_floating_handle_height)
        handleHeight = if (stripHeight > 0) stripHeight else normalHandleHeight
        handleWidth = (56 * density).toInt()
        windowWidth = if (viewWidth > 0) viewWidth else ResourceUtils.getDefaultKeyboardWidth(context)
        windowHeight = if (viewHeight > 0) viewHeight else res.displayMetrics.heightPixels - statusBarHeight(context)
        loadOpacity(context)
        if (!loadedFromPrefs[orientation]) loadStates(context)
        applyStates()
    }

    /** the opacity is used by the floating halves and (if enabled in the settings) by the normal keyboard */
    @JvmStatic
    fun loadOpacity(context: Context) {
        if (opacityLoaded) return
        opacityLoaded = true
        opacity = context.prefs().getFloat(PREF_OPACITY, 1f).coerceIn(MIN_OPACITY, 1f)
    }

    /** [persist] only when the user is done (e.g. lifted the finger from the slider) */
    @JvmStatic
    fun setOpacity(context: Context, value: Float, persist: Boolean) {
        opacity = value.coerceIn(MIN_OPACITY, 1f)
        if (persist) context.prefs().edit { putFloat(PREF_OPACITY, opacity) }
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    // ---- persistence ----

    private fun prefKey() = if (landscape) PREF_LAYOUT_LANDSCAPE else PREF_LAYOUT_PORTRAIT

    private fun loadStates(context: Context) {
        loadedFromPrefs[orientation] = true
        val v = context.prefs().getString(prefKey(), null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        if (v == null || v.size != 8) return
        if (listOf(v[0], v[1], v[4], v[5]).any { it < 0f || it > 1f } || listOf(v[2], v[3], v[6], v[7]).any { it <= 0f || it > 10f }) return
        states[orientation] = arrayOf(HalfState(v[0], v[1], v[2], v[3]), HalfState(v[4], v[5], v[6], v[7]))
    }

    private fun saveStates(context: Context) {
        val s = states[orientation] ?: return
        val string = s.flatMap { listOf(it.edge, it.bottom, it.widthFactor, it.heightFactor) }.joinToString(",")
        context.prefs().edit { putString(prefKey(), string) }
    }

    /** forget the saved layout of both orientations, defaults are used again with the next keyboard build */
    @JvmStatic
    fun resetSavedLayout(context: Context) {
        context.prefs().edit {
            remove(PREF_LAYOUT_PORTRAIT)
            remove(PREF_LAYOUT_LANDSCAPE)
        }
        states.fill(null)
        loadedFromPrefs.fill(true) // nothing there any more
        userLeft.setEmpty()
        userRight.setEmpty()
        version++
    }

    /** default: each half at its side of the window, bottom aligned, normal size */
    private fun defaultStates(): Array<HalfState> {
        val bottom = maxBottom() / windowHeight.toFloat()
        return arrayOf(
            HalfState(defaultLeftEdge / windowWidth.toFloat(), bottom, 1f, 1f),
            HalfState(1f - defaultRightEdge / windowWidth.toFloat(), bottom, 1f, 1f)
        )
    }

    /** calculate the user rects from the saved layout and the normal panel size */
    private fun applyStates() {
        if (windowWidth <= 0 || windowHeight <= 0 || refKeyAreaWidth <= 0 || refKeyAreaHeight <= 0) return
        val s = states[orientation] ?: defaultStates() // not saved: always follows the settings, e.g. side padding
        val leftWidth = (refPanelWidth * s[0].widthFactor).toInt()
        val leftHeight = (refPanelHeight * s[0].heightFactor).toInt()
        val leftX = (s[0].edge * windowWidth).toInt()
        val leftBottom = (s[0].bottom * windowHeight).toInt()
        userLeft.set(leftX, leftBottom - leftHeight, leftX + leftWidth, leftBottom)
        val rightWidth = (refPanelWidth * s[1].widthFactor).toInt()
        val rightHeight = (refPanelHeight * s[1].heightFactor).toInt()
        val rightX = (s[1].edge * windowWidth).toInt()
        val rightBottom = (s[1].bottom * windowHeight).toInt()
        userRight.set(rightX - rightWidth, rightBottom - rightHeight, rightX, rightBottom)
        clampUserRects()
        updateActive()
    }

    private fun storeStates() {
        if (refKeyAreaWidth <= 0 || refKeyAreaHeight <= 0) return
        fun state(r: Rect, edge: Int) = HalfState(
            edge / windowWidth.toFloat(), r.bottom / windowHeight.toFloat(),
            r.width() / refPanelWidth.toFloat(), r.height() / refPanelHeight.toFloat()
        )
        states[orientation] = arrayOf(state(userLeft, userLeft.left), state(userRight, userRight.right))
    }

    // ---- layout when building the keyboard ----

    private var refSettingsKey = 0

    /**
     * Called by the keyboard builder with the size of the key area of the normal keyboard ([baseWidth], [baseHeight]; they depend
     * on the user's keyboard height, key size and gap settings), the spacer in the middle of the rows ([splitSpacer], see
     * [splitSpacerRelativeWidth]), the side padding, and the extents of the two key blocks as they are in the docked keyboard.
     *
     * The normal size of a half is the key area the keys of a half need in the docked keyboard, so with the default layout the keys
     * have exactly the docked size, and they never leave their panel. (The two blocks of a layout are not exactly equally wide:
     * e.g. the shift key of the letters layout is on the left, so the left block is wider than the right one.) Both halves use the
     * wider one, and the size doesn't shrink when switching to a layout with narrower blocks, so panels stay put.
     * Calculates minimum panel sizes and clamps the layout.
     */
    @JvmStatic
    fun layoutForBuild(baseWidth: Int, baseHeight: Int, splitSpacer: Float, leftPadding: Int, rightPadding: Int,
                       leftBlockWidth: Float, rightBlockWidth: Float, blockHeight: Float) {
        val settingsKey = 31 * (31 * baseWidth + baseHeight) + splitSpacer.hashCode()
        val width = max(baseWidth / (2f * (1f + splitSpacer)), max(leftBlockWidth, rightBlockWidth)).toInt()
        val height = max(baseHeight.toFloat(), blockHeight).toInt()
        val sameSettings = settingsKey == refSettingsKey
        refSettingsKey = settingsKey
        refKeyAreaWidth = if (sameSettings) max(width, refKeyAreaWidth) else width
        refKeyAreaHeight = if (sameSettings) max(height, refKeyAreaHeight) else height
        defaultLeftEdge = leftPadding
        defaultRightEdge = rightPadding

        // The minimum key size is never larger than the keys of the docked keyboard: the panels don't get bigger than the user's
        // settings say, and the default size is exactly the docked size. (Assuming the typical 5 keys per row and half, and 5 rows.)
        val minKey = MIN_KEY_SIZE_DP * density
        val minFactorWidth = min(1f, minKey / (refKeyAreaWidth / 5f))
        val minFactorHeight = min(1f, minKey / (refKeyAreaHeight / 5f))
        val maxPanelWidth = windowWidth / 2 - gap / 2
        // on narrow screens both halves can't have keys of the minimum size, the panels are then limited to half of the screen
        minWidthLeft = min((refKeyAreaWidth * minFactorWidth).toInt() + 2 * innerPadding, maxPanelWidth)
        minWidthRight = minWidthLeft
        minHeightPanel = min((refKeyAreaHeight * minFactorHeight).toInt() + handleHeight + 2 * innerPadding, maxBottom() - topLimit())

        applyStates()
        builtLeft.set(userLeft)
        builtRight.set(userRight)
    }

    private fun maxBottom() = windowHeight - bottomInset

    /** The panels can go up to the top of the window: the toolbar is in the top row of the right half, not above it. */
    private fun topLimit() = 0

    /** very rough layout, only used if the emoji palette is shown before any keyboard has been built */
    private fun ensureRects() {
        if (!userLeft.isEmpty && !userRight.isEmpty) return
        val bottom = maxBottom()
        val height = (windowHeight * 0.4f).toInt().coerceAtMost(bottom - topLimit())
        userLeft.set(0, bottom - height, windowWidth / 2 - gap / 2, bottom)
        userRight.set(windowWidth / 2 + gap / 2, bottom - height, windowWidth, bottom)
    }

    private fun clampUserRects() {
        clampPanel(userLeft, LEFT)
        clampPanel(userRight, RIGHT)
        if (linked && userLeft.bottom != userRight.bottom) {
            // linked halves share their baseline
            userRight.offset(0, userLeft.bottom - userRight.bottom)
            clampPanel(userRight, RIGHT)
        }
        // no check for overlapping halves here: they can only overlap if the user resized one over the other (inner grip),
        // and then that layout is kept. Moving a half (see interact) still never moves it over the other one.
    }

    /** bring [r] to a valid size (minimum key size, window) and move it into the window */
    private fun clampPanel(r: Rect, half: Int) {
        val minW = (if (half == LEFT) minWidthLeft else minWidthRight).coerceAtMost(windowWidth)
        val otherMinW = if (half == LEFT) minWidthRight else minWidthLeft
        val maxW = max(minW, windowWidth - gap - otherMinW) // leave space for the other half
        val minH = minHeightPanel.coerceAtMost(max(1, maxBottom() - topLimit()))
        val w = r.width().coerceIn(minW, maxW)
        val h = r.height().coerceIn(minH, max(minH, maxBottom() - topLimit()))
        val x = r.left.coerceIn(0, max(0, windowWidth - w))
        val y = r.top.coerceIn(topLimit(), max(topLimit(), maxBottom() - h))
        r.set(x, y, x + w, y + h)
    }

    // ---- derived rects ----

    @JvmStatic
    fun updateActive() {
        ensureRects()
        if (emojiMode && emojiDesignWidth > 0 && emojiDesignHeight > 0) computeEmojiRects()
        else {
            left.set(userLeft)
            right.set(userRight)
        }
        updateStrip()
        updatePreviewMatrices()
    }

    @JvmStatic
    fun updateStrip() {
        // top row of the right half: the drag handle at its left corner (not in linked mode, there the left handle moves both
        // halves) and the toolbar next to it, which scrolls
        val handleSpace = if (linked) 0 else min(handleWidth, right.width())
        strip.set(right.left + handleSpace, right.top, right.right, right.top + handleHeight)
        // the opacity slider replaces the drag handle of the left half, it gets the width of the top row of that half
        opacityBar.set(left.left, left.top, left.right, left.top + handleHeight)
    }

    /** where the drag handle of [half] is (the left one is centered in the top row of the left half, the right one is in the corner) */
    @JvmStatic
    fun handleRect(half: Int, out: Rect) {
        if (half == LEFT) {
            val width = min(handleWidth, left.width())
            val x = left.left + (left.width() - width) / 2
            out.set(x, left.top, x + width, left.top + handleHeight)
        } else {
            out.set(right.left, right.top, right.left + min(handleWidth, right.width()), right.top + handleHeight)
        }
    }

    /** the area that should receive touches, everything else goes to the app below */
    @JvmStatic
    fun fillTouchableRegion(region: Region, includeStrip: Boolean) {
        region.set(left)
        region.union(right)
        if (includeStrip && stripHeight > 0)
            region.union(strip)
        if (opacityBarVisible) // a touch anywhere dismisses the slider (the app doesn't get that touch)
            region.union(Rect(0, 0, windowWidth, windowHeight))
    }

    /** The key (hit) area of a panel: below the handle and inside the inner padding. */
    @JvmStatic
    fun keyArea(panel: Rect) = Rect(
        panel.left + innerPadding, panel.top + handleHeight,
        panel.right - innerPadding, panel.bottom - innerPadding
    )

    /** part of the keyboard cache key, so a different window size or layout version results in a new keyboard */
    @JvmStatic
    fun cacheKey(): Int =
        (31 * (31 * (31 * (31 * (31 * windowWidth + windowHeight) + bottomInset) + version) + splitSpacerRelativeWidth().hashCode())
            + handleHeight) or 1 // the top area of the halves (drag handle / toolbar) is part of their layout

    /**
     * Width of the gap in the middle of the rows, relative to the keyboard width: the user's split keyboard setting
     * (split enabled and "split distance"), exactly like in the docked keyboard. 0 if split is not enabled.
     */
    @JvmStatic
    fun splitSpacerRelativeWidth(): Float {
        val settings = Settings.getValues()
        return if (settings.mIsSplitKeyboardEnabled) settings.mSplitKeyboardSpacerRelativeWidth else 0f
    }

    // ---- drag and resize ----

    @JvmStatic
    fun beginInteraction(half: Int) {
        startRect.set(if (half == LEFT) userLeft else userRight)
        startRectOther.set(if (half == LEFT) userRight else userLeft)
        startActiveTop = left.top
        startActiveBottom = left.bottom
    }

    /**
     * Move ([resize] false) or resize one half. [dx] and [dy] are relative to the position at [beginInteraction].
     * Resizing moves the outer bottom corner (bottom left for the left half, bottom right for the right half),
     * the opposite corner stays where it is. With [inner] it moves the inner bottom corner instead (bottom right for the left half,
     * bottom left for the right half), without any limit against the other half: the halves may overlap then.
     */
    @JvmStatic
    fun interact(half: Int, resize: Boolean, dx: Float, dy: Float, inner: Boolean = false) {
        if (emojiMode) {
            // emoji palette: only vertical, both halves together (its panels are derived from the layout of the letters)
            if (resize) return
            val minDy = max(topLimit() - startActiveTop, max(topLimit() - startRect.top, topLimit() - startRectOther.top))
            val maxDy = min(maxBottom() - startActiveBottom, min(maxBottom() - startRect.bottom, maxBottom() - startRectOther.bottom))
            val moveY = if (maxDy < minDy) 0 else dy.toInt().coerceIn(minDy, maxDy)
            val (startLeft, startRight) = if (half == LEFT) startRect to startRectOther else startRectOther to startRect
            userLeft.set(startLeft)
            userLeft.offset(0, moveY)
            userRight.set(startRight)
            userRight.offset(0, moveY)
            previewActive = false
            updateActive()
            return
        }
        val target = if (half == LEFT) userLeft else userRight
        val r = Rect(startRect)
        val minW = (if (half == LEFT) minWidthLeft else minWidthRight).coerceAtMost(windowWidth)
        val minH = minHeightPanel.coerceAtMost(max(1, maxBottom() - topLimit()))
        if (!resize && linked && half == LEFT) {
            // linked: only vertical, and the right half moves along (without leaving the window either)
            val minDy = max(topLimit() - startRect.top, topLimit() - startRectOther.top)
            val maxDy = min(maxBottom() - startRect.bottom, maxBottom() - startRectOther.bottom)
            val moveY = (if (maxDy < minDy) 0f else dy.coerceIn(minDy.toFloat(), maxDy.toFloat())).toInt()
            userLeft.set(startRect)
            userLeft.offset(0, moveY)
            userRight.set(startRectOther)
            userRight.offset(0, moveY)
            updateActive()
            previewActive = true
            return
        }
        if (!resize) {
            // x range: inside the window, and never overlapping the other half
            val minX = if (half == RIGHT) userLeft.right + gap else 0
            val maxX = if (half == LEFT) min(windowWidth - startRect.width(), userRight.left - gap - startRect.width())
                else windowWidth - startRect.width()
            r.offsetTo(
                (startRect.left + dx).toInt().coerceIn(minX, max(minX, maxX)),
                (startRect.top + dy).toInt().coerceIn(topLimit(), max(topLimit(), maxBottom() - startRect.height()))
            )
        } else {
            r.bottom = (startRect.bottom + dy).toInt().coerceIn(min(startRect.top + minH, maxBottom()), maxBottom())
            val movesLeftEdge = (half == LEFT) != inner // outer grip: left edge of the left half, right edge of the right half
            if (movesLeftEdge) r.left = (startRect.left + dx).toInt().coerceIn(0, max(0, startRect.right - minW))
            else r.right = (startRect.right + dx).toInt().coerceIn(min(startRect.left + minW, windowWidth), windowWidth)
        }
        target.set(r)
        updateActive()
        previewActive = true
    }

    /** save the new layout, the keyboard needs to be rebuilt afterwards */
    @JvmStatic
    fun endInteraction(context: Context) {
        clampUserRects()
        storeStates()
        saveStates(context)
        version++
        previewActive = false
        updateActive()
    }

    /** matrix that maps a key from where it was built to where its half is now, null if there is nothing to preview for this key */
    @JvmStatic
    fun previewMatrixFor(keyCenterX: Float, keyCenterY: Float): Matrix? {
        if (!previewActive) return null
        return when {
            builtLeft.contains(keyCenterX.toInt(), keyCenterY.toInt()) -> previewMatrixLeft
            builtRight.contains(keyCenterX.toInt(), keyCenterY.toInt()) -> previewMatrixRight
            else -> null
        }
    }

    private fun updatePreviewMatrices() {
        if (builtLeft.isEmpty || builtRight.isEmpty) return
        previewMatrixLeft.setRectToRect(RectF(builtLeft), RectF(left), Matrix.ScaleToFit.FILL)
        previewMatrixRight.setRectToRect(RectF(builtRight), RectF(right), Matrix.ScaleToFit.FILL)
    }

    // ---- emoji ----

    /**
     * The emoji palette is a single view of [designWidth] x [designHeight] pixels. In halves mode it is scaled
     * and shown over two panels: the grid has empty columns in the middle (like in the normal split keyboard), which
     * is where the gap between the panels is. The panels get the same height here, and can't be moved while it is shown.
     */
    @JvmStatic
    fun setEmojiMode(enabled: Boolean, designWidth: Int, designHeight: Int) {
        emojiMode = enabled
        emojiDesignWidth = designWidth
        emojiDesignHeight = designHeight
        updateActive()
    }

    @JvmStatic
    fun emojiSpacerRelativeWidth() = splitSpacerRelativeWidth()

    private fun computeEmojiRects() {
        val pad = innerPadding
        val bottom = max(userLeft.bottom, userRight.bottom)
        val x0 = userLeft.left
        val x1 = userRight.right
        val availW = max(1, x1 - x0 - 2 * pad)
        // not taller than the letters halves, the emoji palette is bigger than the letters keyboard when docked
        val lettersKeyAreaHeight = max(userLeft.height(), userRight.height()) - handleHeight - 2 * pad
        val availH = max(1, min(bottom - topLimit() - handleHeight - 2 * pad, lettersKeyAreaHeight))
        val s = min(availW / emojiDesignWidth.toFloat(), availH / emojiDesignHeight.toFloat())
        emojiScale = s
        val contentW = emojiDesignWidth * s
        val contentH = emojiDesignHeight * s
        // The gap between the panels is the same as between the letters halves, unless the palette has emojis or keys in it
        // (then it is as wide as the smaller gap of the grid and of the bottom row).
        val lettersGap = max(0, userRight.left - userLeft.right).toFloat()
        val gapPx = (listOf(emojiGapDesign, emojiBottomGapDesign).filter { it >= 0f }.map { it * s } + lettersGap).minOrNull()!!
        val x0f = x0.toFloat()
        val startX = (((userLeft.right + userRight.left) / 2f) - contentW / 2f).coerceIn(x0f, max(x0f, x1 - contentW))
        val centerX = startX + contentW / 2f
        val contentTop = bottom - pad - contentH
        val panelTop = contentTop - handleHeight
        emojiContent.set(startX.toInt(), contentTop.toInt(), (startX + contentW).toInt(), (contentTop + contentH).toInt())
        left.set((startX - pad).toInt(), panelTop.toInt(), (centerX - gapPx / 2).toInt(), bottom)
        right.set((centerX + gapPx / 2).toInt(), panelTop.toInt(), (startX + contentW + pad).toInt(), bottom)
    }
}
