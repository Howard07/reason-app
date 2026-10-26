package me.excuse.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.excuse.app.AppServices
import me.excuse.app.BuildConfig
import me.excuse.app.MainActivity
import me.excuse.app.R
import me.excuse.app.data.prefs.InterceptSchedule
import me.excuse.app.data.prefs.PauseSource
import me.excuse.app.service.InterceptStateMachine.Effect
import java.time.LocalDate
import java.time.ZoneId

/**
 * 监控中枢：前台事实来自 [AccessibilityForegroundHub]（无障碍窗口事件），
 * 状态机 effect 的窗口竞争交给 [PromptPresenter]。
 *
 * UsageStats 在这里只剩一个职责：检测（重）启动时对过去 10 秒做一次快照播种，
 * 让 sticky 与现实对齐；之后的每一个前台变化都由无障碍事件驱动，不再轮询。
 */
class AppMonitorService : LifecycleService() {

    private data class UsageEventSnapshot(
        val timestamp: Long,
        val eventType: Int,
        val packageName: String,
        val className: String?,
    )

    private data class StartupHistoryResult(
        val packageName: String?,
        val querySucceeded: Boolean,
    )

    private lateinit var sessionStore: SessionStore
    private lateinit var promptPresenter: PromptPresenter
    private lateinit var usm: UsageStatsManager
    private lateinit var permissionObserver: MonitoringPermissionObserver
    private val sessionWriterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var startupCleanupJob: Job
    private val monitoredReady = CompletableDeferred<Unit>()
    private var pollJob: Job? = null
    private var notifJob: Job? = null
    private var startupSeedAttempted = false

    /** 权限/无障碍缺失时的降级快照；null 表示处于正常工作状态。见 [ensureMonitoringPermissions]。 */
    private var degradedState: MonitoringPermissionState? = null

    private val foregroundHub = AccessibilityForegroundHub

    @Volatile private var monitoredCache: Set<String> = emptySet()
    private var monitoredSubscription: Job? = null

    /** 拦截调度配置的最新快照，每次 tick 据此重算「今天拦不拦」。 */
    @Volatile private var interceptSchedule: InterceptSchedule = InterceptSchedule()
    private var scheduleSubscription: Job? = null

    private var screenReceiver: BroadcastReceiver? = null
    private var deadlineReceiver: BroadcastReceiver? = null

    private val stateMachine = InterceptStateMachine()

    /**
     * 无障碍通道的包级前台变化 → 状态机。sticky 已由 hub 更新，这里只决定要不要驱动。
     *
     * 息屏 / 降级期间不驱动状态机：那两段里事件流与现实脱钩（锁屏窗口搅动），
     * sticky 留给亮屏时的播种重新校准。
     */
    private val foregroundChangeListener: (AccessibilityForegroundTracker.Change) -> Unit =
        listener@{ change ->
            if (degradedState != null) return@listener
            if (pollJob?.isActive != true) return@listener
            if (monitoredCache.isEmpty() &&
                sessionStore.all().isEmpty() &&
                stateMachine.state !is InterceptStateMachine.State.Prompting
            ) {
                return@listener
            }
            debugLog {
                "a11y foreground -> ${change.packageName} edge=${change.isEntryEdge} " +
                    foregroundHub.tracker.debugState()
            }
            executeTick(change.packageName, change.at, change.isEntryEdge)
            // 有真实切换时顺手推进到点 session，不必等下一个维护 tick。
            maybeAdvanceElapsedSessions(change.at)
        }

    /** 无障碍绑定状态变化就是权限门控的输入：断开即降级，重连即自愈。 */
    private val connectionListener: (Boolean) -> Unit = { connected ->
        Log.w(DIAG_TAG, "accessibility connection changed: connected=$connected")
        ensureMonitoringPermissions()
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as AppServices
        val deadlineAlarm = SessionDeadlineAlarm(this)
        sessionStore = SessionStore(
            timer = SessionTimer(lifecycleScope),
            writer = app.repository,
            writerScope = sessionWriterScope,
            deadlineAlarm = deadlineAlarm,
            onWriteFailure = { operation, error ->
                Log.e(DIAG_TAG, "$operation failed; queued for bounded retry", error)
            },
        )
        promptPresenter = PromptPresenter(
            context = this,
            scope = lifecycleScope,
            repository = app.repository,
            stateMachine = stateMachine,
            sessionStore = sessionStore,
            monitoredPackages = { monitoredCache },
            foregroundPackage = { foregroundHub.tracker.stickyPackageName },
            foregroundClassName = { foregroundHub.tracker.stickyClassName },
            // 无障碍事件是即时的包级切换，不存在 UsageStats 那种「PAUSED 未决」中间态。
            hasPendingPauseFor = { false },
            executeEffects = { execute(it) },
            beforeSendHome = { _, _ ->
                debugLog {
                    "sendHome state=${stateMachine.state} ${foregroundHub.tracker.debugState()}"
                }
                // 主动送 Home 后 launcher 的窗口事件随即到达；先把 sticky 置空，
                // 事件到达时 sticky 无变化，就不会再驱动出多余的状态机动作。
                foregroundHub.tracker.forceForeground(null)
            },
            afterReturnHome = { _ ->
                foregroundHub.tracker.forceForeground(null)
            },
            debugLog = { message -> debugLog { message } },
        )
        usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        startInForeground()

        startupCleanupJob = app.sessionCleanupQueue.enqueue("unfinished-session recovery") {
            app.repository.finishUnfinishedSessions()
        }

        try { promptPresenter.prepareAndWarmUp() } catch (_: Exception) {}

        scheduleSubscription = lifecycleScope.launch {
            try {
                app.prefs.interceptSchedule.collect { schedule ->
                    interceptSchedule = schedule
                    // 调度变化可能立刻改变「今天拦不拦」，刷新通知以反映最新状态。
                    refreshNotification()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(DIAG_TAG, "intercept-schedule subscription failed", error)
            }
        }

        monitoredSubscription = lifecycleScope.launch {
            try {
                app.repository.monitoredApps().collect { list ->
                    val enabledApps = list.filter { it.enabled }
                    // monitoredCache 一旦发布，下一次事件就可能马上触发 prompt；先填名字，
                    // 避免首个 prompt 在主线程冷调 PackageManager。
                    enabledApps.forEach { monitored ->
                        promptPresenter.cacheAppName(monitored.packageName, monitored.appName)
                    }
                    val nextMonitored = enabledApps.map { it.packageName }.toSet()
                    val removed = monitoredCache - nextMonitored
                    monitoredCache = nextMonitored
                    monitoredReady.complete(Unit)
                    val activePackage = when (val state = stateMachine.state) {
                        is InterceptStateMachine.State.InSession -> state.pkg
                        is InterceptStateMachine.State.SessionLeaving -> state.pkg
                        is InterceptStateMachine.State.TimeUp -> state.pkg
                        else -> null
                    }
                    // 只主动处理“移除正在运行的目标”。新增 app 时 sticky 可能仍是进入
                    // Reason 主界面前的旧值，拿它主动 tick 会在设置页制造幽灵 prompt。
                    // 这里传 null 只结束旧 session；真实的新前台仍交给无障碍事件。
                    if (activePackage != null && activePackage in removed) {
                        executeTick(
                            foreground = null,
                            now = System.currentTimeMillis(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(DIAG_TAG, "monitored-app subscription failed", error)
            } finally {
                monitoredReady.complete(Unit)
            }
        }

        foregroundHub.addChangeListener(foregroundChangeListener)
        foregroundHub.addConnectionListener(connectionListener)

        permissionObserver = MonitoringPermissionObserver(this) {
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                // 权限变化同时承担两个方向：撤销时进入降级，恢复时自愈。
                ensureMonitoringPermissions()
            }
        }.also { it.start() }

        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> pauseDetection()
                        Intent.ACTION_SCREEN_ON -> resumeDetection()
                    }
                }
            }
            screenReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(
                this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {}

        // 到点兜底：精确闹钟触发时（进程可能刚被 ROM 冻结后唤醒），直接推进状态机。
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != SessionDeadlineAlarm.ACTION_SESSION_DEADLINE) return
                    val pkg = intent.getStringExtra(SessionDeadlineAlarm.EXTRA_PACKAGE_NAME)
                        ?: return
                    Log.w("ReasonTimer", "deadline alarm fired pkg=$pkg")
                    execute(stateMachine.onSessionTimeElapsed(pkg, System.currentTimeMillis()))
                }
            }
            deadlineReceiver = receiver
            val filter = IntentFilter(SessionDeadlineAlarm.ACTION_SESSION_DEADLINE)
            // 自定义 action 属于非系统广播，API 34+ 强制要求显式声明导出状态；
            // ContextCompat 在低版本上退化为带 signature 权限的等价保护。
            ContextCompat.registerReceiver(
                this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 每次被拉起都续排看门狗：即使上一次的闹钟被 ROM 清掉，这一句也能补回来。
        MonitorWatchdog.schedule(this)
        if (ensureMonitoringPermissions()) {
            val pm = getSystemService(PowerManager::class.java)
            val interactive = pm?.isInteractive ?: true
            if (interactive) {
                resumeDetection()
            }
        }
        // 降级时也返回 STICKY。降级是「权限/无障碍暂时缺失」，不是「应该放弃重启资格」。
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        foregroundHub.removeChangeListener(foregroundChangeListener)
        foregroundHub.removeConnectionListener(connectionListener)
        if (::permissionObserver.isInitialized) permissionObserver.stop()
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
        deadlineReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        deadlineReceiver = null
        monitoredSubscription?.cancel()
        scheduleSubscription?.cancel()
        pollJob?.cancel()
        notifJob?.cancel()
        // 禁止挂起的确认在快照之后再启动计时器；后台收尾还会等其取消清理真正结束。
        lifecycleScope.cancel()
        finishActiveSessionsForShutdown()
        sessionWriterScope.cancel()
        promptPresenter.release()
        super.onDestroy()
    }

    private fun resumeDetection() {
        if (!ensureMonitoringPermissions()) return
        if (pollJob?.isActive != true) {
            pollJob = lifecycleScope.launch { maintenanceLoop() }
        }
        if (notifJob?.isActive != true) {
            notifJob = lifecycleScope.launch { notificationTickLoop() }
        }
    }

    private fun pauseDetection() {
        val now = System.currentTimeMillis()
        execute(stateMachine.reset(now))
        promptPresenter.pause()
        foregroundHub.tracker.reset()
        suspendPolling()
    }

    /**
     * 只停维护与通知循环，不动状态机和进行中的 session。
     *
     * [pauseDetection] 语义是「用户息屏 / 主动离开」，重置状态机是对的；
     * 但权限/无障碍抖动只该让监控暂停，不该被当成「用户已经不在用了」而结束 session。
     */
    private fun suspendPolling() {
        pollJob?.cancel()
        pollJob = null
        notifJob?.cancel()
        notifJob = null
    }

    private suspend fun notificationTickLoop() {
        while (currentCoroutineActive()) {
            if (!ensureMonitoringPermissions()) {
                // 降级期间保留低频复查。权限恢复没有广播可听，只能自己回来问；
                // 这里若 return 退出循环，就再没有任何东西把检测叫醒了。
                delay(DEGRADED_PERMISSION_CHECK_MS)
                continue
            }
            val hasActiveSession = sessionStore.all().isNotEmpty()
            if (hasActiveSession) {
                refreshNotification()
            }
            val nextTickMs = if (hasActiveSession) {
                NOTIFICATION_ACTIVE_TICK_MS
            } else {
                NOTIFICATION_IDLE_TICK_MS
            }
            delay(nextTickMs)
        }
    }

    private fun refreshNotification() {
        val now = System.currentTimeMillis()
        val nearest = sessionStore.all()
            .filter { (it.startedAt + it.plannedMinutes * 60_000L) > now }
            .minByOrNull { it.startedAt + it.plannedMinutes * 60_000L }

        // 文案优先级：降级（最需要解释"为什么没拦"）> 进行中 session（已确认，要自然走完）
        // > 临时暂停 > 今日休息 > 默认。
        val text = when {
            degradedState != null -> getString(R.string.notification_degraded)
            nearest != null -> {
                val remainMs = (nearest.startedAt + nearest.plannedMinutes * 60_000L) - now
                val remainMin = ((remainMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                getString(R.string.notification_active_text, remainMin)
            }
            interceptSchedule.isPausedAt(now) -> {
                when (interceptSchedule.pauseSource) {
                    PauseSource.END_OF_DAY -> getString(R.string.notification_rest_day)
                    else -> {
                        val until = interceptSchedule.pauseUntilMillis ?: now
                        val remainMin = ((until - now + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                        getString(R.string.notification_paused_until, remainMin)
                    }
                }
            }
            !shouldInterceptNow(now) -> getString(R.string.notification_rest_day)
            else -> getString(R.string.notification_text)
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    /**
     * 主循环只剩两件不依赖事件流的事：
     * 1. 启动时播种 —— 用 UsageStats 快照把 sticky 校准到现实（之后全靠无障碍事件）；
     * 2. 低频维护 —— 纯内存的到点 session 复查，防 SessionTimer 的 delay 协程被
     *    省电策略冻结（ColorOS 等）。无任何跨进程调用，1s 一次的成本可以忽略。
     */
    private suspend fun maintenanceLoop() {
        // Room cleanup 和 monitored Flow 首帧都完成后才允许播种；否则清理 UPDATE
        // 可能误伤刚插入的新 session，seed 也可能在空名单下把真实前台吃掉。
        startupCleanupJob.join()
        monitoredReady.await()
        if (!ensureMonitoringPermissions()) return
        seedForeground()
        while (currentCoroutineActive()) {
            try {
                maybeAdvanceElapsedSessions(System.currentTimeMillis())
            } catch (t: Exception) {
                debugLog { "maintenance exception=${t::class.java.simpleName}: ${t.message}" }
            }
            delay(MAINTENANCE_TICK_MS)
        }
    }

    /**
     * 播种：用过去 10 秒的 UsageEvents 回放得出「此刻最可能的前台」。
     *
     * 只在检测（重）启动时跑一次 —— 服务冷启动、亮屏、无障碍重连之后。
     * 冷启动且 10 秒窗口得不出结论时，回看一次本次开机以来的历史。
     */
    private suspend fun seedForeground() {
        val initNow = System.currentTimeMillis()
        val isFirstSeed = !startupSeedAttempted
        startupSeedAttempted = true
        val seeded = try {
            replayForeground(
                queryUsageEvents(initNow - SEED_WINDOW_MS, initNow + 1_000L)
                    .sortedWith(eventOrder),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 一次瞬时 binder 异常不能弄死整个检测；sticky 留给下一个无障碍事件建立。
            Log.e(DIAG_TAG, "foreground seed failed", error)
            null
        }
        val historyResult = if (shouldAttemptStartupHistoryRecovery(seeded, isFirstSeed)) {
            recoverStartupForegroundFromEvents(initNow)
        } else {
            StartupHistoryResult(packageName = null, querySucceeded = false)
        }
        val startupDecision = decideStartupForeground(seeded, historyResult.packageName)
        foregroundHub.tracker.forceForeground(startupDecision.packageName)
        executeTick(
            foreground = startupDecision.packageName,
            now = initNow,
            entryEdge = startupDecision.recoveredFromHistory &&
                startupDecision.packageName in monitoredCache,
        )
    }

    /**
     * 播种回放：按序应用 RESUMED/PAUSED/STOPPED，得出窗口末端的前台包。
     * 刻意只做最简单的精确匹配回放 —— 它是「校准起点」而不是「持续真相源」，
     * 回放结束之后每一个变化都由无障碍事件驱动。
     */
    private fun replayForeground(events: List<UsageEventSnapshot>): String? {
        var pkg: String? = null
        var cls: String? = null
        for (event in events) {
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    pkg = event.packageName
                    cls = event.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> if (event.packageName == pkg && (cls == null || event.className == cls)) {
                    pkg = null
                    cls = null
                }
            }
        }
        return pkg
    }

    private suspend fun recoverStartupForegroundFromEvents(now: Long): StartupHistoryResult {
        // 只在 10 秒 seed 无法得出前台时回看一次，且不跨越本次开机，
        // 否则 BOOT_COMPLETED 可能复活上次关机前的旧前台。
        return try {
            val pkg = replayForeground(
                queryUsageEvents(
                    startupHistoryStartMillis(
                        nowMillis = now,
                        elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                        recoveryWindowMillis = STARTUP_EVENT_RECOVERY_WINDOW_MS,
                    ),
                    now + 1_000L,
                ).sortedWith(eventOrder),
            )
            StartupHistoryResult(packageName = pkg, querySucceeded = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(DIAG_TAG, "foreground history recovery failed", error)
            StartupHistoryResult(packageName = null, querySucceeded = false)
        }
    }

    /**
     * 兜底推进到点 session：SessionTimer 的 delay 协程在部分 ROM（ColorOS 等）的省电
     * 策略下会被冻结、到点不回调。这里不依赖任何定时器，直接用墙钟绝对时间复查
     * [SessionStore] 里挂着的 session，发现已到 deadline 就触发与 delay 相同的
     * [InterceptStateMachine.onSessionTimeElapsed]。
     *
     * 幂等：onSessionTimeElapsed 内部只在 state 仍是 InSession/SessionLeaving 且 pkg 匹配时
     * 才会把状态推进到 TimeUp；推进后本方法下一轮不再命中，不会重复弹窗。
     */
    private fun maybeAdvanceElapsedSessions(now: Long) {
        for (session in sessionStore.all()) {
            val deadline = session.startedAt + session.plannedMinutes * 60_000L
            if (now < deadline) continue
            val state = stateMachine.state
            val stillActive =
                (state as? InterceptStateMachine.State.InSession)?.pkg == session.packageName ||
                    (state as? InterceptStateMachine.State.SessionLeaving)?.pkg == session.packageName
            if (!stillActive) continue
            Log.w("ReasonTimer", "deadline fallback hit pkg=${session.packageName} now=$now deadline=$deadline")
            execute(stateMachine.onSessionTimeElapsed(session.packageName, now))
        }
    }

    private suspend fun queryUsageEvents(startMillis: Long, endMillis: Long): List<UsageEventSnapshot> =
        withContext(Dispatchers.Default) {
            val events = usm.queryEvents(startMillis, endMillis)
            val ev = UsageEvents.Event()
            val result = ArrayList<UsageEventSnapshot>()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType !in trackedEventTypes) continue
                val packageName = ev.packageName ?: continue
                result.add(
                    UsageEventSnapshot(
                        timestamp = ev.timeStamp,
                        eventType = ev.eventType,
                        packageName = packageName,
                        className = ev.className,
                    )
                )
            }
            result
        }

    private fun executeTick(foreground: String?, now: Long, entryEdge: Boolean = false) {
        val beforeState = stateMachine.state
        val beforeInitialEntryPending =
            (beforeState as? InterceptStateMachine.State.InSession)?.initialEntryPending
        val effects = stateMachine.tick(
            foreground = foreground,
            monitored = monitoredCache,
            now = now,
            entryEdge = entryEdge,
            interceptEnabled = shouldInterceptNow(now),
        )
        if (effects.isNotEmpty() || entryEdge) {
            val afterInitialEntryPending =
                (stateMachine.state as? InterceptStateMachine.State.InSession)?.initialEntryPending
            debugLog {
                "tick foreground=$foreground entryEdge=$entryEdge " +
                    "initialEntryPending=$beforeInitialEntryPending->$afterInitialEntryPending " +
                    "before=$beforeState state=${stateMachine.state} effects=$effects " +
                    foregroundHub.tracker.debugState()
            }
        }
        execute(effects)
    }

    private fun execute(effects: List<Effect>) {
        effects.forEach { execute(it) }
    }

    /**
     * 「今天拦不拦」：临时暂停优先于周几规则。每次 tick 用当前时间重算，跨午夜 / 暂停到期
     * 都能自然切换，不依赖任何定时器。判定用绝对时间戳，保证跨进程、设备重启后语义一致。
     */
    private fun shouldInterceptNow(nowMillis: Long): Boolean =
        InterceptScheduleEvaluator.shouldIntercept(
            schedule = interceptSchedule,
            now = LocalDate.now(ZoneId.systemDefault()),
            nowMillis = nowMillis,
            zoneId = ZoneId.systemDefault(),
        )

    private fun execute(effect: Effect) {
        when (effect) {
            is Effect.SendHome -> promptPresenter.sendHome(effect.pkg)
            Effect.Dismiss -> promptPresenter.dismiss()
            is Effect.ShowPrompt -> {
                if (ensureMonitoringPermissions()) promptPresenter.showPrompt(effect.pkg)
            }
            is Effect.ShowTimeUp -> {
                Log.w("ReasonTimer", "Effect.ShowTimeUp pkg=${effect.pkg}")
                if (ensureMonitoringPermissions()) promptPresenter.showTimeUp(effect.pkg)
            }
            is Effect.FinishSession -> finishSession(effect.pkg, effect.endedAt, effect.overran)
            is Effect.RecordInterceptOutcome ->
                promptPresenter.recordInterceptOutcome(effect.pkg, effect.outcome)
            Effect.RefreshNotification -> refreshNotification()
            is Effect.SchedulePromptTimeout ->
                promptPresenter.schedulePromptTimeout(effect.pkg, effect.durationMs)
            Effect.CancelPromptTimeout -> promptPresenter.cancelPromptTimeout()
            is Effect.ScheduleSessionLeave -> scheduleSessionLeave(effect.pkg, effect.delayMs)
            Effect.CancelSessionLeave -> cancelSessionLeave()
        }
    }

    private fun scheduleSessionLeave(pkg: String, delayMs: Long) {
        sessionLeaveJob?.cancel()
        val requestGeneration = ++sessionLeaveGeneration
        sessionLeaveJob = lifecycleScope.launch {
            delay(delayMs)
            if (!isCurrentGeneration(requestGeneration, sessionLeaveGeneration)) return@launch
            sessionLeaveJob = null
            execute(stateMachine.onSessionLeaveExpired(pkg, System.currentTimeMillis()))
        }
    }

    private fun cancelSessionLeave() {
        sessionLeaveGeneration += 1L
        sessionLeaveJob?.cancel()
        sessionLeaveJob = null
    }

    private var sessionLeaveJob: Job? = null
    private var sessionLeaveGeneration = 0L

    private fun finishSession(pkg: String, endedAt: Long, overran: Boolean) =
        sessionStore.finish(pkg, endedAt, overran)

    private fun finishActiveSessionsForShutdown() {
        val stoppedStore = sessionStore
        stoppedStore.stopForShutdown(System.currentTimeMillis())
        val serviceJob = lifecycleScope.coroutineContext[Job]
        // 由 Application 持有，service 销毁不会取消冲刷；重启恢复也排在它后面。
        (application as AppServices).sessionCleanupQueue.enqueue("session shutdown flush") {
            serviceJob?.join()
            stoppedStore.flushForShutdown()
        }
    }

    /**
     * 监控门控。
     *
     * 可监控 = 使用情况访问 + 悬浮窗 + **无障碍服务此刻真的被系统绑定**。
     * 第三项必须用 [AccessibilityForegroundHub.isConnected]（绑定真相）而不是设置项：
     * 国产 ROM 杀掉无障碍服务后设置开关常常仍是开的，按设置项放行会让监控在
     * 「事件流已断」的情况下盲跑 —— 现象就是「开关都在，却突然不拦了」。
     *
     * 原实现缺任一权限就 stopSelf，而「主动 stop」会连同 START_STICKY 的重启资格一起
     * 取消；现在改为降级：停循环、把常驻通知换成「已暂停」，服务本体继续活着，
     * 等权限恢复（[MonitoringPermissionObserver] / 无障碍重连回调）或看门狗下次拉起时自愈。
     *
     * 通知权限单独放宽：它只影响常驻通知的可见性，不影响拦截能力，不该因此停掉监控。
     *
     * @return true 表示可以继续弹窗 / 驱动状态机；false 表示已进入降级。
     */
    private fun ensureMonitoringPermissions(): Boolean {
        val state = try {
            MonitoringPermissions.current(this)
        } catch (error: RuntimeException) {
            // 读权限本身抛异常不能等同于「权限丢了」，否则一次 binder 抖动就停摆。
            Log.e(DIAG_TAG, "monitoring permission check failed", error)
            return true
        }
        val canMonitor = state.usageAccess && state.overlay && foregroundHub.isConnected
        if (canMonitor) {
            if (degradedState != null) {
                Log.w(DIAG_TAG, "permissions restored; leaving degraded mode")
                degradedState = null
                resumeDetection()
                refreshNotification()
            }
            return true
        }
        if (degradedState == null) {
            Log.w(
                DIAG_TAG,
                "NOT ready usage=${state.usageAccess} overlay=${state.overlay} " +
                    "a11yConnected=${foregroundHub.isConnected}; entering degraded mode",
            )
            degradedState = state
            suspendPolling()
            // 降级后必须留一个低频复查循环，否则权限恢复时没有任何东西把监控叫回来。
            if (notifJob?.isActive != true) {
                notifJob = lifecycleScope.launch { notificationTickLoop() }
            }
            refreshNotification()
        }
        return false
    }

    private suspend fun currentCoroutineActive(): Boolean = currentCoroutineContext().isActive

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(DIAG_TAG, message())
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .build()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_desc) }
        nm.createNotificationChannel(ch)

        val notification = buildNotification(getString(R.string.notification_text))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "monitor"
        private const val DIAG_TAG = "ReasonMonitor"

        /**
         * 维护 tick：纯内存的到点 session 复查。实时性（打开 app 多久弹窗）由无障碍
         * 事件保证，与这里无关；1s 一次只是为了省电冻结场景下到点窗最多晚 1 秒。
         */
        private const val MAINTENANCE_TICK_MS = 1_000L
        private const val NOTIFICATION_ACTIVE_TICK_MS = 60_000L
        private const val NOTIFICATION_IDLE_TICK_MS = 60_000L
        /** 降级期间复查权限的间隔。权限恢复没有广播可听，只能低频轮询。 */
        private const val DEGRADED_PERMISSION_CHECK_MS = 15_000L
        private const val SEED_WINDOW_MS = 10_000L

        /** 只在 service 冷启动且 10 秒 seed 无法得出前台时读取一次，不进入常态循环。 */
        private const val STARTUP_EVENT_RECOVERY_WINDOW_MS = 24L * 60L * 60L * 1_000L

        private val trackedEventTypes = setOf(
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED,
        )

        // 播种回放内的排序：同 ms 内 STOPPED 优先，其次 PAUSED，最后 RESUMED。
        private val eventOrder =
            compareBy<UsageEventSnapshot> { it.timestamp }
                .thenByDescending { it.eventType }

        fun start(context: Context) {
            val ready = try {
                MonitoringPermissions.current(context).isReady
            } catch (_: RuntimeException) {
                false
            }
            if (!ready) return
            startForegroundSafely(context, reason = "start")
        }

        /**
         * 到点闹钟触发时调用：把服务拉起来。进程还活着时动态 receiver 已直接处理；
         * 进程被 ROM 杀死时，这里负责冷启动，让服务重新评估前台状态并兜底处理。
         * 注意：进程被杀时正在进行的 session 已随 onDestroy 结束，冷启动无法恢复
         * 一个不存在的 session 的超时窗，此时仅保证服务回到正确的前台监控状态。
         */
        fun startForDeadline(context: Context, packageName: String) {
            val ready = try {
                MonitoringPermissions.current(context).isReady
            } catch (_: RuntimeException) {
                false
            }
            if (!ready) return
            startForegroundSafely(context, reason = "deadline pkg=$packageName")
        }

        /**
         * 看门狗触发时调用。
         *
         * 与 [start] 不同，这里不预先检查权限：权限缺失时更需要把服务立起来，
         * 让常驻通知告诉用户「为什么没拦」，而不是静默地什么都不做。
         * 降级判断由 [onStartCommand] 内部完成。
         */
        fun startForWatchdog(context: Context) {
            startForegroundSafely(context, reason = "watchdog")
        }

        /**
         * 无障碍服务被系统绑定时调用。
         *
         * 这是全项目最可靠的复活点：进程被 ROM 杀掉后，只要无障碍开关还开着，
         * 系统就会重新绑定 [ForegroundAccessibilityService]，而「有服务被系统绑定」
         * 命中 Android 12+ 后台启动前台服务的豁免条款，所以这里的启动不会被拒绝。
         */
        fun startFromAccessibility(context: Context) {
            startForegroundSafely(context, reason = "accessibility")
        }

        /**
         * Android 12+ 从后台启动前台服务受限制，未命中豁免时会抛
         * ForegroundServiceStartNotAllowedException（IllegalStateException 的子类）。
         *
         * 这里必须吞掉：抛出去会让 BroadcastReceiver 直接崩，而崩溃的 receiver 不留任何
         * 用户可见痕迹 —— 现象就是「闹钟明明该到点了，却什么都没发生」。
         * 失败的这次不补做重试，等看门狗下一个周期或用户下次打开 app 自然重来。
         */
        private fun startForegroundSafely(context: Context, reason: String) {
            try {
                context.startForegroundService(Intent(context, AppMonitorService::class.java))
            } catch (error: IllegalStateException) {
                Log.w(
                    "ReasonWatchdog",
                    "foreground start blocked ($reason); will retry on next tick",
                    error,
                )
            }
        }
    }
}
