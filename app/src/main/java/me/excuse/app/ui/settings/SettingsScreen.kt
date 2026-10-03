package me.excuse.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import me.excuse.app.BuildConfig
import me.excuse.app.appServices
import me.excuse.app.data.prefs.InterceptSchedule
import me.excuse.app.data.prefs.PauseSource
import me.excuse.app.data.prefs.ThemeMode
import me.excuse.app.service.InterceptScheduleEvaluator
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.MutedSoft
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.theme.SurfaceBg
import me.excuse.app.util.TimeUtil
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SettingsScreen(onRedoPermissions: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page.isNotEmpty()) { page = "" }
    when (page) {
        "guide" -> {
            UsageGuideScreen(onBack = { page = "" })
            return
        }
        "records" -> {
            RecordsScreen(onBack = { page = "" })
            return
        }
        "schedule" -> {
            InterceptScheduleScreen(onBack = { page = "" })
            return
        }
    }
    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 56.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // Hub 风大标题
            Text(
                text = "设置",
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                color = Ink,
                lineHeight = 64.sp
            )

            // 版本块：小标签 + 大字号版本号
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "版本",
                    fontSize = 12.sp,
                    color = Muted,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = BuildConfig.VERSION_NAME,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    color = Ink,
                    lineHeight = 36.sp
                )
                Text(
                    text = "build ${BuildConfig.VERSION_CODE}",
                    fontSize = 12.sp,
                    color = Muted
                )
            }

            // 主题选择块：小标签 + 三个堆叠按钮
            ThemeSection()

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { page = "guide" },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper)
            ) {
                Text("使用说明")
            }

            Button(
                onClick = { page = "schedule" },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceBg, contentColor = Ink)
            ) {
                Text("拦截安排")
            }

            Button(
                onClick = { page = "records" },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceBg, contentColor = Ink)
            ) {
                Text("管理记录")
            }
            Button(
                onClick = onRedoPermissions,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceBg, contentColor = Ink)
            ) {
                Text("权限与自启动设置")
            }
        }
    }
}

@Composable
private fun ThemeSection() {
    val services = LocalContext.current.appServices
    val mode by services.themeModeFlow.collectAsState()
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "主题",
            fontSize = 12.sp,
            color = Muted,
            letterSpacing = 0.5.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                ThemeMode.SYSTEM to "跟随系统",
                ThemeMode.LIGHT to "浅色",
                ThemeMode.DARK to "深色"
            ).forEach { (m, label) ->
                ThemeChip(
                    label = label,
                    selected = m == mode,
                    onClick = { scope.launch { services.prefs.setThemeMode(m) } }
                )
            }
        }
    }
}

@Composable
private fun RowScope.ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(36.dp),
        shape = RoundedCornerShape(0.dp),
        border = BorderStroke(1.dp, if (selected) Ink else MutedSoft),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Ink else SurfaceBg,
            contentColor = if (selected) Paper else Ink
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(label, fontSize = 13.sp)
    }
}

private val WEEKDAY_LABELS = listOf(
    DayOfWeek.MONDAY to "一",
    DayOfWeek.TUESDAY to "二",
    DayOfWeek.WEDNESDAY to "三",
    DayOfWeek.THURSDAY to "四",
    DayOfWeek.FRIDAY to "五",
    DayOfWeek.SATURDAY to "六",
    DayOfWeek.SUNDAY to "日",
)

@Composable
private fun InterceptScheduleScreen(onBack: () -> Unit) {
    val services = LocalContext.current.appServices
    val schedule by services.prefs.interceptSchedule.collectAsState(initial = InterceptSchedule())
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("← 返回", color = Muted)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp)
                    .padding(top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Text(
                    "拦截安排",
                    fontSize = 48.sp,
                    lineHeight = 56.sp,
                    fontWeight = FontWeight.Light,
                    color = Ink
                )

                Spacer(Modifier.height(16.dp))

                // 周几 chip：一～日
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "每周拦截日",
                        fontSize = 14.sp,
                        color = Muted,
                        letterSpacing = 0.5.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WEEKDAY_LABELS.forEach { (day, label) ->
                            val selected = day in schedule.activeWeekdays
                            OutlinedButton(
                                onClick = {
                                    val next = if (selected) {
                                        schedule.activeWeekdays - day
                                    } else {
                                        schedule.activeWeekdays + day
                                    }
                                    scope.launch { services.prefs.setActiveWeekdays(next) }
                                },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, if (selected) Ink else MutedSoft),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) Ink else SurfaceBg,
                                    contentColor = if (selected) Paper else Ink
                                ),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                }

                // 暂停拦截
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "暂停拦截",
                        fontSize = 14.sp,
                        color = Muted,
                        letterSpacing = 0.5.sp
                    )
                    val now = System.currentTimeMillis()
                    if (schedule.isPausedAt(now)) {
                        val until = schedule.pauseUntilMillis ?: now
                        Text(
                            text = "已暂停到 ${TimeUtil.hhmm(until)}",
                            fontSize = 13.sp,
                            color = Muted
                        )
                        Button(
                            onClick = { scope.launch { services.prefs.resumeNow() } },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper)
                        ) {
                            Text("恢复拦截")
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val until = InterceptScheduleEvaluator.pauseForHours(now, 2L)
                                    scope.launch { services.prefs.pauseUntil(until, PauseSource.TWO_HOURS) }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, MutedSoft),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = SurfaceBg,
                                    contentColor = Ink
                                )
                            ) {
                                Text("休息 2 小时")
                            }
                            OutlinedButton(
                                onClick = {
                                    val until = InterceptScheduleEvaluator.pauseUntilEndOfDay(
                                        LocalDate.now(ZoneId.systemDefault()),
                                        ZoneId.systemDefault(),
                                    )
                                    scope.launch { services.prefs.pauseUntil(until, PauseSource.END_OF_DAY) }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, MutedSoft),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = SurfaceBg,
                                    contentColor = Ink
                                )
                            ) {
                                Text("今日休息")
                            }
                        }
                    }
                }
            }
        }
    }
}
