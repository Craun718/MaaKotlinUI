package com.maafw.naruto.bridge

import android.graphics.Bitmap
import android.view.Surface

/**
 * 原生桥 Java 层入口喵～
 * 对应 libbridge.so 中 JNI 注册的 NativeBridgeLib 类。
 */
object NativeBridgeLib {

    @JvmField
    var LOADED: Boolean = false

    init {
        LOADED = try {
            System.loadLibrary("bridge")
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JvmStatic
    external fun ping(): String

    @JvmStatic
    external fun setupNativeCapturer(width: Int, height: Int): Surface?

    @JvmStatic
    external fun releaseNativeCapturer()

    @JvmStatic
    external fun setPreviewSurface(surface: Any?)

    @JvmStatic
    external fun getFrameBufferBitmap(): Bitmap

    @JvmStatic
    external fun getFrameCount(): Long

    /**
     * 切换进程 UID（root 引擎发广播前临时降权到 App uid，发完提权回 root）喵。
     * @return setresuid 返回值（0=成功）
     */
    @JvmStatic
    external fun setResUid(ruid: Int, euid: Int, suid: Int): Int
}