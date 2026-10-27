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

    @Test
    fun inputMethodWindowIsIgnoredEvenWhenItHasALauncherIcon() {
        // 回归用：搜狗/讯飞/百度/Gboard 在桌面都有图标，「有没有 launcher 入口」
        // 判据会对它们全部失效，必须按系统给的输入法列表忽略。
        val ime = "com.sohu.inputmethod.sogou"
        assertEquals(
            AccessibilityWindowClass.Ignore,
            classify(
                packageName = ime,
                inputMethodPackages = setOf(ime),
                isLaunchable = { true },
            ),
        )
    }

    @Test
    fun nonApplicationWindowIsIgnoredRegardlessOfPackage() {
        // 输入法窗口、系统悬浮窗、壁纸：盖在当前应用之上，不是一次切换。
        assertEquals(
            AccessibilityWindowClass.Ignore,
            classify(
                packageName = appB,
                windowType = AccessibilityWindowInfoType.INPUT_METHOD,
                isLaunchable = { true },
            ),
        )
    }

    @Test
    fun applicationWindowFromTheSamePackageIsAccepted() {
        assertEquals(
            AccessibilityWindowClass.App(appA),
            classify(
                packageName = appA,
                windowType = ACCESSIBILITY_WINDOW_TYPE_APPLICATION,
                isLaunchable = { true },
            ),
        )
    }

    @Test
    fun ownOverlayWindowsAreIgnoredButMainActivityCounts() {
        val own = "me.excuse.app"
        assertEquals(
            AccessibilityWindowClass.Ignore,
            classify(packageName = own, className = "android.widget.FrameLayout", ownPackage = own),
        )
        assertEquals(
            AccessibilityWindowClass.App(own),
            classify(
                packageName = own,
                className = "me.excuse.app.MainActivity",
                ownPackage = own,
            ),
        )
    }

    @Test
    fun homeMapsToLeaveAndNonLaunchablePackagesAreIgnored() {
        assertEquals(
            AccessibilityWindowClass.Home,
            classify(packageName = "com.example.launcher", homePackage = "com.example.launcher"),
        )
        assertEquals(
            AccessibilityWindowClass.Ignore,
            classify(packageName = "com.android.systemui", isLaunchable = { false }),
        )
    }

    @Test
    fun unknownWindowTypeFallsBackToPackageOnlyJudgement() {
        // 部分 ROM 的 getWindows 返回空：此时不能因为拿不到窗口类型就放行输入法。
        assertEquals(
            AccessibilityWindowClass.App(appA),
            classify(packageName = appA, windowType = null, isLaunchable = { true }),
        )
    }

    private val ownMainActivity = "me.excuse.app.MainActivity"

    private fun classify(
        packageName: String,
        className: String? = null,
        windowType: Int? = ACCESSIBILITY_WINDOW_TYPE_APPLICATION,
        ownPackage: String = "me.excuse.app",
        homePackage: String? = null,
        inputMethodPackages: Set<String> = emptySet(),
        isLaunchable: (String) -> Boolean = { true },
    ): AccessibilityWindowClass = classifyAccessibilityWindow(
        packageName = packageName,
        className = className,
        windowType = windowType,
        ownPackage = ownPackage,
        ownMainActivityClassName = ownMainActivity,
        homePackage = homePackage,
        inputMethodPackages = inputMethodPackages,
        isLaunchable = isLaunchable,
    )

    /** 只用到分类需要的两个常量，避免单测依赖 Android 的 AccessibilityWindowInfo。 */
    private object AccessibilityWindowInfoType {
        const val INPUT_METHOD = 2
    }
}
