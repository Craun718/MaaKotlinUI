package com.maafw.naruto.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.maafw.naruto.data.schedule.ScheduleRepository
import com.maafw.naruto.schedule.data.ScheduleStrategyRepository
import com.maafw.naruto.schedule.model.ExecutionResult
import com.maafw.naruto.service.MaaEngineService

/**
 * 定时任务闹钟接收器喵～
 *  ScheduleReceiver.kt + 保留旧 ScheduleItem 兼容：
 * - EXTRA_STRATEGY_ID 存在 → 策略触发（ 流程）
 * - 否则走旧 ScheduleItem 流程
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduleAlarmReceiver"
        const val ACTION_TRIGGER = "com.maafw.naruto.SCHEDULE_TRIGGER"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_TASK_ENTRY = "task_entry"
        const val EXTRA_STRATEGY_ID = "strategy_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return

        val strategyId = intent.getStringExtra(EXTRA_STRATEGY_ID)
        if (strategyId != null) {
            handleStrategy(context, strategyId)
            return
        }

        // 旧 ScheduleItem 流程（兼容）
        val scheduleId = intent.getIntExtra(EXTRA_SCHEDULE_ID, -1)
        Log.i(TAG, "定时任务触发：id=$scheduleId 喵")

        val item = ScheduleRepository.load(context).find { it.id == scheduleId }
        if (item != null && item.enabled) {
            ScheduleHelper.schedule(context, item)
        }

        startEngine(context, profileName = "default", forceStart = false, autoSleep = false, closeGame = false)
    }

    /** 策略触发流程（ ScheduleReceiver）喵 */
    private fun handleStrategy(context: Context, strategyId: String) {
        Log.i(TAG, "定时策略触发：strategyId=$strategyId 喵")

        val repository = ScheduleStrategyRepository(context)
        val strategy = repository.getById(strategyId)
        if (strategy == null) {
            Log.w(TAG, "策略不存在：$strategyId 喵")
            return
        }

        // 重新注册下一次闹钟（：仅启用时）
        if (strategy.enabled) {
            ScheduleHelper.scheduleStrategy(context, strategy)
        }

        if (!strategy.enabled) return

        startEngine(
            context,
            profileName = strategy.profileId,
            forceStart = strategy.forceStart,
            autoSleep = strategy.autoSleepAfterTask,
            closeGame = strategy.closeGameAfterTask,
        )

        // 记录执行结果
        repository.recordExecutionResult(strategyId, ExecutionResult.STARTED, "已触发")
    }

    private fun startEngine(
        context: Context,
        profileName: String,
        forceStart: Boolean,
        autoSleep: Boolean,
        closeGame: Boolean,
    ) {
        val serviceIntent = Intent(context.applicationContext, MaaEngineService::class.java).apply {
            putExtra("action", "run_profile")
            putExtra("profile_name", profileName)
            putExtra("force_start", forceStart)
            putExtra("auto_sleep", autoSleep)
            putExtra("close_game", closeGame)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.applicationContext.startForegroundService(serviceIntent)
        } else {
            context.applicationContext.startService(serviceIntent)
        }
    }
}