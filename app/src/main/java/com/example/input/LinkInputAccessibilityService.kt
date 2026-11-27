package com.example.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

class LinkInputAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        _serviceConnected.value = true
        Log.i(TAG, "LINK Multi-Control Accessibility receiver connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally passive: LINK does not read screen contents or keystrokes
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        _serviceConnected.value = false
        instanceRef = null
        super.onDestroy()
    }

    fun performNormalizedTap(nx: Float, ny: Float): Boolean {
        val dm = resources.displayMetrics
        val x = (nx.coerceIn(0.01f, 0.99f) * dm.widthPixels)
        val y = (ny.coerceIn(0.01f, 0.99f) * dm.heightPixels)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performNormalizedSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 220L): Boolean {
        val dm = resources.displayMetrics
        val sx = startX.coerceIn(0.01f, 0.99f) * dm.widthPixels
        val sy = startY.coerceIn(0.01f, 0.99f) * dm.heightPixels
        val ex = endX.coerceIn(0.01f, 0.99f) * dm.widthPixels
        val ey = endY.coerceIn(0.01f, 0.99f) * dm.heightPixels
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(80L, 800L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performGlobalNavigation(action: String): Boolean = when (action) {
        "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }

    fun insertTextIntoFocusedField(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        private const val TAG = "LinkInputA11yService"
        private var instanceRef: WeakReference<LinkInputAccessibilityService>? = null

        private val _serviceConnected = MutableStateFlow(false)
        val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

        fun currentInstance(): LinkInputAccessibilityService? = instanceRef?.get()
    }
}
