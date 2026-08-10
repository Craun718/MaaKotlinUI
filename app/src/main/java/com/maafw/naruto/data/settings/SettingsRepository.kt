package com.maafw.naruto.data.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置仓库喵～
 * 目前支持：屏幕常亮、主题模式喵。
 */
object SettingsRepository {

    private const val PREF_NAME = "maa_settings"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_THEME = "theme"
    private const val KEY_AUTO_START_SHIZUKU = "auto_start_shizuku"
    private const val KEY_SHOW_FLOATING_LOG = "show_floating_log"
    private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
    private const val KEY_NOTIFICATION_SOUND = "notification_sound"
    private const val KEY_NOTIFICATION_VIBRATE = "notification_vibrate"
    private const val KEY_TASK_CONFIG_PREFIX = "task_config_"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ========== 通知设置喵 ==========
    fun isNotificationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NOTIFICATION_ENABLED, true)
    }

    fun setNotificationEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_ENABLED, value).apply()
    }

    fun isNotificationSound(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NOTIFICATION_SOUND, true)
    }

    fun setNotificationSound(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_SOUND, value).apply()
    }

    fun isNotificationVibrate(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NOTIFICATION_VIBRATE, false)
    }

    fun setNotificationVibrate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_VIBRATE, value).apply()
    }

    // ========== 任务配置喵 ==========
    data class TaskConfig(
        val entry: String,
        val enabled: Boolean = false,
        val options: Map<String, String> = emptyMap()
    )

    fun getTaskConfig(context: Context, entry: String): TaskConfig {
        val json = prefs(context).getString(KEY_TASK_CONFIG_PREFIX + entry, null) ?: return TaskConfig(entry)
        return try {
            val obj = org.json.JSONObject(json)
            val opts = mutableMapOf<String, String>()
            val optJson = obj.optJSONObject("options") ?: org.json.JSONObject()
            val keys = optJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                opts[key] = optJson.getString(key)
            }
            TaskConfig(
                entry = entry,
                enabled = obj.optBoolean("enabled", false),
                options = opts
            )
        } catch (e: Exception) {
            TaskConfig(entry)
        }
    }

    fun setTaskConfig(context: Context, config: TaskConfig) {
        val obj = org.json.JSONObject().apply {
            put("entry", config.entry)
            put("enabled", config.enabled)
            put("options", org.json.JSONObject().apply {
                for ((k, v) in config.options) {
                    put(k, v)
                }
            })
        }
        prefs(context).edit().putString(KEY_TASK_CONFIG_PREFIX + config.entry, obj.toString()).apply()
    }

    fun setTaskEnabled(context: Context, entry: String, enabled: Boolean) {
        val cfg = getTaskConfig(context, entry).copy(enabled = enabled)
        setTaskConfig(context, cfg)
    }

    fun setTaskOptions(context: Context, entry: String, options: Map<String, String>) {
        val cfg = getTaskConfig(context, entry).copy(options = options)
        setTaskConfig(context, cfg)
    }

    fun getEnabledTasks(context: Context): List<String> {
        return prefs(context).all.keys
            .filter { it.startsWith(KEY_TASK_CONFIG_PREFIX) }
            .mapNotNull { key ->
                val entry = key.removePrefix(KEY_TASK_CONFIG_PREFIX)
                val cfg = getTaskConfig(context, entry)
                if (cfg.enabled) entry else null
            }
    }

    fun isKeepScreenOn(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)
    }

    fun setKeepScreenOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
    }

    fun getTheme(context: Context): String {
        return prefs(context).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME, value).apply()
    }

    fun isAutoStartShizuku(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_START_SHIZUKU, false)
    }

    fun setAutoStartShizuku(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_SHIZUKU, value).apply()
    }

    fun isShowFloatingLog(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_FLOATING_LOG, false)
    }

    fun setShowFloatingLog(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_FLOATING_LOG, value).apply()
    }

        // ========== 运行设置喵（ 全局开关） ==========
    private const val KEY_CLOSE_GAME_AFTER_TASK = "close_game_after_task"
    private const val KEY_MUTE_ON_GAME_LAUNCH = "mute_on_game_launch"
    private const val KEY_USE_HARDWARE_SCREEN_OFF = "use_hardware_screen_off"
    private const val KEY_SHOW_TOUCH_PREVIEW = "show_touch_preview"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_RUN_MODE = "run_mode"
    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_PUSH_CHANNEL = "push_channel"
    private const val KEY_PUSH_MIAOTIXING_TOKEN = "push_miaotixing_token"
    private const val KEY_PUSH_SERVERCHAN_KEY = "push_serverchan_key"
    private const val KEY_PUSH_DINGTALK_TOKEN = "push_dingtalk_token"
    private const val KEY_PUSH_SMTP_HOST = "push_smtp_host"
    private const val KEY_PUSH_SMTP_PORT = "push_smtp_port"
    private const val KEY_PUSH_SMTP_USER = "push_smtp_user"
    private const val KEY_PUSH_SMTP_PASS = "push_smtp_pass"
    private const val KEY_PUSH_SMTP_TO = "push_smtp_to"
    private const val KEY_PUSH_WEBHOOK_URL = "push_webhook_url"
    private const val KEY_PUSH_WEBHOOK_BODY = "push_webhook_body"

    const val THEME_MONET = "monet"

    const val RUN_MODE_SHIZUKU = "shizuku"
    const val RUN_MODE_ROOT = "root"

    fun isCloseGameAfterTask(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CLOSE_GAME_AFTER_TASK, false)
    }

    fun setCloseGameAfterTask(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOSE_GAME_AFTER_TASK, value).apply()
    }

    /** 启动任务时自动静音游戏（ muteOnGameLaunch）喵 */
    fun isMuteOnGameLaunch(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MUTE_ON_GAME_LAUNCH, false)
    }

    fun setMuteOnGameLaunch(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUTE_ON_GAME_LAUNCH, value).apply()
    }

    /** 硬件熄屏（关物理屏幕而非虚拟屏）喵 */
    fun isUseHardwareScreenOff(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_USE_HARDWARE_SCREEN_OFF, false)
    }

    fun setUseHardwareScreenOff(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_HARDWARE_SCREEN_OFF, value).apply()
    }

    /** 预览屏显示触摸标记喵 */
    fun isShowTouchPreview(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_TOUCH_PREVIEW, false)
    }

    fun setShowTouchPreview(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_TOUCH_PREVIEW, value).apply()
    }
const val RES_720P = "720p"
    const val RES_1080P = "1080p"
    const val RES_CUSTOM = "custom"
    private const val KEY_RES_CUSTOM_W = "res_custom_w"
    private const val KEY_RES_CUSTOM_H = "res_custom_h"
    private const val KEY_RES_CUSTOM_DPI = "res_custom_dpi"

    /** 虚拟屏分辨率（720p/1080p/custom）喵 */
    fun getResolution(context: Context): String {
        return prefs(context).getString(KEY_RESOLUTION, RES_720P) ?: RES_720P
    }

    fun setResolution(context: Context, value: String) {
        prefs(context).edit().putString(KEY_RESOLUTION, value).apply()
    }

    fun getCustomWidth(context: Context): Int = prefs(context).getInt(KEY_RES_CUSTOM_W, 1600)
    fun getCustomHeight(context: Context): Int = prefs(context).getInt(KEY_RES_CUSTOM_H, 900)
    fun getCustomDpi(context: Context): Int = prefs(context).getInt(KEY_RES_CUSTOM_DPI, 240)
    fun setCustomResolution(context: Context, w: Int, h: Int, dpi: Int) {
        prefs(context).edit()
            .putInt(KEY_RES_CUSTOM_W, w)
            .putInt(KEY_RES_CUSTOM_H, h)
            .putInt(KEY_RES_CUSTOM_DPI, dpi)
            .apply()
    }

    /** 返回 (宽, 高, DPI) 喵 */
    fun getResolutionFull(value: String, context: Context?): Triple<Int, Int, Int> = when (value) {
        RES_1080P -> Triple(1920, 1080, 320)
        RES_CUSTOM -> {
            if (context != null) Triple(getCustomWidth(context), getCustomHeight(context), getCustomDpi(context))
            else Triple(1600, 900, 240)
        }
        else -> Triple(1280, 720, 160)
    }
    /** 运行模式（shizuku/root）喵 */
    fun getRunMode(context: Context): String {
        return prefs(context).getString(KEY_RUN_MODE, RUN_MODE_SHIZUKU) ?: RUN_MODE_SHIZUKU
    }

    fun setRunMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_RUN_MODE, value).apply()
    }

    fun isRootMode(context: Context): Boolean = getRunMode(context) == RUN_MODE_ROOT

    /** 页面缩放（0.7~1.3，默认 1.0）喵 */
    fun getUiScale(context: Context): Float {
        return prefs(context).getFloat(KEY_UI_SCALE, 1.0f).coerceIn(0.7f, 1.3f)
    }

    fun setUiScale(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_UI_SCALE, value.coerceIn(0.7f, 1.3f)).apply()
    }

        // ========== 当前任务配置喵（记住上次选择） ==========
    private const val KEY_CURRENT_PROFILE = "current_profile"

    fun getCurrentProfile(context: Context): String {
        return prefs(context).getString(KEY_CURRENT_PROFILE, com.maafw.naruto.data.profile.ProfileManager.DEFAULT_PROFILE_NAME)
            ?: com.maafw.naruto.data.profile.ProfileManager.DEFAULT_PROFILE_NAME
    }

    fun setCurrentProfile(context: Context, name: String) {
        prefs(context).edit().putString(KEY_CURRENT_PROFILE, name).apply()
    }

    // ========== 定时任务后台唤醒喵 ==========
    private const val KEY_SCHEDULE_WAKE = "schedule_wake_on"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    /** 是否已完成首次启动引导喵 */
    fun isOnboardingDone(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
    }

    /** 后台唤醒：锁屏/应用未启动时由系统精确唤醒并执行定时任务喵 */
    fun isScheduleWakeOn(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SCHEDULE_WAKE, true)
    }

    fun setScheduleWakeOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCHEDULE_WAKE, value).apply()
    }

    // ========== 首次启动引导喵 ==========
    private const val KEY_ONBOARDED = "onboarded"

    fun isOnboarded(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    // ========== 第三方通知推送配置喵 ==========

    private const val KEY_PUSH_NOTIFY_SUCCESS = "push_notify_success"
    private const val KEY_PUSH_NOTIFY_ERROR = "push_notify_error"

    fun isPushNotifySuccess(context: Context): Boolean = prefs(context).getBoolean(KEY_PUSH_NOTIFY_SUCCESS, true)
    fun setPushNotifySuccess(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_PUSH_NOTIFY_SUCCESS, v).apply()
    fun isPushNotifyError(context: Context): Boolean = prefs(context).getBoolean(KEY_PUSH_NOTIFY_ERROR, true)
    fun setPushNotifyError(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_PUSH_NOTIFY_ERROR, v).apply()

    fun getPushChannel(context: Context): String {
        return prefs(context).getString(KEY_PUSH_CHANNEL, "none") ?: "none"
    }

    fun setPushChannel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PUSH_CHANNEL, value).apply()
    }

    fun getPushString(context: Context, key: String): String {
        return prefs(context).getString(key, "") ?: ""
    }

    fun setPushString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun getPushMiaotixingToken(context: Context) = getPushString(context, KEY_PUSH_MIAOTIXING_TOKEN)
    fun setPushMiaotixingToken(context: Context, v: String) = setPushString(context, KEY_PUSH_MIAOTIXING_TOKEN, v)
    fun getPushServerChanKey(context: Context) = getPushString(context, KEY_PUSH_SERVERCHAN_KEY)
    fun setPushServerChanKey(context: Context, v: String) = setPushString(context, KEY_PUSH_SERVERCHAN_KEY, v)
    fun getPushDingTalkToken(context: Context) = getPushString(context, KEY_PUSH_DINGTALK_TOKEN)
    fun setPushDingTalkToken(context: Context, v: String) = setPushString(context, KEY_PUSH_DINGTALK_TOKEN, v)
    fun getPushSmtpHost(context: Context) = getPushString(context, KEY_PUSH_SMTP_HOST)
    fun getPushSmtpPort(context: Context) = getPushString(context, KEY_PUSH_SMTP_PORT).toIntOrNull() ?: 465
    fun getPushSmtpUser(context: Context) = getPushString(context, KEY_PUSH_SMTP_USER)
    fun getPushSmtpPass(context: Context) = getPushString(context, KEY_PUSH_SMTP_PASS)
    fun getPushSmtpTo(context: Context) = getPushString(context, KEY_PUSH_SMTP_TO)
    fun setPushSmtpHost(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_HOST, v)
    fun setPushSmtpPort(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_PORT, v)
    fun setPushSmtpUser(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_USER, v)
    fun setPushSmtpPass(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_PASS, v)
    fun setPushSmtpTo(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_TO, v)
    fun getPushWebhookUrl(context: Context) = getPushString(context, KEY_PUSH_WEBHOOK_URL)
    fun getPushWebhookBody(context: Context) = getPushString(context, KEY_PUSH_WEBHOOK_BODY)
    fun setPushWebhookUrl(context: Context, v: String) = setPushString(context, KEY_PUSH_WEBHOOK_URL, v)
    fun setPushWebhookBody(context: Context, v: String) = setPushString(context, KEY_PUSH_WEBHOOK_BODY, v)
}