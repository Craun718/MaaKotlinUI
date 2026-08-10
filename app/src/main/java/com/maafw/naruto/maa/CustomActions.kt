package com.maafw.naruto.maa

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.maafw.naruto.bridge.NativeBridge
import com.maafw.naruto.bridge.NativeBridgeLib
import com.maafw.naruto.third.Ln
import com.maafw.naruto.third.wrappers.InputManager
import com.maafw.naruto.third.wrappers.ServiceManager
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * MaaFramework 自定义动作实现喵～
 *
 * 通过 MaaResourceRegisterCustomAction 注册，pipeline 里 action: "Custom" 的节点
 * 会回调到这里执行。
 */
object CustomActions {

    private const val TAG = "CustomActions"
    private val lib = MaaFrameworkLib.INSTANCE

    // 防止 JNA 回调对象被 GC 回收，必须强引用持有喵
    private val registeredCallbacks = mutableListOf<MaaCustomActionCallback>()

    // CounterIncrement 用的计数器（按 task_id 区分）
    private val counters = ConcurrentHashMap<Long, Int>()

    /**
     * 注册全部 custom action 到 resource 上喵。
     * 必须在 MaaResourceCreate 之后、任务运行之前调用。
     */
    fun register(res: Pointer): Boolean {
        var ok = true
        val actions = mapOf(
            "StopTaskList" to ::actionStopTaskList,
            "RetryFailed" to ::actionRetryFailed,
            "NonlinearSwipe" to ::actionNonlinearSwipe,
            "MultiSwipeCustom" to ::actionMultiSwipeCustom,
            "GoIntoEntryByGuide" to ::actionGoIntoEntryByGuide,
            "ShopSwipeBack" to ::actionShopSwipeBack,
            "CounterIncrement" to ::actionCounterIncrement,
            "CleanupVisionImg" to ::actionCleanupVisionImg,
            "CleanupOnErrorImg" to ::actionCleanupOnErrorImg,
            "CleanupCustomImg" to ::actionCleanupCustomImg,
            "CleanupCustomLog" to ::actionCleanupCustomLog,
            "CleanupMaafwBakLogs" to ::actionCleanupMaafwBakLogs,
        )
        for ((name, fn) in actions) {
            // JNA Callback 接口不能用 Kotlin SAM lambda，必须匿名对象实现喵
            val cb = object : MaaCustomActionCallback {
                override fun invoke(
                    context: Pointer?, taskId: Long, nodeName: String?, actionName: String?,
                    actionParam: String?, recoId: Long, box: Pointer?, transArg: Pointer?
                ): Byte {
                    return try {
                        fn(context, taskId, nodeName, actionName, actionParam, recoId, box)
                    } catch (e: Throwable) {
                        Ln.e("$TAG.$name 执行异常: ${e.message}")
                        e.printStackTrace()
                        0
                    }
                }
            }
            registeredCallbacks.add(cb)
            val r = lib.MaaResourceRegisterCustomAction(res, name, cb, null)
            if (r != 1.toByte()) {
                Ln.e("$TAG 注册 $name 失败")
                ok = false
            } else {
                Ln.i("$TAG 已注册 custom action: $name")
            }
        }
        return ok
    }

    fun clearCounters() = counters.clear()

    /** 读取指定 task 的计数器值（供 CustomRecognition 使用）喵 */
    fun getCounter(taskId: Long): Int = counters[taskId] ?: 0

    // ==================== 工具函数 ====================

    /** 判断任务是否正在停止喵 */
    private fun isStopping(context: Pointer?): Boolean {
        if (context == null || Pointer.nativeValue(context) == 0L) return false
        val tasker = lib.MaaContextGetTasker(context)
        if (tasker == null || Pointer.nativeValue(tasker) == 0L) return false
        return lib.MaaTaskerStopping(tasker) == 1.toByte()
    }

    /** 停止整个任务链喵 */
    private fun stopTask(context: Pointer?) {
        runCatching {
            val ctx = context ?: return@runCatching
            val tasker = lib.MaaContextGetTasker(ctx)
            if (tasker != null && Pointer.nativeValue(tasker) != 0L) {
                lib.MaaTaskerPostStop(tasker)
            }
        }
    }

    /** 把当前帧缓冲截图转成 MaaImageBuffer（PNG编码）喵 */
    private fun captureToImageBuffer(): Pointer? {
        val bitmap = NativeBridgeLib.getFrameBufferBitmap() ?: return null
        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) return null
            val mem = Memory(bytes.size.toLong())
            mem.write(0, bytes, 0, bytes.size)
            val img = lib.MaaImageBufferCreate()
            if (img == null || Pointer.nativeValue(img) == 0L) return null
            val set = lib.MaaImageBufferSetEncoded(img, mem, bytes.size.toLong())
            if (set != 1.toByte()) {
                lib.MaaImageBufferDestroy(img)
                return null
            }
            return img
        } catch (e: Exception) {
            Ln.e("$TAG captureToImageBuffer: ${e.message}")
            return null
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    /**
     * 用 OCR 在截图中查找文本，返回 [x, y, w, h] 或 null 喵。
     * MaaFramework 的 recognition roi 默认就是绝对坐标（无相对模式），
     * 因此直接传绝对 roi 即可，不需要 PC 版的 absolutely 字段喵。
     */
    private fun ocrFind(context: Pointer?, image: Pointer?, expected: List<String>, roi: IntArray?): IntArray? {
        if (context == null || image == null) return null
        return try {
            val param = JSONObject()
            param.put("expected", JSONArray(expected))
            roi?.let { param.put("roi", JSONArray().apply { it.forEach { v -> put(v) } }) }
            val recoId = lib.MaaContextRunRecognitionDirect(context, "OCR", param.toString(), image)
            if (recoId == 0L) return null
            val tasker = lib.MaaContextGetTasker(context)
            if (tasker == null || Pointer.nativeValue(tasker) == 0L) return null
            extractRecoBox(tasker, recoId)
        } catch (e: Exception) {
            Ln.e("$TAG ocrFind: ${e.message}")
            null
        }
    }

    /** 从 reco_id 提取命中框 [x,y,w,h]，未命中返回 null 喵 */
    private fun extractRecoBox(tasker: Pointer, recoId: Long): IntArray? {
        return try {
            val hit = IntByReference(0)
            val nodeName = lib.MaaStringBufferCreate()
            val algorithm = lib.MaaStringBufferCreate()
            val detailJson = lib.MaaStringBufferCreate()
            val box = lib.MaaRectCreate()
            val ok = lib.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeName, algorithm, hit, box, detailJson, null, null)
            if (ok != 1.toByte() || hit.value == 0) {
                lib.MaaStringBufferDestroy(nodeName)
                lib.MaaStringBufferDestroy(algorithm)
                lib.MaaStringBufferDestroy(detailJson)
                lib.MaaRectDestroy(box)
                return null
            }
            val r = intArrayOf(
                lib.MaaRectGetX(box), lib.MaaRectGetY(box),
                lib.MaaRectGetW(box), lib.MaaRectGetH(box)
            )
            lib.MaaStringBufferDestroy(nodeName)
            lib.MaaStringBufferDestroy(algorithm)
            lib.MaaStringBufferDestroy(detailJson)
            lib.MaaRectDestroy(box)
            r
        } catch (e: Exception) {
            Ln.e("$TAG extractRecoBox: ${e.message}")
            null
        }
    }

    /** 点击（带随机偏移，）喵 */
    private fun click(context: Pointer?, x: Int, y: Int, w: Int = 1, h: Int = 1) {
        if (context == null) return
        val rx = if (w > 1) x + Random.nextInt(w) else x
        val ry = if (h > 1) y + Random.nextInt(h) else y
        val rect = lib.MaaRectCreate()
        lib.MaaRectSet(rect, 0, 0, 0, 0)
        lib.MaaContextRunActionDirect(context, "Click", "{\"x\":$rx,\"y\":$ry}", rect, "")
        lib.MaaRectDestroy(rect)
    }

    /** 线性滑动（fast_swipe）喵 */
    private fun fastSwipe(context: Pointer?, sx: Int, sy: Int, ex: Int, ey: Int, duration: Int = 150) {
        if (context == null) return
        val rect = lib.MaaRectCreate()
        lib.MaaRectSet(rect, 0, 0, 0, 0)
        lib.MaaContextRunActionDirect(
            context, "Swipe",
            "{\"begin\":[$sx,$sy],\"end\":[$ex,$ey],\"duration\":$duration}",
            rect, ""
        )
        lib.MaaRectDestroy(rect)
    }

    /**
     * 非线性滑动喵。
     * 利用 MaaFramework Swipe 动作的多途径点能力：先 override 一个 custom_swipe 节点再执行。
     */
    private fun nonlinearSwipe(
        context: Pointer?,
        startX: Int, startY: Int, endX: Int, endY: Int,
        duration: Int = 150, endHold: Boolean = false,
        afterSwipeDelay: Int = 300, steps: Int = 7
    ) {
        if (context == null) return
        val sX = startX + Random.nextInt(-50, 51)
        val sY = startY + Random.nextInt(-50, 51)
        val eX = endX + Random.nextInt(-50, 51)
        val eY = endY + Random.nextInt(-50, 51)
        val totalDur = duration + Random.nextInt(-100, 101)
        val hold = if (endHold) Random.nextInt(100, 201) else 0

        val points = JSONArray()
        val durs = JSONArray()
        var totalProg = 0.0
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val prog = 1 - (1 - t) * (1 - t)
            val delta = prog - totalProg
            totalProg = prog
            val cx = (sX + (eX - sX) * prog).toInt()
            val cy = (sY + (eY - sY) * prog).toInt()
            points.put(JSONArray().put(cx).put(cy))
            durs.put((totalDur * delta).toInt())
        }
        // 修正总时长误差
        val sumDurs = (0 until durs.length()).sumOf { durs.getInt(it) }
        durs.put(durs.length() - 1, durs.getInt(durs.length() - 1) + (totalDur - sumDurs))

        val override = JSONObject().put(
            "custom_swipe", JSONObject()
                .put("action", "Swipe")
                .put("begin", JSONArray().put(sX).put(sY))
                .put("end", points)
                .put("end_hold", hold)
                .put("duration", durs)
        )
        val rect = lib.MaaRectCreate()
        lib.MaaRectSet(rect, 0, 0, 0, 0)
        lib.MaaContextRunAction(context, "custom_swipe", override.toString(), rect, "")
        lib.MaaRectDestroy(rect)
        SystemClock.sleep(afterSwipeDelay.toLong())
    }

    /** 等待画面稳定喵 */
    private fun waitFreezes(context: Pointer?, time: Long = 200) {
        if (context == null) return
        val rect = lib.MaaRectCreate()
        lib.MaaRectSet(rect, 0, 0, 0, 0)
        lib.MaaContextWaitFreezes(context, time, rect, "{}")
        lib.MaaRectDestroy(rect)
    }

    // ==================== 多指滑动（MultiSwipe 替代实现） ====================
    // MaaFramework 的 AndroidNative controller 官方只支持单点触摸（"native android controller
    // only supports single touch"），MultiSwipe 多指会被拒绝；这里在引擎进程直接用
    // InputManager.injectInputEvent 注入多指针 MotionEvent，绕过该限制喵。

    private data class FingerSwipe(
        val bx: Float, val by: Float, val ex: Float, val ey: Float, val dur: Long
    )

    /** 解析 swipes 数组为每根手指的滑动规格喵 */
    private fun parseFingers(swipes: JSONArray): List<FingerSwipe> {
        val fingers = mutableListOf<FingerSwipe>()
        for (i in 0 until swipes.length()) {
            val s = swipes.optJSONObject(i) ?: continue
            val begin = s.optJSONArray("begin") ?: continue
            val end = s.optJSONArray("end") ?: continue
            if (begin.length() < 2 || end.length() < 2) continue
            // [x, y, w, h] 取中心点
            val bx = begin.optInt(0) + begin.optInt(2) / 2f
            val by = begin.optInt(1) + begin.optInt(3) / 2f
            val ex = end.optInt(0) + end.optInt(2) / 2f
            val ey = end.optInt(1) + end.optInt(3) / 2f
            val dur = s.optLong("duration", 150L)
            fingers.add(FingerSwipe(bx, by, ex, ey, dur))
        }
        return fingers
    }

    /** 注入一个多指针 MotionEvent 喵 */
    private fun injectPointers(
        displayId: Int, downTime: Long, eventTime: Long,
        action: Int, coords: List<Pair<Float, Float>>, actionIndex: Int = -1
    ): Boolean {
        val n = coords.size
        if (n == 0) return false
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val cs = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = coords[i].first
                y = coords[i].second
                pressure = 1f
                size = 1f
            }
        }
        var act = action
        if (actionIndex >= 0 && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)) {
            act = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        val ev = MotionEvent.obtain(
            downTime, eventTime, act, n, props, cs,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        return runCatching {
            InputManager.setDisplayId(ev, displayId)
            ServiceManager.getInputManager().injectInputEvent(
                ev, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
            )
        }.getOrDefault(false)
    }

    /** 执行多指滑动：所有手指同时按下 → 逐步移动 → 依次抬起喵 */
    private fun multiSwipeInject(swipes: JSONArray) {
        val displayId = NativeBridge.getDisplayId()
        if (displayId < 0 || swipes.length() == 0) {
            Ln.w("$TAG multiSwipeInject: displayId=$displayId swipes=${swipes.length()}")
            return
        }
        val fingers = parseFingers(swipes)
        if (fingers.isEmpty()) return
        val maxDur = fingers.maxOf { it.dur }.coerceAtLeast(50L)

        val downTime = SystemClock.uptimeMillis()
        val startCoords = fingers.map { it.bx to it.by }

        // 1) 手指 0 DOWN
        injectPointers(displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, listOf(startCoords[0]))
        // 2) 手指 1..n POINTER_DOWN（逐指加入）
        for (i in 1 until fingers.size) {
            injectPointers(
                displayId, downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_DOWN, startCoords.subList(0, i + 1), actionIndex = i
            )
            SystemClock.sleep(10)
        }
        // 3) 分步 MOVE（所有手指按各自轨迹插值移动）
        val steps = (maxDur / 50).toInt().coerceIn(4, 20)
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val coords = fingers.map { (it.bx + (it.ex - it.bx) * t) to (it.by + (it.ey - it.by) * t) }
            injectPointers(displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, coords)
            SystemClock.sleep((maxDur / steps).coerceAtLeast(10))
        }
        // 4) 手指 n..1 POINTER_UP（逆序抬起）
        for (i in fingers.size - 1 downTo 1) {
            val coords = fingers.subList(0, i + 1).map { it.ex to it.ey }
            injectPointers(
                displayId, downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_UP, coords, actionIndex = i
            )
            SystemClock.sleep(10)
        }
        // 5) 手指 0 UP
        injectPointers(displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf(fingers[0].ex to fingers[0].ey))
        Ln.i("$TAG multiSwipeInject: ${fingers.size}指 时长=${maxDur}ms displayId=$displayId")
    }

    // ==================== Custom Actions ====================

    /** StopTaskList：停止当前任务以及后续任务列表喵 */
    private fun actionStopTaskList(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        stopTask(context)
        return 0 // 与 PC 版一致：post_stop + success=False
    }

    /** RetryFailed：重试失败，PC 版做校验/截图；这里返回成功即可喵 */
    private fun actionRetryFailed(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        return 1
    }

    /** NonlinearSwipe：非线性滑动喵 */
    private fun actionNonlinearSwipe(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        return try {
            val p = param?.let { JSONObject(it) } ?: JSONObject()
            nonlinearSwipe(
                context,
                p.optInt("start_x", 0), p.optInt("start_y", 0),
                p.optInt("end_x", 0), p.optInt("end_y", 0),
                duration = p.optInt("duration", 150),
                endHold = p.optBoolean("end_hold", false),
                afterSwipeDelay = p.optInt("after_swipe_delay", 300),
                steps = p.optInt("steps", 7),
            )
            1
        } catch (e: Exception) {
            Ln.e("$TAG NonlinearSwipe 失败: ${e.message}")
            0
        }
    }

    /**
     * MultiSwipeCustom：多指同时滑动（组合技）喵。
     * 替代 MaaFramework 的 MultiSwipe（AndroidNative controller 不支持多指），
     * 在引擎进程直接注入多指针 MotionEvent 到虚拟屏。
     * param: {"swipes": [{"begin":[x,y,w,h],"end":[x,y,w,h],"duration":ms}, ...]}
     */
    private fun actionMultiSwipeCustom(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        return try {
            val p = param?.let { JSONObject(it) } ?: JSONObject()
            val swipes = p.optJSONArray("swipes")
            if (swipes == null || swipes.length() == 0) {
                Ln.w("$TAG MultiSwipeCustom: swipes 为空（node=$nodeName）")
                return 1
            }
            multiSwipeInject(swipes)
            1
        } catch (e: Exception) {
            Ln.e("$TAG MultiSwipeCustom 失败: ${e.message}")
            0
        }
    }

    /** GoIntoEntryByGuide：从忍界指引进入特定功能喵 */
    private fun actionGoIntoEntryByGuide(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        if (context == null) return 0
        return try {
            val p = param?.let { JSONObject(it) } ?: JSONObject()
            var enterName = p.opt("entry_name")
            if (enterName == null || enterName.toString().isBlank()) {
                stopTask(context)
                return 0
            }
            val names = if (enterName is JSONArray) {
                (0 until enterName.length()).map { enterName.optString(it) }
            } else listOf(enterName.toString())

            var start = intArrayOf(0, 0)
            var end = intArrayOf(0, 0)
            var listRoi = intArrayOf(26, 60, 404, 616)

            val img = captureToImageBuffer() ?: return 0
            try {
                val box1 = ocrFind(context, img, listOf("回流"), intArrayOf(0, 0, 195, 285))
                if (box1 == null) {
                    // 非回归账号
                    start = intArrayOf(70, 600)
                    end = intArrayOf(70, 300)
                    listRoi = intArrayOf(0, 66, 219, 627)
                } else {
                    // 回归账号
                    start = intArrayOf(300, 600)
                    end = intArrayOf(300, 300)
                    listRoi = intArrayOf(209, 88, 200, 580)
                    val box2 = ocrFind(context, img, listOf("忍界指引"), intArrayOf(0, 600, 212, 120))
                    if (box2 == null) return 0
                    click(context, box2[0], box2[1], box2[2], box2[3])
                }
            } finally {
                lib.MaaImageBufferDestroy(img)
            }

            waitFreezes(context, 300)
            if (isStopping(context)) return 0

            // 滑到最顶端（识别"天赋"）
            var guard = 0
            while (guard++ < 30) {
                if (isStopping(context)) return 0
                val img2 = captureToImageBuffer() ?: break
                val hitTalent = try {
                    ocrFind(context, img2, listOf("天赋"), listRoi) != null
                } finally {
                    lib.MaaImageBufferDestroy(img2)
                }
                if (hitTalent) break
                nonlinearSwipe(context, end[0], end[1], start[0], start[1], endHold = false)
            }

            // 查找功能入口
            var foundBox: IntArray? = null
            for (i in 0 until 20) {
                if (isStopping(context)) return 0
                val img3 = captureToImageBuffer() ?: break
                foundBox = try {
                    ocrFind(context, img3, names, listRoi)
                } finally {
                    lib.MaaImageBufferDestroy(img3)
                }
                if (foundBox != null) break
                nonlinearSwipe(context, start[0], start[1], end[0], end[1])
            }
            if (foundBox == null) return 0
            if (isStopping(context)) return 0

            click(context, foundBox[0], foundBox[1], foundBox[2], foundBox[3])
            SystemClock.sleep(500)

            val img4 = captureToImageBuffer() ?: return 0
            val goBox = try {
                ocrFind(context, img4, listOf("前往"), intArrayOf(834, 539, 287, 149))
            } finally {
                lib.MaaImageBufferDestroy(img4)
            }
            if (goBox == null) return 0
            click(context, goBox[0], goBox[1], goBox[2], goBox[3])
            1
        } catch (e: Exception) {
            Ln.e("$TAG GoIntoEntryByGuide 失败: ${e.message}")
            0
        }
    }

    /** ShopSwipeBack：商店兑换滑动回商品头部喵 */
    private fun actionShopSwipeBack(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        return try {
            if (context != null) {
                runCatching { lib.MaaContextClearHitCount(context, "shop_swipe_for_goods") }
                fastSwipe(context, 280, 409, 1200, 404)
            }
            1
        } catch (e: Exception) {
            Ln.e("$TAG ShopSwipeBack 失败: ${e.message}")
            0
        }
    }

    /** CounterIncrement：计数器自增（按 task_id）喵 */
    private fun actionCounterIncrement(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        counters.merge(taskId, 1) { a, b -> a + b }
        return 1
    }

    /** Cleanup 系列：清理文件，返回成功喵 */
    private fun actionCleanupVisionImg(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("vision") ?: 1
    private fun actionCleanupOnErrorImg(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("on_error") ?: 1
    private fun actionCleanupCustomImg(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("custom_img") ?: 1
    private fun actionCleanupCustomLog(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("custom_log") ?: 1
    private fun actionCleanupMaafwBakLogs(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("bak") ?: 1

    /** 清理 files 下指定子目录里的文件（保留目录本身）喵 */
    private fun cleanupDir(sub: String): Byte? {
        return try {
            val dir = File(maaFilesDir ?: return null, sub)
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
            1
        } catch (e: Exception) {
            Ln.e("$TAG cleanupDir($sub) 失败: ${e.message}")
            null
        }
    }

    @Volatile
    var maaFilesDir: File? = null
        private set

    fun setFilesDir(dir: File?) {
        maaFilesDir = dir
    }
}