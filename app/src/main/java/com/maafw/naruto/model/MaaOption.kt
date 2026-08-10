package com.maafw.naruto.model

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * interface.json 中 option 字段的数据类喵～
 * 每个 option 对应一个用户可选项，case 里可以覆盖 pipeline 节点字段喵。
 */
data class MaaOption(
    val type: String = "select",
    val description: String = "",
    @SerializedName("default_case")
    val defaultCase: String = "",
    val cases: List<MaaOptionCase> = emptyList(),
    val inputs: List<MaaOptionInput>? = null,
    @SerializedName("pipeline_override")
    val pipelineOverride: JsonObject? = null
)

/**
 * option 的单个 case 喵～
 * name 是显示名，pipeline_override 用来动态修改 pipeline 节点喵。
 */
data class MaaOptionCase(
    val name: String = "",
    @SerializedName("pipeline_override")
    val pipelineOverride: JsonObject? = null
)

/**
 * input 类型 option 的输入项喵～
 */
data class MaaOptionInput(
    val name: String = "",
    val label: String = "",
    val description: String = "",
    @SerializedName("pipeline_type")
    val pipelineType: String = "string",
    val default: String = "",
    val verify: String? = null
)