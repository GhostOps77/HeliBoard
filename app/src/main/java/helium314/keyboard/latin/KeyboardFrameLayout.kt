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
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.doOnLayout
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
 * and the keys are drawn inside two panels. The panels, their drag handle strips and resize grips, and the strip are
 * placed according to [HalvesLayout]. Dragging a handle moves a half, dragging a grip resizes it. While dragging, the panel,
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
    private lateinit var gripLeft: ImageView
    private lateinit var gripRight: ImageView
    private lateinit var opacityBar: View
    private lateinit var opacitySeekBar: SeekBar
    private lateinit var opacityValue: TextView
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
        opacityBar = findViewById(R.id.halves_opacity_bar)
        opacitySeekBar = findViewById(R.id.halves_opacity_seekbar)
        opacityValue = findViewById(R.id.halves_opacity_value)
        // the grip icon is drawn for the bottom right corner, the left panel has its grip in the bottom left corner
        gripLeft.scaleX = -1f
        setChildrenDrawingOrderEnabled(true)
        setTouchHandling(handleLeft, HalvesLayout.LEFT, false)
        setTouchHandling(handleRight, HalvesLayout.RIGHT, false)
        setTouchHandling(gripLeft, HalvesLayout.LEFT, true)
        setTouchHandling(gripRight, HalvesLayout.RIGHT, true)
        setupOpacitySlider()
    }

    private fun setupOpacitySlider() {
        opacitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // never completely transparent: the slider starts at the minimum opacity
                HalvesLayout.setOpacity(context, HalvesLayout.MIN_OPACITY + progress / 100f, false)
                applyOpacity()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                HalvesLayout.setOpacity(context, HalvesLayout.opacity, true)
            }
        })
    }

    private var lastOpacityBarDismiss = 0L

    /** toolbar key: show or hide the slider */
    fun toggleOpacityBar() {
        if (opacityBar.visibility == VISIBLE) {
            setOpacityBarVisible(false)
            return
        }
        // A touch outside of the slider hides it when the finger goes down, which is before a click on the toolbar key is
        // completed. Without this check the key would hide the slider and then immediately show it again.
        if (SystemClock.uptimeMillis() - lastOpacityBarDismiss < 600) return
        setOpacityBarVisible(true)
    }

    private fun opacityAvailable() = halvesMode || Settings.getValues().mOpacityEverywhere

    /**
     * Show or hide the opacity slider. With floating halves it is above the left half (where a toolbar would be); in the normal
     * keyboard (if opacity is enabled for all modes) it replaces the content of the toolbar / suggestion strip.
     */
    fun setOpacityBarVisible(visible: Boolean) {
        HalvesLayout.loadOpacity(context)
        val show = visible && opacityAvailable()
        val parent: ViewGroup = if (halvesMode) this else strip as ViewGroup
        if (opacityBar.parent !== parent) {
            (opacityBar.parent as? ViewGroup)?.removeView(opacityBar)
            if (parent === this) addView(opacityBar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            else parent.addView(opacityBar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        HalvesLayout.opacityBarVisible = show && halvesMode // only used for the touchable region of the halves
        opacityBar.visibility = if (show) VISIBLE else GONE
        if (show) {
            val colors = Settings.getValues().mColors
            colors.setBackground(opacityBar, ColorType.STRIP_BACKGROUND)
            val textColor = colors.get(ColorType.KEY_TEXT)
            opacityValue.setTextColor(textColor)
            colors.setColor(opacityBar.findViewById<ImageView>(R.id.halves_opacity_icon), ColorType.KEY_TEXT)
            opacitySeekBar.progressTintList = ColorStateList.valueOf(textColor)
            opacitySeekBar.thumbTintList = ColorStateList.valueOf(textColor)
            opacitySeekBar.progress = ((HalvesLayout.opacity - HalvesLayout.MIN_OPACITY) * 100).toInt()
        }
        applyOpacity()
        requestLayout() // also updates the touchable region
    }

    /**
     * The keyboard (panels / background, keys, handles and grips) is see-through, but never invisible. The toolbar stays opaque.
     * Floating halves always use the saved opacity, the normal keyboard only if the opacity is enabled for all keyboard modes.
     */
    fun applyOpacity() {
        HalvesLayout.loadOpacity(context)
        val alpha = if (opacityAvailable()) HalvesLayout.opacity else 1f
        if (halvesMode)
            listOf(panelLeft, panelRight, wrapper, handleLeft, handleRight, gripLeft, gripRight).forEach { it.alpha = alpha }
        else {
            listOf(panelLeft, panelRight, handleLeft, handleRight, gripLeft, gripRight).forEach { it.alpha = 1f }
            wrapper.alpha = alpha
            background?.mutate()?.alpha = (alpha * 255).toInt()
        }
        opacityValue.text = "${(alpha * 100).toInt()}%"
    }

    /** a touch anywhere except on the slider hides it; the touch itself is handled as usual (e.g. a key press) */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (opacityBar.visibility == VISIBLE && (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
            val frameLocation = IntArray(2)
            val barLocation = IntArray(2)
            getLocationOnScreen(frameLocation)
            opacityBar.getLocationOnScreen(barLocation)
            val x = frameLocation[0] + ev.getX(ev.actionIndex)
            val y = frameLocation[1] + ev.getY(ev.actionIndex)
            val inside = x >= barLocation[0] && x < barLocation[0] + opacityBar.width && y >= barLocation[1] && y < barLocation[1] + opacityBar.height
            if (!inside) {
                lastOpacityBarDismiss = SystemClock.uptimeMillis()
                setOpacityBarVisible(false)
            }
        }
        return super.dispatchTouchEvent(ev)
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
        if (opacityBar.visibility == VISIBLE) setOpacityBarVisible(false)
        applyOpacity()
        requestLayout()
    }

    fun applyHalvesMode(enabled: Boolean) {
        if (enabled == halvesMode) {
            if (enabled) updateEmojiMode() // geometry may have changed
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
        handleLeft.layout(HalvesLayout.left.left, HalvesLayout.left.top, HalvesLayout.left.right, HalvesLayout.left.top + HalvesLayout.handleHeight)
        handleRight.layout(HalvesLayout.right.left, HalvesLayout.right.top, HalvesLayout.right.right, HalvesLayout.right.top + HalvesLayout.handleHeight)
        // outer bottom corners: bottom left for the left panel, bottom right for the right panel
        val grip = HalvesLayout.gripSize
        gripLeft.layout(HalvesLayout.left.left, HalvesLayout.left.bottom - grip, HalvesLayout.left.left + grip, HalvesLayout.left.bottom)
        gripRight.layout(HalvesLayout.right.right - grip, HalvesLayout.right.bottom - grip, HalvesLayout.right.right, HalvesLayout.right.bottom)
        if (opacityBar.visibility != GONE && opacityBar.parent === this) opacityBar.layout(HalvesLayout.opacityBar)
        floatingHandleContainer.layout(0, 0, 0, 0)
    }

    private fun View.layout(rect: Rect) = layout(rect.left, rect.top, rect.right, rect.bottom)

    // The keyboard view fills the whole frame, so the strip must be on top of it to receive touches (drawing order is also touch order).
    // Handles and grips are after the wrapper in the layout, so they are above the keyboard view as well.
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        if (!halvesMode || !::strip.isInitialized) return drawingPosition
        // strip and opacity bar are drawn last (and get touches first), the other children keep their order
        val last = listOf(indexOfChild(strip), indexOfChild(opacityBar)).filter { it >= 0 }
        val rest = (0 until childCount).filter { it !in last }
        return if (drawingPosition < rest.size) rest[drawingPosition] else last[drawingPosition - rest.size]
    }
}
