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
    @JvmField var handleHeight = 0
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
    // normal panel size (keyboard size from the current settings, plus handle and padding), set when building the keyboard
    private var refPanelWidth = 0
    private var refPanelHeight = 0
    private var loadedFromPrefs = booleanArrayOf(false, false)

    // ---- emoji mode: the emoji palette is one view, shown scaled in the space of the two panels ----
    @JvmField var emojiMode = false
    @JvmField val emojiContent = Rect()
    @JvmField var emojiScale = 1f
    private var emojiDesignWidth = 0
    private var emojiDesignHeight = 0
    /** width of the empty columns in the middle of the emoji grid, as measured by the grid keyboard; <= 0 if unknown */
    @JvmField var emojiGapDesign = 0f

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
    @JvmField var previewActive = false
    private val previewMatrixLeft = Matrix()
    private val previewMatrixRight = Matrix()

    private const val MIN_KEY_SIZE_DP = 44
    /** gap between the emoji halves, relative to the emoji palette width (also used for the empty columns of the emoji grid) */
    private const val EMOJI_GAP_FRACTION = 0.05f
    // keys starting with "floating_pos" don't make Settings reload all values when written (see Settings.reloadOnChanged)
    private const val PREF_LAYOUT_PORTRAIT = "floating_pos_halves_layout_portrait"
    private const val PREF_LAYOUT_LANDSCAPE = "floating_pos_halves_layout_landscape"
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
        handleHeight = res.getDimensionPixelSize(R.dimen.config_floating_handle_height)
        gripSize = (28 * density).toInt()
        innerPadding = (3 * density).toInt()
        cornerRadius = (12 * density).toInt()
        gap = (6 * density).toInt()
        stripHeight = if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
            else res.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        windowWidth = if (viewWidth > 0) viewWidth else ResourceUtils.getDefaultKeyboardWidth(context)
        windowHeight = if (viewHeight > 0) viewHeight else res.displayMetrics.heightPixels - statusBarHeight(context)
        linked = Settings.getValues().mFloatingHalvesLinked
        if (!opacityLoaded) {
            opacityLoaded = true
            opacity = context.prefs().getFloat(PREF_OPACITY, 1f).coerceIn(MIN_OPACITY, 1f)
        }
        if (!loadedFromPrefs[orientation]) loadStates(context)
        applyStates()
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
        return arrayOf(HalfState(0f, bottom, 1f, 1f), HalfState(1f, bottom, 1f, 1f))
    }

    /** calculate the user rects from the saved layout and the normal panel size */
    private fun applyStates() {
        val s = states[orientation] ?: return
        if (windowWidth <= 0 || windowHeight <= 0 || refPanelWidth <= 0 || refPanelHeight <= 0) return
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
        if (refPanelWidth <= 0 || refPanelHeight <= 0) return
        fun state(r: Rect, edge: Int) = HalfState(
            edge / windowWidth.toFloat(), r.bottom / windowHeight.toFloat(),
            r.width() / refPanelWidth.toFloat(), r.height() / refPanelHeight.toFloat()
        )
        states[orientation] = arrayOf(state(userLeft, userLeft.left), state(userRight, userRight.right))
    }

    // ---- layout when building the keyboard ----

    /**
     * Called by the keyboard builder with the extents of the two key blocks of the normal keyboard
     * ([designLeft], [designRight], in pixels), the typical key size ([keyWidth], [keyHeight]) and the size of the key area
     * of the normal keyboard ([baseWidth], [baseHeight], which depend on the user's keyboard height and size settings).
     * Calculates the normal and minimum panel sizes, uses the default layout if nothing was saved, and clamps the layout.
     */
    @JvmStatic
    fun layoutForBuild(designLeft: RectF, designRight: RectF, keyWidth: Float, keyHeight: Float, baseWidth: Int, baseHeight: Int) {
        refPanelWidth = baseWidth / 2 + 2 * innerPadding
        refPanelHeight = baseHeight + handleHeight + 2 * innerPadding

        val minKey = MIN_KEY_SIZE_DP * density
        val scaleX = if (keyWidth > 0f) minKey / keyWidth else 1f
        val scaleY = if (keyHeight > 0f) minKey / keyHeight else 1f
        val maxPanelWidth = windowWidth / 2 - gap / 2
        // on narrow screens both halves can't have keys of the minimum size, the panels are then limited to half of the screen
        minWidthLeft = min((designLeft.width() * scaleX).toInt() + 2 * innerPadding, maxPanelWidth)
        minWidthRight = min((designRight.width() * scaleX).toInt() + 2 * innerPadding, maxPanelWidth)
        minHeightPanel = min((designLeft.height() * scaleY).toInt() + handleHeight + 2 * innerPadding, maxBottom() - stripHeight)

        if (states[orientation] == null) states[orientation] = defaultStates() // in memory only, saved once the user changes something
        applyStates()
        builtLeft.set(userLeft)
        builtRight.set(userRight)
    }

    private fun maxBottom() = windowHeight - bottomInset

    /** very rough layout, only used if the emoji palette is shown before any keyboard has been built */
    private fun ensureRects() {
        if (!userLeft.isEmpty && !userRight.isEmpty) return
        val bottom = maxBottom()
        val height = (windowHeight * 0.4f).toInt().coerceAtMost(bottom - stripHeight)
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
        resolveOverlap()
    }

    /** the halves never overlap: the left half is always left of the right half, with at least a small gap */
    private fun resolveOverlap() {
        var over = userLeft.right + gap - userRight.left
        if (over <= 0) return
        val moveLeft = min(over, userLeft.left)
        userLeft.offset(-moveLeft, 0)
        over -= moveLeft
        if (over > 0) {
            val moveRight = min(over, windowWidth - userRight.right)
            userRight.offset(moveRight, 0)
            over -= moveRight
        }
        if (over > 0) { // both are at the window edges, so they need to get narrower
            userLeft.right -= over / 2
            userRight.left += over - over / 2
        }
    }

    /** bring [r] to a valid size (minimum key size, window) and move it into the window */
    private fun clampPanel(r: Rect, half: Int) {
        val minW = (if (half == LEFT) minWidthLeft else minWidthRight).coerceAtMost(windowWidth)
        val otherMinW = if (half == LEFT) minWidthRight else minWidthLeft
        val maxW = max(minW, windowWidth - gap - otherMinW) // leave space for the other half
        val minH = minHeightPanel.coerceAtMost(max(1, maxBottom() - stripHeight))
        val w = r.width().coerceIn(minW, maxW)
        val h = r.height().coerceIn(minH, max(minH, maxBottom() - stripHeight))
        val x = r.left.coerceIn(0, max(0, windowWidth - w))
        val y = r.top.coerceIn(stripHeight, max(stripHeight, maxBottom() - h))
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
        val top = min(left.top, right.top)
        if (linked) // only above the right half
            strip.set(right.left, right.top - stripHeight, right.right, right.top)
        else
            strip.set(min(left.left, right.left), top - stripHeight, max(left.right, right.right), top)
        val barHeight = if (stripHeight > 0) stripHeight else (40 * density).toInt()
        val barTop = max(0, left.top - barHeight)
        opacityBar.set(left.left, barTop, left.right, left.top.coerceAtLeast(barTop))
    }

    /** the area that should receive touches, everything else goes to the app below */
    @JvmStatic
    fun fillTouchableRegion(region: Region, includeStrip: Boolean) {
        region.set(left)
        region.union(right)
        if (includeStrip && stripHeight > 0)
            region.union(strip)
        if (opacityBarVisible)
            region.union(opacityBar)
    }

    /** The key (hit) area of a panel: below the handle and inside the inner padding. */
    @JvmStatic
    fun keyArea(panel: Rect) = Rect(
        panel.left + innerPadding, panel.top + handleHeight,
        panel.right - innerPadding, panel.bottom - innerPadding
    )

    /** part of the keyboard cache key, so a different window size or layout version results in a new keyboard */
    @JvmStatic
    fun cacheKey(): Int = (31 * (31 * (31 * windowWidth + windowHeight) + bottomInset) + version) or 1

    // ---- drag and resize ----

    @JvmStatic
    fun beginInteraction(half: Int) {
        startRect.set(if (half == LEFT) userLeft else userRight)
        startRectOther.set(if (half == LEFT) userRight else userLeft)
    }

    /**
     * Move ([resize] false) or resize one half. [dx] and [dy] are relative to the position at [beginInteraction].
     * Resizing moves the outer bottom corner (bottom left for the left half, bottom right for the right half),
     * the opposite corner stays where it is.
     */
    @JvmStatic
    fun interact(half: Int, resize: Boolean, dx: Float, dy: Float) {
        val target = if (half == LEFT) userLeft else userRight
        val r = Rect(startRect)
        val minW = (if (half == LEFT) minWidthLeft else minWidthRight).coerceAtMost(windowWidth)
        val minH = minHeightPanel.coerceAtMost(max(1, maxBottom() - stripHeight))
        if (!resize && linked && half == LEFT) {
            // linked: only vertical, and the right half moves along (without leaving the window either)
            val minDy = max(stripHeight - startRect.top, stripHeight - startRectOther.top)
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
                (startRect.top + dy).toInt().coerceIn(stripHeight, max(stripHeight, maxBottom() - startRect.height()))
            )
        } else {
            r.bottom = (startRect.bottom + dy).toInt().coerceIn(min(startRect.top + minH, maxBottom()), maxBottom())
            if (half == LEFT) r.left = (startRect.left + dx).toInt().coerceIn(0, max(0, startRect.right - minW))
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
    fun emojiSpacerRelativeWidth() = EMOJI_GAP_FRACTION

    private fun computeEmojiRects() {
        val pad = innerPadding
        val bottom = max(userLeft.bottom, userRight.bottom)
        val x0 = userLeft.left
        val x1 = userRight.right
        val availW = max(1, x1 - x0 - 2 * pad)
        val availH = max(1, bottom - stripHeight - handleHeight - 2 * pad)
        val s = min(availW / emojiDesignWidth.toFloat(), availH / emojiDesignHeight.toFloat())
        emojiScale = s
        val contentW = emojiDesignWidth * s
        val contentH = emojiDesignHeight * s
        val gapDesign = if (emojiGapDesign > 0f) emojiGapDesign else emojiDesignWidth * EMOJI_GAP_FRACTION
        val gapPx = gapDesign * s
        val startX = x0 + (x1 - x0 - contentW) / 2
        val contentTop = bottom - pad - contentH
        val panelTop = contentTop - handleHeight
        emojiContent.set(startX.toInt(), contentTop.toInt(), (startX + contentW).toInt(), (contentTop + contentH).toInt())
        left.set((startX - pad).toInt(), panelTop.toInt(), (startX + (contentW - gapPx) / 2 + pad).toInt(), bottom)
        right.set((startX + (contentW + gapPx) / 2 - pad).toInt(), panelTop.toInt(), (startX + contentW + pad).toInt(), bottom)
    }
}
