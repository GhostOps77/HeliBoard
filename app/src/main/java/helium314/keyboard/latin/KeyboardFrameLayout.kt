// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import helium314.keyboard.keyboard.internal.HalvesLayout
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * The frame around suggestion strip and keyboard (main_keyboard_frame).
 *
 * Normally this is just a vertical [LinearLayout], and it behaves exactly like one.
 *
 * In "floating halves" mode ([halvesMode]) the frame is as large as the IME window and has no background of its own
 * (so the app stays visible everywhere). The keyboard wrapper (containing the single keyboard view) fills the whole frame,
 * and the keys are drawn inside two panels. The panels, their drag handle strips and resize grips, and the strip are
 * placed according to [HalvesLayout]. Handles and grips are only drawn here, making them react to touches is done separately.
 */
class KeyboardFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var halvesMode = false
        private set

    private lateinit var strip: View
    private lateinit var wrapper: View
    private lateinit var floatingHandleContainer: View
    private lateinit var panelLeft: View
    private lateinit var panelRight: View
    private lateinit var handleLeft: ImageView
    private lateinit var handleRight: ImageView
    private lateinit var gripLeft: ImageView
    private lateinit var gripRight: ImageView
    private val halvesViews get() = listOf(panelLeft, panelRight, handleLeft, handleRight, gripLeft, gripRight)

    private val panelOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, HalvesLayout.cornerRadius.toFloat())
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        strip = findViewById(R.id.strip_container)
        wrapper = findViewById(R.id.keyboard_view_wrapper)
        floatingHandleContainer = findViewById(R.id.float_handle_container)
        panelLeft = findViewById(R.id.halves_panel_left)
        panelRight = findViewById(R.id.halves_panel_right)
        handleLeft = findViewById(R.id.halves_handle_left)
        handleRight = findViewById(R.id.halves_handle_right)
        gripLeft = findViewById(R.id.halves_grip_left)
        gripRight = findViewById(R.id.halves_grip_right)
        // the grip icon is drawn for the bottom right corner, the left panel has its grip in the bottom left corner
        gripLeft.scaleX = -1f
        setChildrenDrawingOrderEnabled(true)
    }

    fun applyHalvesMode(enabled: Boolean) {
        if (enabled == halvesMode) {
            if (enabled) requestLayout() // geometry may have changed
            return
        }
        halvesMode = enabled
        halvesViews.forEach { it.visibility = if (enabled) VISIBLE else GONE }
        floatingHandleContainer.visibility = GONE
        if (enabled) {
            background = null // the gap between the halves must stay transparent
            setupHalvesViews()
        } else {
            Settings.getValues().mColors.setBackground(this, ColorType.MAIN_BACKGROUND)
        }
        requestLayout()
    }

    private fun setupHalvesViews() {
        val colors = Settings.getValues().mColors
        listOf(panelLeft, panelRight).forEach { panel ->
            // MAIN_BACKGROUND may be a bitmap that needs the view size, so apply it once the panel has been laid out
            panel.outlineProvider = panelOutline
            panel.clipToOutline = true
            panel.doOnLayout { colors.setBackground(panel, ColorType.MAIN_BACKGROUND) }
        }
        listOf(handleLeft, handleRight, gripLeft, gripRight).forEach { colors.setColor(it, ColorType.ONE_HANDED_MODE_BUTTON) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!halvesMode) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        measureExactly(wrapper, width, height)
        if (strip.visibility != GONE)
            measureExactly(strip, HalvesLayout.strip.width(), HalvesLayout.strip.height())
        measureExactly(panelLeft, HalvesLayout.left.width(), HalvesLayout.left.height())
        measureExactly(panelRight, HalvesLayout.right.width(), HalvesLayout.right.height())
        measureExactly(handleLeft, HalvesLayout.left.width(), HalvesLayout.handleHeight)
        measureExactly(handleRight, HalvesLayout.right.width(), HalvesLayout.handleHeight)
        measureExactly(gripLeft, HalvesLayout.gripSize, HalvesLayout.gripSize)
        measureExactly(gripRight, HalvesLayout.gripSize, HalvesLayout.gripSize)
    }

    private fun measureExactly(view: View, width: Int, height: Int) =
        view.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), MeasureSpec.EXACTLY))

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!halvesMode) {
            super.onLayout(changed, l, t, r, b)
            return
        }
        wrapper.layout(0, 0, r - l, b - t)
        if (strip.visibility != GONE) strip.layout(HalvesLayout.strip)
        panelLeft.layout(HalvesLayout.left)
        panelRight.layout(HalvesLayout.right)
        handleLeft.layout(HalvesLayout.left.left, HalvesLayout.left.top, HalvesLayout.left.right, HalvesLayout.left.top + HalvesLayout.handleHeight)
        handleRight.layout(HalvesLayout.right.left, HalvesLayout.right.top, HalvesLayout.right.right, HalvesLayout.right.top + HalvesLayout.handleHeight)
        // outer bottom corners: bottom left for the left panel, bottom right for the right panel
        val grip = HalvesLayout.gripSize
        gripLeft.layout(HalvesLayout.left.left, HalvesLayout.left.bottom - grip, HalvesLayout.left.left + grip, HalvesLayout.left.bottom)
        gripRight.layout(HalvesLayout.right.right - grip, HalvesLayout.right.bottom - grip, HalvesLayout.right.right, HalvesLayout.right.bottom)
        floatingHandleContainer.layout(0, 0, 0, 0)
    }

    private fun View.layout(rect: Rect) = layout(rect.left, rect.top, rect.right, rect.bottom)

    // The keyboard view fills the whole frame, so the strip must be on top of it to receive touches (drawing order is also touch order).
    // Handles and grips are after the wrapper in the layout, so they are above the keyboard view as well.
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        if (!halvesMode || !::strip.isInitialized) return drawingPosition
        val stripIndex = indexOfChild(strip)
        return when {
            drawingPosition == childCount - 1 -> stripIndex
            drawingPosition >= stripIndex -> drawingPosition + 1
            else -> drawingPosition
        }
    }
}
