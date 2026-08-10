package com.maafw.naruto.root;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import com.maafw.naruto.third.Ln;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Root 服务入口（app_process main）喵～
 * root 进程启动后把引擎 binder 注册到系统 ServiceManager（root 可 addService），
 * app 侧通过 ServiceManager.getService 获取——与 Shizuku 完全一致的 binder 接口。
 * 生命周期：守护 app 进程，app 退出后自动销毁引擎并退出，避免孤儿进程。
 */
public final class RootServiceStarter {

    private static final String TAG = "RootServiceStarter";
    public static final String SERVICE_NAME = "maafw_engine";
    // 对应 IRemoteEngineService.aidl 里 destroy() 的事务码
    private static final int DESTROY_TRANSACTION_CODE = 16777114;

    private static RootUserService.CreatedService activeService;

    private RootServiceStarter() {
    }

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        int appPid = -1;
        for (String arg : args) {
            if (arg.startsWith("--app-pid=")) {
                try {
                    appPid = Integer.parseInt(arg.substring(10));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        RootUserService.CreatedService createdService = RootUserService.create(args);
        if (createdService == null) {
            System.err.println("[RootServiceStarter] RootUserService.create() returned null");
            System.exit(1);
            return;
        }
        activeService = createdService;

        if (!publishService(createdService)) {
            System.err.println("[RootServiceStarter] publishService() failed");
            System.exit(1);
            return;
        }

        if (appPid > 0) {
            watchAppProcess(appPid);
        }

        Looper.loop();
        System.exit(0);
    }

    /** 把引擎 binder 注册为系统服务（uid=0 可 addService）喵 */
    private static boolean publishService(RootUserService.CreatedService createdService) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method add = smClass.getMethod("addService", String.class, IBinder.class);
            add.invoke(null, SERVICE_NAME, createdService.service);
            Ln.i(TAG + ": addService(" + SERVICE_NAME + ") ok");
            return true;
        } catch (Throwable tr) {
            Ln.e(TAG + ": addService failed", tr);
            return false;
        }
    }

    /** 守护 app 进程：app 退出后销毁引擎并退出 root 进程（复制 MAA-Meow 生命周期语义）喵 */
    private static void watchAppProcess(int appPid) {
        Thread watcher = new Thread(() -> {
            Ln.i(TAG + ": watching app pid=" + appPid);
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!isProcessAlive(appPid)) {
                    Ln.i(TAG + ": app process " + appPid + " died, destroying root service");
                    destroyService(activeService.service);
                    System.exit(0);
                    return;
                }
            }
        }, "root-app-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static boolean isProcessAlive(int pid) {
        return new File("/proc/" + pid).exists();
    }

    private static void destroyService(IBinder service) {
        if (service == null || !service.pingBinder()) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            String descriptor = service.getInterfaceDescriptor();
            if (descriptor != null) {
                data.writeInterfaceToken(descriptor);
            }
            service.transact(DESTROY_TRANSACTION_CODE, data, reply, Binder.FLAG_ONEWAY);
        } catch (Throwable tr) {
            Ln.w(TAG + ": destroy root remote service failed", tr);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}