package com.maafw.naruto.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 把用户选中的 option 值合并成 pipeline_override JSON 喵～
 * 每个 option 的 case 可能带 pipeline_override，多个 option 要深度合并。
 */
object OptionOverrideBuilder {

    /**
     * 根据 task.option 顺序和当前选项值，合并出最终 pipeline_override JSON 字符串喵。
     *
     * 用户未配置的 option 会用 interface.json 的 default_case 填充（保持与 Maa 语义一致）：
     * - select 类型：default_case 对应的 pipeline_override
     * - input 类型：inputs[0].default 作为输入值
     */
    fun build(
        task: MaaTask,
        options: Map<String, String>,
        interfaceData: MaaInterface?
    ): String? {
        if (task.option.isNullOrEmpty()) return null

        val merged = JsonObject()
        for (optName in task.option) {
            val opt = interfaceData?.option?.get(optName) ?: continue

            // 用户未配置 → 用 default_case 填充；input 类型 → 用 inputs[0].default 喵
            val selected = options[optName]
                ?: if (opt.type == "input") opt.inputs?.firstOrNull()?.default
                else opt.defaultCase.ifBlank { opt.cases.firstOrNull()?.name }
            if (selected.isNullOrBlank()) continue

            val caseOverride = when (opt.type) {
                "input" -> resolveInputOverride(opt, selected)
                else -> opt.cases.find { it.name == selected }?.pipelineOverride
            } ?: continue

            deepMerge(merged, caseOverride)
        }

        return if (merged.size() == 0) null else merged.toString()
    }

    /**
     * input 类型选项：把 pipeline_override 里的 {输入项名} 占位符替换成用户输入的实际值喵。
     * ：input 选项的 pipeline_override 用 {inputs[0].name} 引用输入值，
     * 例如 "何时退出" 的 {"max_hit": "{周胜胜利次数}"} 会变成 {"max_hit": "5"}。
     */
    private fun resolveInputOverride(opt: MaaOption, userInput: String): JsonObject? {
        val override = opt.pipelineOverride ?: return null
        val inputs = opt.inputs.orEmpty()
        if (inputs.isEmpty()) return override

        var text = override.toString()
        inputs.forEachIndexed { idx, input ->
            // 第一个输入项使用用户输入值，其余用默认值（当前 interface.json 的 input 选项基本都只有 1 个输入项）
            val value = if (idx == 0) userInput.ifBlank { input.default } else input.default
            // 对值做 JSON 转义，避免含引号/反斜杠时破坏 JSON 结构喵
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            text = text.replace("{${input.name}}", escaped)
        }
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    }

    private fun deepMerge(target: JsonObject, source: JsonObject) {
        for ((key, value) in source.entrySet()) {
            if (!target.has(key)) {
                target.add(key, value.deepCopy())
                continue
            }
            val existing = target.get(key)
            if (existing.isJsonObject && value.isJsonObject) {
                deepMerge(existing.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value.deepCopy())
            }
        }
    }
}