package me.excuse.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "prefs")

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

class Prefs(private val context: Context) {
    private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_ACTIVE_WEEKDAYS = stringSetPreferencesKey("active_weekdays")
    private val KEY_PAUSE_UNTIL_MILLIS = longPreferencesKey("pause_until_millis")
    private val KEY_PAUSE_SOURCE = stringPreferencesKey("pause_source")

    // 首屏的去向和配色必须来自同一次读取，不能把真实标志与默认浅色拼成一帧。
    val startupPreferences: Flow<StartupPreferences> = context.dataStore.data.map {
        StartupPreferences(
            onboarded = it[KEY_ONBOARDED] ?: false,
            themeMode = ThemeMode.fromKey(it[KEY_THEME_MODE]),
        )
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDED] = value }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { ThemeMode.fromKey(it[KEY_THEME_MODE]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.key }
    }

    /**
     * 拦截调度配置。默认全 7 天、未暂停。
     *
     * 周几以 ISO 序数值（1=周一 … 7=周日）存入 StringSet；暂停截止存绝对时间戳。
     * 读到非法值（例如历史数据被清空）时安全回退到默认，不抛异常。
     */
    val interceptSchedule: Flow<InterceptSchedule> = context.dataStore.data.map { prefs ->
        val dayValues = prefs[KEY_ACTIVE_WEEKDAYS]
        val active = if (dayValues == null) {
            DayOfWeek.entries.toSet()
        } else {
            dayValues.mapNotNull { raw ->
                raw.toIntOrNull()?.let { value ->
                    DayOfWeek.entries.firstOrNull { it.value == value }
                }
            }.toSet()
        }
        val pauseUntil = prefs[KEY_PAUSE_UNTIL_MILLIS]?.takeIf { it > 0L }
        InterceptSchedule(
            activeWeekdays = active,
            pauseUntilMillis = pauseUntil,
            pauseSource = PauseSource.fromKey(prefs[KEY_PAUSE_SOURCE]),
        )
    }

    suspend fun setActiveWeekdays(days: Set<DayOfWeek>) {
        val values = days.map { it.value.toString() }.toSet()
        context.dataStore.edit { it[KEY_ACTIVE_WEEKDAYS] = values }
    }

    suspend fun pauseUntil(millis: Long, source: PauseSource) {
        context.dataStore.edit {
            it[KEY_PAUSE_UNTIL_MILLIS] = millis
            it[KEY_PAUSE_SOURCE] = source.key
        }
    }

    suspend fun resumeNow() {
        context.dataStore.edit {
            it.remove(KEY_PAUSE_UNTIL_MILLIS)
            it.remove(KEY_PAUSE_SOURCE)
        }
    }
}
