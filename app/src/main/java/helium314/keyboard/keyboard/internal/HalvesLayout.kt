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
 *  - user rects ([userLeft], [userRight]): what the user chose, saved per orientation (as fractions of the window)
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

    // layouts as fractions of the window: [orientation][left, right]; null if nothing saved and no default calculated yet
    private val fractions = arrayOfNulls<Array<RectF>>(2)
    private var loadedFromPrefs = booleanArrayOf(false, false)

    // ---- emoji mode: the emoji palette is one view, shown scaled in the space of the two panels ----
    @JvmField var emojiMode = false
    @JvmField val emojiContent = Rect()
    @JvmField var emojiScale = 1f
    private var emojiDesignWidth = 0
    private var emojiDesignHeight = 0
    /** width of the empty columns in the middle of the emoji grid, as measured by the grid keyboard; <= 0 if unknown */
    @JvmField var emojiGapDesign = 0f

    // ---- interaction (drag / resize) ----
    private val startRect = Rect()
    @JvmField var previewActive = false
    private val previewMatrixLeft = Matrix()
    private val previewMatrixRight = Matrix()

    private const val MIN_KEY_SIZE_DP = 44
    /** gap between the emoji halves, relative to the emoji palette width (also used for the empty columns of the emoji grid) */
    private const val EMOJI_GAP_FRACTION = 0.05f
    private const val PREF_RECTS_PORTRAIT = "floating_halves_rects_portrait"
    private const val PREF_RECTS_LANDSCAPE = "floating_halves_rects_landscape"
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
        if (!loadedFromPrefs[orientation]) loadFractions(context)
        applyFractions()
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    // ---- persistence ----

    private fun prefKey() = if (landscape) PREF_RECTS_LANDSCAPE else PREF_RECTS_PORTRAIT

    private fun loadFractions(context: Context) {
        loadedFromPrefs[orientation] = true
        val values = context.prefs().getString(prefKey(), null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        if (values == null || values.size != 8 || values.any { it < 0f || it > 1f }) return
        val l = RectF(values[0], values[1], values[2], values[3])
        val r = RectF(values[4], values[5], values[6], values[7])
        if (l.width() <= 0f || l.height() <= 0f || r.width() <= 0f || r.height() <= 0f) return
        fractions[orientation] = arrayOf(l, r)
    }

    private fun saveFractions(context: Context) {
        val f = fractions[orientation] ?: return
        val s = listOf(f[0].left, f[0].top, f[0].right, f[0].bottom, f[1].left, f[1].top, f[1].right, f[1].bottom).joinToString(",")
        context.prefs().edit { putString(prefKey(), s) }
    }

    /** forget the saved layout of both orientations, defaults are used again with the next keyboard build */
    @JvmStatic
    fun resetSavedLayout(context: Context) {
        context.prefs().edit {
            remove(PREF_RECTS_PORTRAIT)
            remove(PREF_RECTS_LANDSCAPE)
        }
        fractions.fill(null)
        loadedFromPrefs.fill(true) // nothing there any more
        userLeft.setEmpty()
        userRight.setEmpty()
        version++
    }

    private fun applyFractions() {
        val f = fractions[orientation] ?: return
        if (windowWidth <= 0 || windowHeight <= 0) return
        userLeft.set(toPixels(f[0]))
        userRight.set(toPixels(f[1]))
        clampUserRects()
        updateActive()
    }

    private fun toPixels(f: RectF) = Rect(
        (f.left * windowWidth).toInt(), (f.top * windowHeight).toInt(),
        (f.right * windowWidth).toInt(), (f.bottom * windowHeight).toInt()
    )

    private fun toFractions(r: Rect) = RectF(
        r.left / windowWidth.toFloat(), r.top / windowHeight.toFloat(),
        r.right / windowWidth.toFloat(), r.bottom / windowHeight.toFloat()
    )

    private fun storeFractions() {
        fractions[orientation] = arrayOf(toFractions(userLeft), toFractions(userRight))
    }

    // ---- layout when building the keyboard ----

    /**
     * Called by the keyboard builder with the extents of the two key blocks of the normal keyboard
     * ([designLeft], [designRight], in pixels) and the typical key size ([keyWidth], [keyHeight]).
     * Calculates minimum panel sizes, uses default positions if nothing was saved, and clamps the layout.
     */
    @JvmStatic
    fun layoutForBuild(designLeft: RectF, designRight: RectF, keyWidth: Float, keyHeight: Float) {
        val minKey = MIN_KEY_SIZE_DP * density
        val scaleX = if (keyWidth > 0f) minKey / keyWidth else 1f
        val scaleY = if (keyHeight > 0f) minKey / keyHeight else 1f
        val maxPanelWidth = windowWidth / 2 - gap / 2
        // on narrow screens both halves can't have keys of the minimum size, the panels are then limited to half of the screen
        minWidthLeft = min((designLeft.width() * scaleX).toInt() + 2 * innerPadding, maxPanelWidth)
        minWidthRight = min((designRight.width() * scaleX).toInt() + 2 * innerPadding, maxPanelWidth)
        minHeightPanel = min((designLeft.height() * scaleY).toInt() + handleHeight + 2 * innerPadding, maxBottom() - stripHeight)

        if (fractions[orientation] == null) {
            // defaults: each half at its side of the window, bottom aligned, with the size of the keys in a normal keyboard
            val bottom = maxBottom()
            val panelHeight = min(designLeft.height().toInt() + handleHeight + 2 * innerPadding, bottom - stripHeight)
            val leftWidth = min(designLeft.width().toInt() + 2 * innerPadding, maxPanelWidth)
            val rightWidth = min(designRight.width().toInt() + 2 * innerPadding, maxPanelWidth)
            userLeft.set(0, bottom - panelHeight, leftWidth, bottom)
            userRight.set(windowWidth - rightWidth, bottom - panelHeight, windowWidth, bottom)
            storeFractions() // in memory only, saved once the user changes something
        }
        applyFractions()
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
    }

    /** bring [r] to a valid size (minimum key size, window) and move it into the window */
    private fun clampPanel(r: Rect, half: Int) {
        val minW = (if (half == LEFT) minWidthLeft else minWidthRight).coerceAtMost(windowWidth)
        val minH = minHeightPanel.coerceAtMost(max(1, maxBottom() - stripHeight))
        val w = r.width().coerceIn(minW, max(minW, windowWidth))
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
        strip.set(min(left.left, right.left), top - stripHeight, max(left.right, right.right), top)
    }

    /** the area that should receive touches, everything else goes to the app below */
    @JvmStatic
    fun fillTouchableRegion(region: Region, includeStrip: Boolean) {
        region.set(left)
        region.union(right)
        if (includeStrip && stripHeight > 0)
            region.union(strip)
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
        if (!resize) {
            r.offsetTo(
                (startRect.left + dx).toInt().coerceIn(0, max(0, windowWidth - startRect.width())),
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
        storeFractions()
        saveFractions(context)
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
