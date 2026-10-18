// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
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
 * and the keys are drawn inside two panels. The panels, their drag handle strips and width grips, and the strip are
 * placed according to [HalvesLayout]. Dragging a handle moves a half, dragging a grip (inner bottom corner) changes its width. While dragging, the panel,
 * handle and grip views follow the finger and the keys are drawn at the new position ([HalvesLayout.previewMatrixFor]).
 * When the finger is lifted the layout is saved and the keyboard is rebuilt, so key positions (hit boxes and the native
 * proximity info used for typing and gestures) match again.
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
    // width grips in the inner bottom corners: bottom right of the left panel, bottom left of the right panel
    private lateinit var gripLeft: ImageView
    private lateinit var gripRight: ImageView
    // opacity button of the halves (top row of the left half) and of the standard floating keyboard (its bottom row)
    private lateinit var opacityButtonHalves: ImageView
    private lateinit var opacityButtonFloating: View
    private lateinit var opacityBar: View
    private lateinit var opacitySeekBar: SeekBar
    private lateinit var opacityValue: TextView
    private val tmpRect = Rect()
    private val halvesViews get() = listOf(panelLeft, panelRight, handleLeft, handleRight, gripLeft, gripRight, opacityButtonHalves)

    private val panelOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, HalvesLayout.cornerRadius.toFloat())
        }
    }

    // The toolbar belongs to the keyboard: it has the rounded upper corners and square lower corners. In linked mode it is inside of
    // the top area of the right half, so toolbar and half look like one shape.
    // upper right corner only: the left side of the toolbar is next to the drag handle of the right half
    private val topRightRoundedOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = HalvesLayout.cornerRadius
            outline.setRoundRect(-r, 0, view.width, view.height + r, r.toFloat())
        }
    }
    private val topRoundedOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            // the bottom corners are outside of the view, so they are cut off
            outline.setRoundRect(0, 0, view.width, view.height + HalvesLayout.cornerRadius, HalvesLayout.cornerRadius.toFloat())
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
        opacityButtonHalves = findViewById(R.id.halves_opacity_button)
        opacityButtonFloating = findViewById(R.id.floating_opacity_button)
        opacityBar = findViewById(R.id.halves_opacity_bar)
        opacitySeekBar = findViewById(R.id.halves_opacity_seekbar)
        opacityValue = findViewById(R.id.halves_opacity_value)
        setChildrenDrawingOrderEnabled(true)
        setTouchHandling(handleLeft, HalvesLayout.LEFT, false)
        setTouchHandling(handleRight, HalvesLayout.RIGHT, false)
        setTouchHandling(gripLeft, HalvesLayout.LEFT, true)
        setTouchHandling(gripRight, HalvesLayout.RIGHT, true)
        setupOpacitySlider()
    }

    private fun setupOpacitySlider() {
        opacityButtonHalves.setOnClickListener { toggleOpacityBar() }
        opacityButtonFloating.setOnClickListener { toggleOpacityBar() }
        opacitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // never completely transparent: the slider starts at the minimum opacity
                HalvesLayout.setOpacity(context, HalvesLayout.MIN_OPACITY + progress / 100f, false)
                applyOpacity()
                restartAutoHide()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { restartAutoHide() }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                HalvesLayout.setOpacity(context, HalvesLayout.opacity, true)
                restartAutoHide()
            }
        })
    }

    private val autoHideOpacityBar = Runnable { setOpacityBarVisible(false) }

    /** the slider hides itself when it is not used for a while */
    private fun restartAutoHide() {
        removeCallbacks(autoHideOpacityBar)
        if (opacityBar.visibility == VISIBLE) postDelayed(autoHideOpacityBar, OPACITY_BAR_TIMEOUT_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(autoHideOpacityBar)
        super.onDetachedFromWindow()
    }

    /** opacity button: show or hide the slider */
    fun toggleOpacityBar() = setOpacityBarVisible(opacityBar.visibility != VISIBLE)

    /** The opacity can be changed for the floating halves and the standard floating keyboard, the docked keyboard is never see-through. */
    private fun opacityAvailable() = halvesMode || Settings.getValues().mIsFloatingKeyboard

    /**
     * Show or hide the opacity slider. With floating halves it replaces the drag handle of the left half (the opacity button
     * stays at the end of that row); in the standard floating keyboard it replaces the resize and drag handles in the bottom row
     * (next to the opacity button).
     */
    fun setOpacityBarVisible(visible: Boolean) {
        HalvesLayout.loadOpacity(context)
        val show = visible && opacityAvailable()
        val floating = !halvesMode
        val parent: ViewGroup = if (floating) floatingHandleContainer as ViewGroup else this
        if (opacityBar.parent !== parent) {
            (opacityBar.parent as? ViewGroup)?.removeView(opacityBar)
            if (floating) {
                val height = resources.getDimensionPixelSize(R.dimen.config_floating_handle_height)
                parent.addView(opacityBar, 0, LinearLayout.LayoutParams(0, height, 2f))
            } else addView(opacityBar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        // The slider leaves room for the button at the end of its row (halves). A touch anywhere else hides it, so the whole window
        // has to be touchable while it is shown (see HalvesLayout.fillTouchableRegion and LatinIME.onComputeInsets).
        val padding = (8 * resources.displayMetrics.density).toInt()
        opacityBar.setPaddingRelative(padding, 0, if (halvesMode) HalvesLayout.handleHeight + padding else padding, 0)
        HalvesLayout.opacityBarVisible = show
        opacityBar.visibility = if (show) VISIBLE else GONE
        // with floating halves the slider is in the place of the drag handle of the left half (and has its upper corners)
        handleLeft.visibility = if (halvesMode && !show) VISIBLE else GONE
        if (floating) {
            floatingHandleContainer.findViewById<View>(R.id.resize_handle)?.visibility = if (show) GONE else VISIBLE
            floatingHandleContainer.findViewById<View>(R.id.drag_handle)?.visibility = if (show) GONE else VISIBLE
        }
        opacityBar.outlineProvider = if (halvesMode) topRoundedOutline else ViewOutlineProvider.BACKGROUND
        opacityBar.clipToOutline = halvesMode
        opacityBar.invalidateOutline()
        if (show) {
            val colors = Settings.getValues().mColors
            colors.setBackground(opacityBar, ColorType.STRIP_BACKGROUND)
            val textColor = colors.get(ColorType.KEY_TEXT)
            opacityValue.setTextColor(textColor)
            opacitySeekBar.progressTintList = ColorStateList.valueOf(textColor)
            opacitySeekBar.thumbTintList = ColorStateList.valueOf(textColor)
            opacitySeekBar.progress = ((HalvesLayout.opacity - HalvesLayout.MIN_OPACITY) * 100).toInt()
        }
        // touches outside of the keyboard (not in this frame) also have to hide the slider in the standard floating keyboard
        (this.parent as? InputView)?.setOutsideTouchDismiss(show && floating)
        restartAutoHide()
        applyOpacity()
        requestLayout() // also updates the touchable region
    }

    /**
     * The keyboard (panels / background, keys, handles and grips) and the toolbar / suggestion / emoji category strip are
     * see-through, but never invisible.
     * Floating halves and the standard floating keyboard use the saved opacity (the same one), the docked keyboard is never see-through.
     */
    fun applyOpacity() {
        HalvesLayout.loadOpacity(context)
        val alpha = if (opacityAvailable()) HalvesLayout.opacity else 1f
        if (halvesMode)
            listOf(panelLeft, panelRight, wrapper, handleLeft, handleRight, gripLeft, gripRight, opacityButtonHalves).forEach { it.alpha = alpha }
        else {
            listOf(panelLeft, panelRight, handleLeft, handleRight, gripLeft, gripRight).forEach { it.alpha = 1f }
            wrapper.alpha = alpha
            background?.mutate()?.alpha = (alpha * 255).toInt()
        }
        // The toolbar, suggestions, emoji categories and clipboard strip are in the strip container: fade each of its views.
        (strip as? ViewGroup)?.let { container ->
            for (i in 0 until container.childCount) container.getChildAt(i).alpha = alpha
        }
        opacityValue.text = "${(alpha * 100).toInt()}%"
    }

    /** a touch anywhere except on the slider or on an opacity button hides the slider; the touch itself is handled as usual (e.g. a key press) */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (opacityBar.visibility == VISIBLE && (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
            val frameLocation = IntArray(2)
            getLocationOnScreen(frameLocation)
            val x = frameLocation[0] + ev.getX(ev.actionIndex)
            val y = frameLocation[1] + ev.getY(ev.actionIndex)
            if (listOf(opacityBar, opacityButtonHalves, opacityButtonFloating).none { it.isVisible && containsOnScreen(it, x, y) })
                setOpacityBarVisible(false)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun containsOnScreen(view: View, x: Float, y: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return x >= location[0] && x < location[0] + view.width && y >= location[1] && y < location[1] + view.height
    }

    private var interactionStartX = 0f
    private var interactionStartY = 0f
    private var interacting = false

    /**
     * Handles are views above the keyboard view, so a finger that goes down on a handle or grip never reaches the keyboard
     * (and keeps being a drag), while a finger that went down on a key never reaches a handle (so swipe typing is not disturbed).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setTouchHandling(view: View, half: Int, resize: Boolean) {
        view.setOnTouchListener { v, event ->
            // the emoji palette can only be moved vertically (both halves together), it can't be resized
            if (HalvesLayout.emojiMode && resize) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (interacting) return@setOnTouchListener false // only one drag / resize at a time
                    interacting = true
                    interactionStartX = event.rawX
                    interactionStartY = event.rawY
                    HalvesLayout.beginInteraction(half)
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!interacting) return@setOnTouchListener false
                    HalvesLayout.interact(half, resize, event.rawX - interactionStartX, event.rawY - interactionStartY)
                    // layout moves panels, handles, grips and strip, and also triggers an update of the touchable region
                    requestLayout()
                    if (HalvesLayout.emojiMode) KeyboardSwitcher.getInstance().updateEmojiHalvesPlacement()
                    else findViewById<MainKeyboardView>(R.id.keyboard_view)?.invalidateAllKeys()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!interacting) return@setOnTouchListener false
                    interacting = false
                    HalvesLayout.endInteraction(context)
                    requestLayout()
                    // rebuild key geometry and proximity info for the new positions
                    KeyboardSwitcher.getInstance().reloadKeyboard()
                    true
                }
                else -> false
            }
        }
    }

    /** grips are hidden while the emoji palette is shown, because the layout can't be changed there */
    fun updateEmojiMode() {
        val visibility = if (halvesMode && !HalvesLayout.emojiMode) VISIBLE else GONE
        gripLeft.visibility = visibility
        gripRight.visibility = visibility
        // linked: only the left handle moves the halves (both vertically), the right half has no handle
        handleRight.visibility = if (halvesMode && !HalvesLayout.linked) VISIBLE else GONE
        // the opacity slider takes the place of the left handle while it is shown
        handleLeft.visibility = if (halvesMode && opacityBar.visibility != VISIBLE) VISIBLE else GONE
        strip.outlineProvider = if (!halvesMode) ViewOutlineProvider.BACKGROUND
            else if (HalvesLayout.linked) topRoundedOutline else topRightRoundedOutline
        strip.clipToOutline = halvesMode
        strip.invalidateOutline()
        applyOpacity()
        requestLayout()
    }

    fun applyHalvesMode(enabled: Boolean) {
        if (enabled == halvesMode) {
            if (enabled) updateEmojiMode() // geometry may have changed
            return
        }
        halvesMode = enabled
        if (opacityBar.visibility == VISIBLE) setOpacityBarVisible(false) // it belongs to the old layout
        halvesViews.forEach { it.visibility = if (enabled) VISIBLE else GONE }
        floatingHandleContainer.visibility = GONE
        if (enabled) {
            background = null // the gap between the halves must stay transparent
            setupHalvesViews()
        } else {
            Settings.getValues().mColors.setBackground(this, ColorType.MAIN_BACKGROUND)
        }
        updateEmojiMode()
    }

    private fun setupHalvesViews() {
        val colors = Settings.getValues().mColors
        listOf(panelLeft, panelRight).forEach { panel ->
            // MAIN_BACKGROUND may be a bitmap that needs the view size, so apply it once the panel has been laid out
            panel.outlineProvider = panelOutline
            panel.clipToOutline = true
            panel.doOnLayout { colors.setBackground(panel, ColorType.MAIN_BACKGROUND) }
        }
        listOf(handleLeft, handleRight, gripLeft, gripRight, opacityButtonHalves).forEach { colors.setColor(it, ColorType.ONE_HANDED_MODE_BUTTON) }
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
        HalvesLayout.handleRect(HalvesLayout.LEFT, tmpRect)
        measureExactly(handleLeft, tmpRect.width(), tmpRect.height())
        HalvesLayout.handleRect(HalvesLayout.RIGHT, tmpRect)
        measureExactly(handleRight, tmpRect.width(), tmpRect.height())
        measureExactly(gripLeft, HalvesLayout.gripSize, HalvesLayout.gripSize)
        measureExactly(gripRight, HalvesLayout.gripSize, HalvesLayout.gripSize)
        measureExactly(opacityButtonHalves, HalvesLayout.handleHeight, HalvesLayout.handleHeight)
        if (opacityBar.visibility != GONE && opacityBar.parent === this)
            measureExactly(opacityBar, HalvesLayout.opacityBar.width(), HalvesLayout.opacityBar.height())
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
        HalvesLayout.handleRect(HalvesLayout.LEFT, tmpRect)
        handleLeft.layout(tmpRect)
        HalvesLayout.handleRect(HalvesLayout.RIGHT, tmpRect)
        handleRight.layout(tmpRect)
        // width grips in the inner bottom corners: bottom right for the left panel, bottom left for the right panel
        val grip = HalvesLayout.gripSize
        gripLeft.layout(HalvesLayout.left.right - grip, HalvesLayout.left.bottom - grip, HalvesLayout.left.right, HalvesLayout.left.bottom)
        gripRight.layout(HalvesLayout.right.left, HalvesLayout.right.bottom - grip, HalvesLayout.right.left + grip, HalvesLayout.right.bottom)
        HalvesLayout.opacityButtonRect(tmpRect)
        opacityButtonHalves.layout(tmpRect)
        if (opacityBar.visibility != GONE && opacityBar.parent === this) opacityBar.layout(HalvesLayout.opacityBar)
        floatingHandleContainer.layout(0, 0, 0, 0)
    }

    private fun View.layout(rect: Rect) = layout(rect.left, rect.top, rect.right, rect.bottom)

    // The keyboard view fills the whole frame, so the strip must be on top of it to receive touches (drawing order is also touch order).
    // Handles and grips are after the wrapper in the layout, so they are above the keyboard view as well.
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        if (!halvesMode || !::strip.isInitialized) return drawingPosition
        // strip, opacity bar and the opacity button are drawn last (and get touches first), the other children keep their order
        val last = listOf(indexOfChild(strip), indexOfChild(opacityBar), indexOfChild(opacityButtonHalves)).filter { it >= 0 }
        val rest = (0 until childCount).filter { it !in last }
        return if (drawingPosition < rest.size) rest[drawingPosition] else last[drawingPosition - rest.size]
    }

    private companion object {
        /** the opacity slider hides itself after this time without being used */
        const val OPACITY_BAR_TIMEOUT_MS = 4000L
    }
}
