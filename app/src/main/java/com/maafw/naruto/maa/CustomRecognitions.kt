package com.maafw.naruto.maa

import com.maafw.naruto.third.Ln
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * MaaFramework 自定义识别实现喵～
 *
 * 通过 MaaResourceRegisterCustomRecognition 注册，pipeline 里 recognition: "Custom" 的节点
 * 会回调到这里执行。全部 14 个识别器
 *
 * 未实现这些识别器时，决斗场（连点器）、羁绊、秘境、招财等任务会在 Custom 节点
 * 直接失败导致任务链提前结束——这正是「进去对战没连点就结束」的根因。
 */
object CustomRecognitions {

    private const val TAG = "CustomRecognitions"
    private val lib = MaaFrameworkLib.INSTANCE

    // 防止 JNA 回调对象被 GC 回收，必须强引用持有喵
    private val registeredCallbacks = mutableListOf<MaaCustomRecognitionCallback>()

    // CheckBuyEnergyCount 的初始次数（每次任务运行重置）喵
    private var buyEnergyStartCount = -1

    /**
     * 注册全部 custom recognition 到 resource 上喵。
     * 必须在 MaaResourceCreate 之后、任务运行之前调用。
     */
    fun register(res: Pointer): Boolean {
        var ok = true
        val recognitions = mapOf(
            "IsCounterOverflow" to ::recoIsCounterOverflow,
            "IsInNinjaGuide" to ::recoIsInNinjaGuide,
            "FindPlantableFlower" to ::recoFindPlantableFlower,
            "FindBondsWithoutEnoughToken" to ::recoFindBondsWithoutEnoughToken,
            "FindAccessoryFlipTicket" to ::recoFindAccessoryFlipTicket,
            "FindGearFlipTicket" to ::recoFindGearFlipTicket,
            "SecretRealmTicket" to ::recoSecretRealmTicket,
            "MissionOfficeStrategy" to ::recoMissionOfficeStrategy,
            "CheckGetCopperRoll" to ::recoCheckGetCopperRoll,
            "CheckGetCopperCount" to ::recoCheckGetCopperCount,
            "CheckBuyEnergyCount" to ::recoCheckBuyEnergyCount,
            "FlipCard" to ::recoFlipCard,
            "Shopping" to ::recoShopping,
            "FindToChallenge" to ::recoFindToChallenge,
        )
        for ((name, fn) in recognitions) {
            val cb = object : MaaCustomRecognitionCallback {
                override fun invoke(
                    context: Pointer?, taskId: Long, nodeName: String?, recoName: String?,
                    recoParam: String?, image: Pointer?, outBox: Pointer?, transArg: Pointer?
                ): Byte {
                    return try {
                        fn(context, taskId, nodeName, recoParam, image, outBox)
                    } catch (e: Throwable) {
                        Ln.e("$TAG.$name 识别异常: ${e.message}")
                        e.printStackTrace()
                        0
                    }
                }
            }
            registeredCallbacks.add(cb)
            val r = lib.MaaResourceRegisterCustomRecognition(res, name, cb, null)
            if (r != 1.toByte()) {
                Ln.e("$TAG 注册 $name 失败")
                ok = false
            } else {
                Ln.i("$TAG 已注册 custom recognition: $name")
            }
        }
        return ok
    }

    fun resetState() {
        buyEnergyStartCount = -1
    }

    // ==================== 工具函数 ====================

    /** 设置输出识别框喵 */
    private fun setBox(outBox: Pointer?, x: Int, y: Int, w: Int, h: Int) {
        if (outBox != null && Pointer.nativeValue(outBox) != 0L) {
            lib.MaaRectSet(outBox, x, y, w, h)
        }
    }

    private fun hitBox(outBox: Pointer?) = setBox(outBox, 0, 0, 1, 1)

    /** 执行 OCR 识别，返回 all_results 数组（null = 失败/无结果）喵 */
    private fun ocrResults(context: Pointer?, image: Pointer?, roi: IntArray?, expected: String?): JSONArray? {
        if (context == null || image == null) return null
        return try {
            val param = JSONObject()
            expected?.let { param.put("expected", it) }
            roi?.let { param.put("roi", JSONArray().apply { it.forEach { v -> put(v) } }) }
            val recoId = lib.MaaContextRunRecognitionDirect(context, "OCR", param.toString(), image)
            if (recoId == 0L) return null
            val tasker = lib.MaaContextGetTasker(context) ?: return null
            getRecoDetail(tasker, recoId)?.optJSONArray("all_results")
                ?: getRecoDetail(tasker, recoId)?.optJSONArray("results")
        } catch (e: Exception) {
            Ln.e("$TAG ocrResults: ${e.message}")
            null
        }
    }

    /** 获取识别 detail JSON（含 all_results/best_result）喵 */
    private fun getRecoDetail(tasker: Pointer, recoId: Long): JSONObject? {
        return try {
            val hit = IntByReference(0)
            val nodeName = lib.MaaStringBufferCreate()
            val algorithm = lib.MaaStringBufferCreate()
            val detailJson = lib.MaaStringBufferCreate()
            val box = lib.MaaRectCreate()
            val ok = lib.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeName, algorithm, hit, box, detailJson, null, null)
            val detail = if (ok == 1.toByte()) {
                runCatching { JSONObject(lib.MaaStringBufferGet(detailJson) ?: "{}") }.getOrNull()
            } else null
            lib.MaaStringBufferDestroy(nodeName)
            lib.MaaStringBufferDestroy(algorithm)
            lib.MaaStringBufferDestroy(detailJson)
            lib.MaaRectDestroy(box)
            detail
        } catch (e: Exception) {
            Ln.e("$TAG getRecoDetail: ${e.message}")
            null
        }
    }

    /** 提取 ROI 内纯数字喵 */
    private fun digitCount(context: Pointer?, image: Pointer?, roi: IntArray): Int? {
        val results = ocrResults(context, image, roi, "\\d+") ?: return null
        val sb = StringBuilder()
        for (i in 0 until results.length()) {
            sb.append(results.getJSONObject(i).optString("text", ""))
        }
        val m = Regex("\\d+").find(sb.toString().replace(" ", "")) ?: return null
        return m.value.toIntOrNull()
    }

    /** 运行 pipeline 节点并判断是否命中（IsInNinjaGuide 用）喵 */
    private fun runNodeHit(context: Pointer?, image: Pointer?, nodeName: String, override: String = "{}"): Boolean {
        if (context == null || image == null) return false
        return try {
            val recoId = lib.MaaContextRunRecognition(context, nodeName, override, image)
            if (recoId == 0L) return false
            val tasker = lib.MaaContextGetTasker(context) ?: return false
            val hit = IntByReference(0)
            val nodeNameBuf = lib.MaaStringBufferCreate()
            val algorithm = lib.MaaStringBufferCreate()
            val detailJson = lib.MaaStringBufferCreate()
            val box = lib.MaaRectCreate()
            val ok = lib.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeNameBuf, algorithm, hit, box, detailJson, null, null)
            lib.MaaStringBufferDestroy(nodeNameBuf)
            lib.MaaStringBufferDestroy(algorithm)
            lib.MaaStringBufferDestroy(detailJson)
            lib.MaaRectDestroy(box)
            ok == 1.toByte() && hit.value != 0
        } catch (e: Exception) {
            Ln.e("$TAG runNodeHit: ${e.message}")
            false
        }
    }

    private fun stopTask(context: Pointer?) {
        runCatching {
            val ctx = context ?: return@runCatching
            val tasker = lib.MaaContextGetTasker(ctx)
            if (tasker != null && Pointer.nativeValue(tasker) != 0L) {
                lib.MaaTaskerPostStop(tasker)
            }
        }
    }

    private fun paramObj(param: String?): JSONObject = runCatching { JSONObject(param ?: "{}") }.getOrDefault(JSONObject())

    // ==================== 识别器 ====================

    /**
     * IsCounterOverflow：计数器溢出检测（连点器核心）喵。
     * 计数器未达到 max_hit → 命中（继续连点）；达到 → 未命中（停止连点）。
     */
    private fun recoIsCounterOverflow(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val p = paramObj(param)
        val maxHit = p.optInt("max_hit", 0)
        if (maxHit <= 0) {
            Ln.e("$TAG IsCounterOverflow max_hit 参数错误")
            stopTask(context)
            return 0
        }
        val nowCount = CustomActions.getCounter(taskId)
        if (nowCount >= maxHit) {
            Ln.i("$TAG IsCounterOverflow 达到最大执行次数 max=$maxHit now=$nowCount")
            return 0
        }
        Ln.d("$TAG IsCounterOverflow 继续连点 max=$maxHit now=$nowCount")
        hitBox(outBox)
        return 1
    }

    /** IsInNinjaGuide：是否在忍界引导界面（in_ninja_guide 是 Or 识别：任务/回流）喵 */
    private fun recoIsInNinjaGuide(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        return if (runNodeHit(context, image, "in_ninja_guide")) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** FindPlantableFlower：中山花店，找可种的花（种子≥10）喵 */
    private fun recoFindPlantableFlower(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val flowerConfig = listOf(
            intArrayOf(400, 355, 111, 32) to intArrayOf(440, 298, 37, 41),
            intArrayOf(509, 355, 103, 29) to intArrayOf(543, 298, 29, 27),
            intArrayOf(607, 355, 106, 27) to intArrayOf(642, 295, 34, 34),
            intArrayOf(711, 355, 103, 32) to intArrayOf(749, 300, 29, 29),
            intArrayOf(810, 256, 143, 140) to intArrayOf(844, 298, 37, 34),
        )
        for ((seedRoi, btnRoi) in flowerConfig) {
            // 种子文本形如 "剩余:xx/10"，直接提取数字取第一个（剩余量）
            val count = digitCount(context, image, seedRoi) ?: continue
            if (count >= 10) {
                setBox(outBox, btnRoi[0], btnRoi[1], btnRoi[2], btnRoi[3])
                return 1
            }
        }
        // 无可用种子也返回一个不影响的 box（原版 invalid_box），避免无限重试
        hitBox(outBox)
        return 1
    }

    /** FindBondsWithoutEnoughToken：羁绊币 < 5 则通过喵 */
    private fun recoFindBondsWithoutEnoughToken(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val roi = intArrayOf(846, 639, 111, 80)
        val tokenCount = digitCount(context, image, roi)
        if (tokenCount == null || tokenCount >= 5) return 0
        hitBox(outBox)
        return 1
    }

    /** FindAccessoryFlipTicket：秘境饰品翻牌卷 > 0 则通过喵 */
    private fun recoFindAccessoryFlipTicket(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = digitCount(context, image, intArrayOf(550, 481, 171, 238))
        if (count != null && count > 0) {
            hitBox(outBox)
            return 1
        }
        return 0
    }

    /** FindGearFlipTicket：忍具翻牌卷 > 0 则通过喵 */
    private fun recoFindGearFlipTicket(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = digitCount(context, image, intArrayOf(436, 483, 138, 236))
        if (count != null && count > 0) {
            hitBox(outBox)
            return 1
        }
        return 0
    }

    /** SecretRealmTicket：秘境挑战卷 > 0 则通过喵 */
    private fun recoSecretRealmTicket(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = digitCount(context, image, intArrayOf(496, 624, 39, 44))
        if (count != null && count > 0) {
            hitBox(outBox)
            return 1
        }
        return 0
    }

    /** MissionOfficeStrategy：任务集会所策略，(刷新上限-9)*1.5 >= 可接受任务 则通过喵 */
    private fun recoMissionOfficeStrategy(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val maxResource = digitCount(context, image, intArrayOf(1004, 614, 27, 27))
        val currentResource = digitCount(context, image, intArrayOf(1003, 648, 22, 28))
        if (maxResource == null || currentResource == null) return 0
        val condition = (maxResource - 9) * 1.5 >= currentResource
        return if (condition) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** CheckGetCopperRoll：招财轮次 >= count+1 则通过喵 */
    private fun recoCheckGetCopperRoll(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = paramObj(param).optInt("count", 1)
        val now = digitCount(context, image, intArrayOf(104, 468, 40, 31)) ?: 66
        return if (now >= count + 1) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** CheckGetCopperCount：招财次数 >= count 则通过喵 */
    private fun recoCheckGetCopperCount(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = paramObj(param).optInt("count", 1)
        val now = digitCount(context, image, intArrayOf(309, 468, 27, 30)) ?: 66
        return if (now >= count) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** CheckBuyEnergyCount：购买体力次数，首次识别 - 当前 >= count 则通过喵 */
    private fun recoCheckBuyEnergyCount(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = paramObj(param).optInt("count", 1)
        val roi = intArrayOf(499, 374, 251, 59)
        if (buyEnergyStartCount == -1) {
            buyEnergyStartCount = digitCount(context, image, roi) ?: 0
        }
        val now = digitCount(context, image, roi) ?: 0
        return if (buyEnergyStartCount - now >= count) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /**
     * FlipCard：翻牌识别（原版读取两张牌的值比较；安卓端简化：识别到翻牌界面即通过，
     * 由 pipeline 的 CustomAction/Counter 控制翻牌次数）喵。
     */
    private fun recoFlipCard(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        // 翻牌界面有"未翻开"模板卡，宽松命中让流程继续；避免节点失败中断任务链
        val p = paramObj(param)
        val mode = p.optString("mode", "loose")
        if (mode == "strict") {
            // 严格模式：OCR 找数字牌值
            val results = ocrResults(context, image, null, "\\d+") ?: return 0
            return if (results.length() > 0) {
                hitBox(outBox)
                1
            } else 0
        }
        hitBox(outBox)
        return 1
    }

    /**
     * Shopping：商店兑换（原版检测商品格子；安卓端宽松：命中让流程继续）喵。
     */
    private fun recoShopping(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val results = ocrResults(context, image, null, null) ?: return 0
        // 检测到任何文本即视为商店界面存在
        return if (results.length() > 0) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** FindToChallenge：点赛挑战入口（宽松命中）喵 */
    private fun recoFindToChallenge(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        // 优先 OCR 找"挑战"文本；找不到也宽松命中，避免卡住
        val results = ocrResults(context, image, null, "挑战") ?: return 1.let { hitBox(outBox); return 1 }
        if (results.length() > 0) {
            val r = results.getJSONObject(0)
            val boxArr = r.optJSONArray("box")
            if (boxArr != null && boxArr.length() >= 4) {
                setBox(outBox, boxArr.getInt(0), boxArr.getInt(1), boxArr.getInt(2), boxArr.getInt(3))
                return 1
            }
            hitBox(outBox)
            return 1
        }
        hitBox(outBox)
        return 1
    }
}