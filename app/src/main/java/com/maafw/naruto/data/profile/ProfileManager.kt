package com.maafw.naruto.data.profile

import android.content.Context
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaTask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 任务配置文件管理喵～
 * 一个 profile 就是一份有序任务列表。
 */
object ProfileManager {

    private const val PROFILES_DIR = "profiles"
    const val DEFAULT_PROFILE_NAME = "default"

    data class ProfileTask(
        val entry: String,
        var enabled: Boolean = true
    )

    data class Profile(
        val name: String,
        val tasks: MutableList<ProfileTask> = mutableListOf()
    )

    private fun profilesDir(context: Context): File {
        return File(context.filesDir, PROFILES_DIR).apply { mkdirs() }
    }

    private fun profileFile(context: Context, name: String): File {
        return File(profilesDir(context), "$name.json")
    }

    /**
     * 加载默认配置文件喵。
     * 如果不存在，根据 interface.json 的 default_check 生成一份。
     */
    fun loadDefault(context: Context, interfaceData: MaaInterface?): Profile {
        val file = profileFile(context, DEFAULT_PROFILE_NAME)
        if (file.exists()) {
            return runCatching { parseProfile(file.readText()) }.getOrNull()
                ?: createDefaultProfile(context, interfaceData)
        }
        return createDefaultProfile(context, interfaceData).also { save(context, it) }
    }

    /**
     * 保存配置文件喵。
     */
    fun save(context: Context, profile: Profile) {
        val file = profileFile(context, profile.name)
        file.writeText(serializeProfile(profile))
    }

    /**
     * 根据 interface.json 创建默认配置：所有 default_check=true 的任务按原顺序加入喵。
     */
    fun createDefaultProfile(context: Context, interfaceData: MaaInterface?): Profile {
        val tasks = interfaceData?.task
            ?.filter { it.defaultCheck }
            ?.map { ProfileTask(it.entry, true) }
            ?.toMutableList()
            ?: mutableListOf()
        if (tasks.isEmpty()) {
            // fallback
            tasks.add(ProfileTask("start_up", true))
        }
        return Profile(DEFAULT_PROFILE_NAME, tasks)
    }

    /**
     * 获取所有可用的 profile 名喵。
     */
    fun listProfiles(context: Context): List<String> {
        return profilesDir(context).listFiles { _, name -> name.endsWith(".json") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 读取指定 profile 喵。
     */
    fun load(context: Context, name: String): Profile? {
        val file = profileFile(context, name)
        if (!file.exists()) return null
        return runCatching { parseProfile(file.readText()) }.getOrNull()
    }

    /**
     * 删除 profile 喵。
     */
    fun delete(context: Context, name: String): Boolean {
        if (name == DEFAULT_PROFILE_NAME) return false
        return profileFile(context, name).delete()
    }

    /** 重命名 profile 喵 */
    fun rename(context: Context, oldName: String, newName: String): Boolean {
        if (oldName == DEFAULT_PROFILE_NAME) return false
        val file = profileFile(context, oldName)
        if (!file.exists()) return false
        return file.renameTo(profileFile(context, newName))
    }

    private fun serializeProfile(profile: Profile): String {
        val arr = JSONArray()
        profile.tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("entry", t.entry)
                put("enabled", t.enabled)
            })
        }
        return JSONObject().apply {
            put("name", profile.name)
            put("tasks", arr)
        }.toString(2)
    }

    private fun parseProfile(json: String): Profile {
        val obj = JSONObject(json)
        val name = obj.optString("name", DEFAULT_PROFILE_NAME)
        val arr = obj.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<ProfileTask>()
        for (i in 0 until arr.length()) {
            val task = arr.getJSONObject(i)
            tasks.add(ProfileTask(
                entry = task.optString("entry", ""),
                enabled = task.optBoolean("enabled", true)
            ))
        }
        return Profile(name, tasks)
    }
}