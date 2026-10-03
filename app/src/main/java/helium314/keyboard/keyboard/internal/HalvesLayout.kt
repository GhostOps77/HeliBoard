// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarMode
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
 */
object HalvesLayout {
    @JvmField val left = Rect()
    @JvmField val right = Rect()
    /** the suggestion strip / toolbar, placed directly above the higher of the two panels */
    @JvmField val strip = Rect()

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

    /** increased whenever the layout changes in a way that needs the keyboard to be rebuilt (part of the keyboard cache key) */
    @JvmField var version = 0

    /**
     * Update metrics that depend on the screen / window. [viewWidth] and [viewHeight] are the size of the
     * input view if it has been laid out already, otherwise 0 and an estimate from the display is used.
     * (If the estimate is wrong, the input view reports its real size and the keyboard is reloaded.)
     */
    @JvmStatic
    fun refreshMetrics(context: Context, viewWidth: Int, viewHeight: Int) {
        val res = context.resources
        val density = res.displayMetrics.density
        handleHeight = res.getDimensionPixelSize(R.dimen.config_floating_handle_height)
        gripSize = (28 * density).toInt()
        innerPadding = (3 * density).toInt()
        cornerRadius = (12 * density).toInt()
        gap = (6 * density).toInt()
        stripHeight = if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
            else res.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        windowWidth = if (viewWidth > 0) viewWidth else ResourceUtils.getDefaultKeyboardWidth(context)
        windowHeight = if (viewHeight > 0) viewHeight else res.displayMetrics.heightPixels - statusBarHeight(context)
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /**
     * Phase 2: fixed positions. Each half sits at its own side of the window, bottom aligned, with the
     * size the keys would have in a normal keyboard. [designLeft] and [designRight] are the extents of the
     * two key blocks in the normal (unsplit, full width) layout, in pixels.
     */
    @JvmStatic
    fun layoutDefaults(designLeft: RectF, designRight: RectF) {
        val bottom = windowHeight - bottomInset
        val maxPanelHeight = max(1, bottom - stripHeight)
        val panelHeight = min(designLeft.height().toInt() + handleHeight + 2 * innerPadding, maxPanelHeight)
        val halfWidth = windowWidth / 2 - gap / 2
        val leftWidth = min(designLeft.width().toInt() + 2 * innerPadding, halfWidth)
        val rightWidth = min(designRight.width().toInt() + 2 * innerPadding, halfWidth)
        left.set(0, bottom - panelHeight, leftWidth, bottom)
        right.set(windowWidth - rightWidth, bottom - panelHeight, windowWidth, bottom)
        updateStrip()
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
}
