// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log

/**
 * Debug help for swipe typing on the floating halves (only active with the debug mode of the debug settings, which is on by default
 * in debug builds): when a swipe ends, shows (and logs) which fingers contributed how many points to the word, and where those
 * points were: in the left half, in the right half, or in between. A swipe with one thumb on each half must show two fingers,
 * both with points in their half, in this one word. It also checks that all keys are inside their panel.
 */
object HalvesGestureReport {
    private const val TAG = "HalvesGesture"

    @JvmStatic
    fun report(pointers: InputPointers) {
        if (!DebugFlags.DEBUG_ENABLED || !Settings.getValues().mIsFloatingHalves) return
        val size = pointers.pointerSize
        if (size <= 0) return
        val ids = pointers.pointerIds
        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates
        // pointer id -> points in the left half, in the right half, elsewhere
        val perFinger = linkedMapOf<Int, IntArray>()
        for (i in 0 until size) {
            val counts = perFinger.getOrPut(ids[i]) { IntArray(3) }
            when {
                HalvesLayout.builtLeft.contains(xs[i], ys[i]) -> counts[0]++
                HalvesLayout.builtRight.contains(xs[i], ys[i]) -> counts[1]++
                else -> counts[2]++
            }
        }
        val fingers = perFinger.entries.joinToString("; ") { (id, c) -> "finger $id: left ${c[0]}, right ${c[1]}, between ${c[2]}" }
        val outside = keysOutsideTheirPanel()
        val text = "swipe: ${perFinger.size} finger(s), $size points ($fingers)" + if (outside > 0) ", $outside keys outside their panel!" else ""
        Log.d(TAG, text)
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    /** number of keys that don't fit in the panel of their half (should be 0) */
    private fun keysOutsideTheirPanel(): Int {
        val keyboard = KeyboardSwitcher.getInstance().mainKeyboardView?.keyboard ?: return 0
        if (HalvesLayout.builtLeft.isEmpty || HalvesLayout.builtRight.isEmpty) return 0
        return keyboard.sortedKeys.count { key ->
            val centerX = key.x + key.width / 2
            val centerY = key.y + key.height / 2
            val panel = when {
                HalvesLayout.builtLeft.contains(centerX, centerY) -> HalvesLayout.builtLeft
                HalvesLayout.builtRight.contains(centerX, centerY) -> HalvesLayout.builtRight
                else -> return@count true
            }
            key.x < panel.left - 1 || key.y < panel.top - 1 || key.x + key.width > panel.right + 1 || key.y + key.height > panel.bottom + 1
        }
    }
}
