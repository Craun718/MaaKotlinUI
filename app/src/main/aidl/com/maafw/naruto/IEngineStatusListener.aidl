// IEngineStatusListener.aidl
package com.maafw.naruto;

interface IEngineStatusListener {
    void onStatusChanged(boolean running, String currentEntry) = 1;

    void onLog(String message) = 2;
}
