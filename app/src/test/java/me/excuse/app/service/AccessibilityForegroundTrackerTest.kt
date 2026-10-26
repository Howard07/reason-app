package me.excuse.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityForegroundTrackerTest {

    private val appA = "com.example.a"
    private val appB = "com.example.b"

    @Test
    fun packageSwitchEmitsEntryEdge() {
        val tracker = AccessibilityForegroundTracker()
        val change = tracker.onWindowForeground(appA, "$appA.Main", at = 1_000L)
        assertEquals(appA, change?.packageName)
        assertTrue(change?.isEntryEdge == true)
        assertEquals(appA, tracker.stickyPackageName)
    }

    @Test
    fun samePackageWindowChurnProducesNoChange() {
        val tracker = AccessibilityForegroundTracker()
        tracker.onWindowForeground(appA, "$appA.Main", at = 1_000L)
        // 应用内跳页、Dialog、PopupWindow：包名不变，一律不输出。
        assertNull(tracker.onWindowForeground(appA, "$appA.Detail", at = 1_100L))
        assertNull(tracker.onWindowForeground(appA, "android.widget.PopupWindow\$1", at = 1_200L))
        assertEquals(appA, tracker.stickyPackageName)
    }

    @Test
    fun homeArrivalEmitsLeave() {
        val tracker = AccessibilityForegroundTracker()
        tracker.onWindowForeground(appA, "$appA.Main", at = 1_000L)
        val leave = tracker.onWindowForeground(null, null, at = 1_100L)
        assertNull(leave?.packageName)
        assertFalse(leave?.isEntryEdge ?: true)
        assertNull(tracker.stickyPackageName)
    }

    @Test
    fun reentryAfterHomeEmitsNewEntryEdge() {
        val tracker = AccessibilityForegroundTracker()
        tracker.onWindowForeground(appA, "$appA.Main", at = 1_000L)
        tracker.onWindowForeground(null, null, at = 1_100L)
        val reentry = tracker.onWindowForeground(appA, "$appA.Main", at = 2_000L)
        // Home 往返之后必须是一次新的 entry edge —— 状态机靠它决定是否重新弹窗。
        assertTrue(reentry?.isEntryEdge == true)
    }

    @Test
    fun switchingBetweenAppsDirectlyEmitsBothFactsInOneChange() {
        val tracker = AccessibilityForegroundTracker()
        tracker.onWindowForeground(appA, "$appA.Main", at = 1_000L)
        val change = tracker.onWindowForeground(appB, "$appB.Main", at = 1_100L)
        // A -> B 不经过 Home：一次 Change 同时是「离开 A」和「进入 B」。
        assertEquals(appB, change?.packageName)
        assertTrue(change?.isEntryEdge == true)
    }

    @Test
    fun forceForegroundSuppressesTheCorrespondingEvent() {
        val tracker = AccessibilityForegroundTracker()
        tracker.onWindowForeground(appA, "$appA.Main", at = 1_000L)
        // 主动 sendHome：先对齐 sticky，launcher 事件到达时不再产生输出。
        tracker.forceForeground(null)
        assertNull(tracker.onWindowForeground(null, null, at = 1_100L))
    }

    @Test
    fun repeatedHomeEventsCollapse() {
        val tracker = AccessibilityForegroundTracker()
        tracker.onWindowForeground(null, null, at = 1_000L)
        assertNull(tracker.onWindowForeground(null, null, at = 1_100L))
    }

    @Test
    fun resetClearsStickySoSamePackageIsFreshAgain() {
        val tracker = AccessibilityForegroundTracker()
        tracker.onWindowForeground(appA, "$appA.Main", at = 1_000L)
        tracker.reset()
        assertNull(tracker.stickyPackageName)
        val change = tracker.onWindowForeground(appA, "$appA.Main", at = 2_000L)
        assertTrue(change?.isEntryEdge == true)
    }
}
