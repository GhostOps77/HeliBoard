/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.internal

import android.content.Context
import android.content.res.Resources
import android.graphics.RectF
import android.util.Xml
import androidx.annotation.XmlRes
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Key.KeyParams
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParser
import helium314.keyboard.keyboard.internal.keyboard_parser.KeyboardParser
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.sumOf
import java.util.Collections
import java.util.IdentityHashMap
import org.xmlpull.v1.XmlPullParser

open class KeyboardBuilder<KP : KeyboardParams>(protected val mContext: Context, @JvmField val mParams: KP) {
    @JvmField
    protected val mResources: Resources
    private var mCurrentY = 0

    private var mLeftEdge = false
    private var mTopEdge = false
    private var mRightEdgeKey: Key? = null
    private lateinit var keysInRows: ArrayList<ArrayList<KeyParams>>

    init {
        val res = mContext.resources
        mResources = res
        mParams.GRID_WIDTH = res.getInteger(R.integer.config_keyboard_grid_width)
        mParams.GRID_HEIGHT = res.getInteger(R.integer.config_keyboard_grid_height)
    }

    fun load(id: KeyboardId): KeyboardBuilder<KP> {
        mParams.mId = id
        if (id.element.isEmojiLayout) {
            mParams.mAllowRedundantPopupKeys = true
            readAttributes(R.xml.kbd_emoji)
            keysInRows = EmojiParser(mParams, mContext).parse()
        } else {
            try {
                setupParams()
                keysInRows = KeyboardParser(mParams, mContext).parseLayout()
                if (keysInRows.size != 4) // that was effectively the default for OpenBoard
                    mParams.mTouchPositionCorrection.load(mContext.resources.getStringArray(R.array.touch_position_correction_data_default))
                determineAbsoluteValues()
            } catch (e: Exception) {
                Log.e(TAG, "error parsing layout $id ${id.element}", e)
                throw e
            }
        }
        return this
    }

    private fun setupParams() {
        val sv = Settings.getValues()
        mParams.mAllowRedundantPopupKeys = !sv.mRemoveRedundantPopups
        mParams.mProximityCharsCorrectionEnabled = mParams.mId.element == KeyboardElement.ALPHABET
                || (mParams.mId.element.isAlphabet && !mParams.mId.subtype.hasExtraValue(Constants.Subtype.ExtraValue.NO_SHIFT_PROXIMITY_CORRECTION))

        LocaleKeyboardInfos.addLocaleKeyTextsToParams(mContext, mParams, sv.mShowMorePopupKeys)
        mParams.mPopupKeyOrder.addAll(sv.mPopupKeyOrder)
        // add label source only if popup key type enabled
        sv.mPopupKeyHintOrder.forEach { if (it in sv.mPopupKeyOrder) mParams.mPopupKeyHintOrder.add(it) }
    }

    // todo: remnant of old parser, replace it if reasonably simple
    protected fun readAttributes(@XmlRes xmlId: Int) {
        val parser = mResources.getXml(xmlId)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val event = parser.next()
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name
                if ("Keyboard" == tag) {
                    mParams.readAttributes(mContext, Xml.asAttributeSet(parser))
                    return
                }
            }
        }
        mParams.readAttributes(mContext, null)
    }

    fun disableTouchPositionCorrectionDataForTest() {
        mParams.mTouchPositionCorrection.setEnabled(false)
    }

    fun setProximityCharsCorrectionEnabled(enabled: Boolean) {
        mParams.mProximityCharsCorrectionEnabled = enabled
    }

    open fun build(): Keyboard {
        if (mParams.mId.isFloatingHalves && mParams.mId.element in KeyboardElement.ALPHABET..KeyboardElement.SYMBOLS_SHIFTED) {
            // two independent floating halves: split the rows like in split mode, then place both blocks in the window
            // same spacer as the docked keyboard has with the user's settings, so the keys get the same size
            addSplit(HalvesLayout.splitSpacerRelativeWidth())
            buildFloatingHalves()
            return Keyboard(mParams)
        }
        if (mParams.mId.isSplitLayout
                && mParams.mId.element in KeyboardElement.ALPHABET..KeyboardElement.SYMBOLS_SHIFTED) {
            addSplit(Settings.getValues().mSplitKeyboardSpacerRelativeWidth)
        } else if (mParams.mId.isFloatingHalves && mParams.mId.element == KeyboardElement.EMOJI_BOTTOM_ROW) {
            // the emoji palette is shown over the two halves, with a gap in the middle (see HalvesLayout.setEmojiMode)
            addSplit(HalvesLayout.emojiSpacerRelativeWidth())
            // the emoji palette is placed around the gaps of its grid and of this bottom row (HalvesLayout.setEmojiMode)
            HalvesLayout.publishEmojiBottomGap(keysInRows.flatten().filter { it.isSpacer }.maxOfOrNull { it.mAbsoluteWidth } ?: 0f)
        }
        addKeysToParams()
        return Keyboard(mParams)
    }

    // determine key size and positions using relative width and height
    private fun determineAbsoluteValues() {
        var currentY = mParams.mTopPadding.toFloat()
        for (row in keysInRows) {
            if (row.isEmpty()) continue
            var currentX = mParams.mLeftPadding.toFloat()
            row.forEach {
                it.setAbsoluteDimensions(currentX, currentY)
                if (DebugFlags.DEBUG_ENABLED)
                    Log.d(TAG, "setting size and position for ${it.mLabel ?: it.mIconName}, ${it.mCode}: x ${currentX.toInt()}, w ${it.mAbsoluteWidth.toInt()}")
                currentX += it.mAbsoluteWidth
            }
            currentY += row.first().mAbsoluteHeight
        }
    }

    private fun addSplit(spacerRelativeWidth: Float) {
        // adjust gaps for the whole keyboard, so it's the same for all rows
        mParams.mRelativeHorizontalGap *= 1f / (1f + spacerRelativeWidth)
        mParams.mHorizontalGap = (mParams.mRelativeHorizontalGap * mParams.mId.width).toInt()
        var maxWidthBeforeSpacer = 0f
        var maxWidthAfterSpacer = 0f
        for (row in keysInRows) {
            val y = row.first().yPos // all have the same y, so this is fine
            val relativeWidthSum = row.sumOf { it.mWidth } // sum up relative widths
            val spacer = KeyParams.newSpacer(mParams, spacerRelativeWidth)
            // insert spacer before first key that starts right of the center (also consider gap)
            var insertIndex = row.indexOfFirst { it.xPos + it.mAbsoluteWidth / 3 > mParams.mOccupiedWidth / 2 }
                .takeIf { it > -1 } ?: (row.size / 2) // fallback should never be needed, but better than having an error
            val indexOfProperSpace = row.indexOfFirst { key ->
                // should work reasonably with customizable layouts, where space key might be completely different:
                // "normal" width space keys are ignored, and the possibility of space being first in row is considered
                key.mCode == Constants.CODE_SPACE && key.mWidth > row.first { !it.isSpacer && it.mCode != Constants.CODE_SPACE }.mWidth * 1.5f
            }
            if (indexOfProperSpace >= 0) {
                val spaceLeft = row[indexOfProperSpace]
                reduceSymbolAndActionKeyWidth(row)
                insertIndex = row.indexOf(spaceLeft) + 1
                val widthBeforeSpace = row.subList(0, insertIndex - 1).sumOf { it.mWidth }
                val widthAfterSpace = row.subList(insertIndex, row.size).sumOf { it.mWidth }
                val spaceLeftWidth = (maxWidthBeforeSpacer - widthBeforeSpace).coerceAtLeast(mParams.mDefaultKeyWidth)
                val spaceRightWidth = (maxWidthAfterSpacer - widthAfterSpace).coerceAtLeast(mParams.mDefaultKeyWidth)
                val spacerWidth = spaceLeft.mWidth + spacerRelativeWidth - spaceLeftWidth - spaceRightWidth
                if (spacerWidth > 0.05f) {
                    // only insert if the spacer has a reasonable width
                    val spaceRight = KeyParams(spaceLeft)
                    spaceLeft.mWidth = spaceLeftWidth
                    spaceRight.mWidth = spaceRightWidth
                    spacer.mWidth = spacerWidth
                    row.add(insertIndex, spaceRight)
                    row.add(insertIndex, spacer)
                } else {
                    // otherwise increase space width, so other keys are resized properly
                    spaceLeft.mWidth += spacerWidth
                }
            } else {
                val widthBeforeSpacer = row.subList(0, insertIndex).sumOf { it.mWidth }
                val widthAfterSpacer = row.subList(insertIndex, row.size).sumOf { it.mWidth }
                maxWidthBeforeSpacer = maxWidthBeforeSpacer.coerceAtLeast(widthBeforeSpacer)
                maxWidthAfterSpacer = maxWidthAfterSpacer.coerceAtLeast(widthAfterSpacer)
                row.add(insertIndex, spacer)
            }
            // re-calculate relative widths
            val relativeWidthSumNew = row.sumOf { it.mWidth }
            val widthFactor = relativeWidthSum / relativeWidthSumNew
            // re-calculate absolute sizes and positions
            var currentX = mParams.mLeftPadding.toFloat()
            row.forEach {
                it.mWidth *= widthFactor
                it.setAbsoluteDimensions(currentX, y)
                currentX += it.mAbsoluteWidth
            }
        }
    }

    // reduce width of symbol and action key if in the (to be split) row, and add this width to space to keep other key size constant
    private fun reduceSymbolAndActionKeyWidth(row: ArrayList<KeyParams>) {
        val spaceKey = row.first { it.mCode == Constants.CODE_SPACE }
        if (spaceKey.mWidth >= 0.5) return // only if space key is small
        val symbolKey = row.firstOrNull { it.mCode == KeyCode.SYMBOL_ALPHA }
        val symbolKeyWidth = symbolKey?.mWidth ?: 0f
        if (symbolKeyWidth > mParams.mDefaultKeyWidth) {
            val widthToChange = symbolKey!!.mWidth - mParams.mDefaultKeyWidth
            symbolKey.mWidth -= widthToChange
            spaceKey.mWidth += widthToChange
        }
        val actionKey = row.firstOrNull { it.mBackgroundType == Key.BACKGROUND_TYPE_ACTION }
        val actionKeyWidth = actionKey?.mWidth ?: 0f
        if (actionKeyWidth > mParams.mDefaultKeyWidth * 1.1f) { // allow it to stay a little wider
            val widthToChange = actionKey!!.mWidth - mParams.mDefaultKeyWidth * 1.1f
            actionKey.mWidth -= widthToChange
            spaceKey.mWidth += widthToChange
        }
    }

    /**
     * Floating halves: [addSplit] has already inserted a spacer into (nearly) every row.
     * Here we separate the keys into a left and a right block, scale / move each block into the panel
     * of [HalvesLayout], and create the keys. The keyboard then has the size of the whole window, so that
     * both halves share one coordinate space (necessary for gesture typing and for the native proximity info).
     */
    private fun buildFloatingHalves() {
        val spacers = keysInRows.flatMap { row -> row.filter { it.isSpacer } }
        check(spacers.isNotEmpty()) { "can't split layout ${mParams.mId.element} into floating halves" }
        val splitStart = spacers.map { it.xPos }.average().toFloat()
        val splitEnd = splitStart + spacers.map { it.mAbsoluteWidth }.average().toFloat()
        val splitCenter = (splitStart + splitEnd) / 2

        val leftKeys = Collections.newSetFromMap(IdentityHashMap<KeyParams, Boolean>())
        val rightKeys = Collections.newSetFromMap(IdentityHashMap<KeyParams, Boolean>())
        val rows = ArrayList<ArrayList<KeyParams>>()
        for (row in keysInRows) {
            val newRow = ArrayList<KeyParams>()
            val rowHasSpacer = row.any { it.isSpacer }
            for (key in row) {
                if (key.isSpacer) continue // spacers are not needed any more, the gap is part of the window layout
                val start = key.xPos
                val end = key.xPos + key.mAbsoluteWidth
                if (!rowHasSpacer && start < splitCenter - 1f && end > splitCenter + 1f
                        && splitStart - start > 1f && end - splitEnd > 1f) {
                    // wide key (space bar) across the middle of a row without spacer: one copy for each half
                    val l = KeyParams(key)
                    val r = KeyParams(key)
                    l.mAbsoluteWidth = splitStart - start
                    r.xPos = splitEnd
                    r.mAbsoluteWidth = end - splitEnd
                    newRow.add(l); newRow.add(r)
                    leftKeys.add(l); rightKeys.add(r)
                } else {
                    newRow.add(key)
                    if ((start + end) / 2 < splitCenter) leftKeys.add(key) else rightKeys.add(key)
                }
            }
            if (newRow.isNotEmpty()) rows.add(newRow)
        }
        check(leftKeys.isNotEmpty() && rightKeys.isNotEmpty()) { "can't split layout ${mParams.mId.element} into floating halves" }

        val allKeys = rows.flatten()
        val yMin = allKeys.minOf { it.yPos }
        val yMax = allKeys.maxOf { it.yPos + it.mAbsoluteHeight }
        fun blockOf(keys: Set<KeyParams>) = RectF(
            keys.minOf { it.xPos }, yMin, keys.maxOf { it.xPos + it.mAbsoluteWidth }, yMax
        )
        val leftBlock = blockOf(leftKeys)
        val rightBlock = blockOf(rightKeys)
        HalvesLayout.layoutForBuild(
            mParams.mBaseWidth, mParams.mBaseHeight, HalvesLayout.splitSpacerRelativeWidth(), mParams.mLeftPadding, mParams.mRightPadding,
            leftBlock.width(), rightBlock.width(), leftBlock.height()
        )

        // map key block (design coordinates of the normal keyboard) to the key area of its panel
        fun transform(keys: Set<KeyParams>, block: RectF, panel: android.graphics.Rect) {
            val area = HalvesLayout.keyArea(panel)
            // relative to the docked key area (not to this block): with the default panel size, keys have exactly the docked size
            val sx = area.width() / HalvesLayout.refKeyAreaWidth.toFloat()
            val sy = area.height() / HalvesLayout.refKeyAreaHeight.toFloat()
            for (key in keys) {
                key.xPos = area.left + (key.xPos - block.left) * sx
                key.yPos = area.top + (key.yPos - block.top) * sy
                key.mAbsoluteWidth *= sx
                key.mAbsoluteHeight *= sy
            }
        }
        // not HalvesLayout.left / right: those are different while the emoji palette is shown, the keys must match the user's layout
        val leftPanel = android.graphics.Rect(HalvesLayout.builtLeft)
        val rightPanel = android.graphics.Rect(HalvesLayout.builtRight)
        transform(leftKeys, leftBlock, leftPanel)
        transform(rightKeys, rightBlock, rightPanel)

        // create keys; extend hit boxes of edge keys to the edge of their own panel, but never beyond it
        // (keys read mOccupiedWidth for their gap, so this must happen before it is changed to the window size)
        for ((rowIndex, row) in rows.withIndex()) {
            for (isLeft in booleanArrayOf(true, false)) {
                val sideKeys = row.filter { (if (isLeft) leftKeys else rightKeys).contains(it) }
                val panel = if (isLeft) leftPanel else rightPanel
                sideKeys.forEachIndexed { i, keyParams ->
                    val key = keyParams.createKey()
                    val hitBox = key.hitBox
                    if (i == 0) hitBox.left = panel.left
                    if (i == sideKeys.lastIndex) hitBox.right = panel.right
                    if (rowIndex == 0) hitBox.top = panel.top + HalvesLayout.handleHeight
                    if (rowIndex == rows.lastIndex) hitBox.bottom = panel.bottom
                    mParams.onAddKey(key)
                }
            }
        }
        // the keyboard (and so the keyboard view) is as large as the window
        mParams.mOccupiedWidth = HalvesLayout.windowWidth
        mParams.mOccupiedHeight = HalvesLayout.windowHeight
        endKeyboard()
    }

    private fun startKeyboard() {
        mCurrentY += mParams.mTopPadding
        mTopEdge = true
    }

    private fun startRow() {
        mLeftEdge = true
        mRightEdgeKey = null
    }

    private fun endRow() {
        val rightEdgeKey = mRightEdgeKey
        if (rightEdgeKey != null) {
            rightEdgeKey.markAsRightEdge(mParams)
            mCurrentY += rightEdgeKey.height + rightEdgeKey.verticalGap
            mRightEdgeKey = null
        }
        mLeftEdge = false
        mTopEdge = false
    }

    private fun endKey(key: Key) {
        mParams.onAddKey(key)
        if (mLeftEdge && !key.isSpacer) {
            key.markAsLeftEdge(mParams)
            mLeftEdge = false
        }
        if (mTopEdge) {
            key.markAsTopEdge(mParams)
        }
        if (!key.isSpacer) {
            mRightEdgeKey = key
        }
    }

    private fun endKeyboard() {
        mParams.removeRedundantPopupKeys()
        if (!mParams.mId.element.isEmojiLayout) return
        val actualHeight = mCurrentY - mParams.mVerticalGap + mParams.mBottomPadding
        mParams.mOccupiedHeight = mParams.mOccupiedHeight.coerceAtLeast(actualHeight)
    }

    private fun addKeysToParams() {
        // need to reset it, we need to sum it up to get the height nicely
        // (though in the end we could just not touch it at all, final used value is the same as the one before resetting)
        mCurrentY = 0
        startKeyboard()
        for (row in keysInRows) {
            startRow()
            for (keyParams in row) {
                endKey(keyParams.createKey())
            }
            endRow()
        }
        endKeyboard()
    }

    companion object {
        private const val TAG = "Keyboard.Builder"
    }
}
