package com.maafw.naruto.third.wrappers;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import com.maafw.naruto.third.FakeContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 系统服务包装喵，用于在 shell 进程获取 DisplayManager / WindowManager 等隐藏服务。
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class ServiceManager {

    private static final Method GET_SERVICE_METHOD;

    static {
        try {
            GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
            GET_SERVICE_METHOD.setAccessible(true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static DisplayManager displayManager;
    private static WindowManager windowManager;
    private static InputManager inputManager;
    private static ActivityManager activityManager;

    public static InputManager getInputManager() {
        if (inputManager == null) {
            inputManager = InputManager.create();
        }
        return inputManager;
    }

    public static IBinder getService(String name) {
        try {
            return (IBinder) GET_SERVICE_METHOD.invoke(null, name);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static DisplayManager getDisplayManager() {
        if (displayManager == null) {
            displayManager = DisplayManager.create();
        }
        return displayManager;
    }

    public static WindowManager getWindowManager() {
        if (windowManager == null) {
            windowManager = WindowManager.create();
        }
        return windowManager;
    }

    public static ActivityManager getActivityManager() {
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }
        return activityManager;
    }

    public static abstract class Manager {
        protected final IInterface manager;

        protected Manager(IInterface manager) {
            this.manager = manager;
        }
    }
}