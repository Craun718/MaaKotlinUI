package com.maafw.naruto.data.log

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出工具喵～
 * 把引擎实时写入的 maafw.log 复制到公共目录 /storage/emulated/0/maa日志，
 * 文件名带时间戳，确保每次导出都是最新的一份、不会被上次的覆盖。
 */
object LogExporter {

    const val LOG_SUB_DIR = "maa_logs"
    const val LOG_FILE_NAME = "maafw.log"
    const val EXPORT_DIR_NAME = "maa日志"

    /** 源日志文件（应用外部私有目录） */
    fun sourceLogFile(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        return File(base, "$LOG_SUB_DIR/$LOG_FILE_NAME")
    }

    /** 是否有写入公共存储的权限（Android 11+ 需要"所有文件访问"） */
    fun hasStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    /** 导出目录：优先公共目录 /storage/emulated/0/maa日志，无权限时 fallback 应用私有目录喵 */
    private fun targetDir(context: Context): File {
        val publicDir = File(Environment.getExternalStorageDirectory(), EXPORT_DIR_NAME)
        return if (publicDir.exists() || publicDir.mkdirs()) {
            publicDir
        } else {
            File(context.getExternalFilesDir(null), "maa_logs_export").apply { mkdirs() }
        }
    }

    /**
     * 导出最新日志到 /storage/emulated/0/maa日志/maafw_yyyyMMdd_HHmmss.log
     * @return 导出的文件绝对路径
     */
    fun exportLatest(context: Context): Result<String> {
        val src = sourceLogFile(context)
            ?: return Result.failure(IllegalStateException("无法获取应用外部存储目录"))
        if (!src.exists() || !src.isFile) {
            return Result.failure(IllegalStateException("日志文件不存在：${src.absolutePath}"))
        }

        // 引擎日志是异步写入的，稍等片刻让队列 flush，确保复制到最新内容喵
        Thread.sleep(400)

        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "maafw_$ts.log")
        src.copyTo(dest, overwrite = false)
        return Result.success(dest.absolutePath)
    }

    /** 导出应用侧日志（MainActivity 运行日志），即使 MaaFW 没跑也能导出喵 */
    fun exportAppLog(context: Context, appLog: List<String>): Result<String> {
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "app_$ts.log")
        runCatching { dest.writeText(appLog.joinToString("\n")) }
            .getOrElse { return Result.failure(it) }
        return Result.success(dest.absolutePath)
    }

    /** 把给定的 logcat 文本写入导出文件（引擎侧抓取的全量日志）喵 */
    fun exportLogcatText(context: Context, logcatText: String): Result<String> {
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "logcat_$ts.log")
        runCatching { dest.writeText(logcatText) }
            .getOrElse { return Result.failure(it) }
        return Result.success(dest.absolutePath)
    }

    /** 导出 logcat 系统日志（含引擎 Native 日志/异常堆栈）喵 */
    fun exportLogcat(context: Context): Result<String> {
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "logcat_$ts.log")
        return runCatching {
            val text = runCatching { execLogcat("") }
                .getOrElse { err1 ->
                    // app 进程无权限时尝试 su（root）抓取喵
                    runCatching { execLogcat("su -c ") }
                        .getOrElse { err2 ->
                            "（无法读取 logcat：${err1.message ?: "权限不足"}；${err2.message ?: ""} 请连接引擎或授予 root 权限）"
                        }
                }
            dest.writeText(text)
            dest.absolutePath
        }
    }

    private fun execLogcat(prefix: String): String {
        val cmd = arrayOf("sh", "-c", "$prefix logcat -d -t 2000 -v time")
        val proc = Runtime.getRuntime().exec(cmd)
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // 保留关键行：本应用相关 + 崩溃 + 引擎 native 日志喵
        val keywords = listOf(
            "maafw", "maafw", "MaaFW", "MaaFWRemote", "RemoteEngine",
            "MainActivity", "AndroidRuntime", "FATAL", "InputInjector",
            "CustomActions", "CustomRecognitions", "libbridge", "MaaFramework",
            "ScheduleAlarm", "MaaEngineService", "NativeBridge"
        )
        val filtered = text.lineSequence()
            .filter { line -> keywords.any { line.contains(it, ignoreCase = true) } }
            .joinToString("\n")
        return if (filtered.isBlank()) "（logcat 中暂无本应用相关输出）" else filtered
    }

    /** 源日志的最后修改时间（用于确认导出的是最新日志） */
    fun sourceLastModifiedText(context: Context): String? {
        val src = sourceLogFile(context) ?: return null
        if (!src.exists()) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(src.lastModified()))
    }

    /** 源日志文件大小（KB） */
    fun sourceSizeKb(context: Context): Long? {
        val src = sourceLogFile(context) ?: return null
        if (!src.exists()) return null
        return src.length() / 1024
    }
}