// IRemoteEngineService.aidl
package com.maafw.naruto;

import android.content.Intent;
import android.view.Surface;
import com.maafw.naruto.IEngineStatusListener;

interface IRemoteEngineService {
    void destroy() = 16777114;

    void exit() = 1;

    String version() = 2;

    boolean setup(String userDir) = 3;

    int startVirtualDisplay() = 4;

    void stopVirtualDisplay() = 5;

    void setMonitorSurface(in Surface surface) = 6;

    boolean startTask(String entry, String pipelineOverride) = 7;

    boolean startTasksJson(String json) = 8;

    void stopTask() = 9;

    boolean isRunning() = 10;

    String currentTask() = 11;

    boolean startActivity(in Intent intent) = 12;

    boolean startActivityOnDisplay(in Intent intent, int displayId) = 18;

    boolean isPackageInstalled(String packageName) = 13;

    boolean moveAppToVirtualDisplay(String packageName) = 14;

    void setDisplayPower(boolean on) = 15;

    String captureFramePng(String dirPath) = 16;

    boolean stopPackage(String packageName) = 17;

    boolean setAudioMuted(boolean muted) = 19;

    boolean injectTouch(int action, int x, int y) = 20;

    int[] getDisplayResolution() = 21;

    void hardwareScreenOff() = 22;

    boolean setResolution(int width, int height, int dpi) = 23;

    void registerStatusListener(IEngineStatusListener listener) = 25;

    void unregisterStatusListener(IEngineStatusListener listener) = 26;

    String captureLogcat(int lines) = 27;
}