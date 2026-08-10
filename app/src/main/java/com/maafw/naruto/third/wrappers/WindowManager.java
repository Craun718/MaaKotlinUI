package com.maafw.naruto.third.wrappers;

import android.annotation.SuppressLint;
import android.os.IInterface;

import java.lang.reflect.Method;

/**
 * WindowManager 隐藏 API 包装喵。
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class WindowManager {

    private final IInterface manager;
    private Method getRotationMethod;

    static WindowManager create() {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String.class);
            getServiceMethod.setAccessible(true);
            Object binder = getServiceMethod.invoke(null, "window");

            Class<?> stubClass = Class.forName("android.view.IWindowManager$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder.class);
            IInterface manager = (IInterface) asInterface.invoke(null, binder);
            return new WindowManager(manager);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private WindowManager(IInterface manager) {
        this.manager = manager;
    }

    public int getRotation() {
        try {
            if (getRotationMethod == null) {
                getRotationMethod = manager.getClass().getMethod("getRotation");
            }
            return (int) getRotationMethod.invoke(manager);
        } catch (Exception e) {
            return -1;
        }
    }
}