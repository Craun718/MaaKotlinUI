# Android.mk —— AIDE/ndk-build 编译 libbridge.so（与 CMakeLists.txt 同源，含 FPS 统计）
# 注意：去掉 -flto/--strip-all（保守编译），避免 LTO 优化影响 JNI upcall/线程 attach 行为
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := bridge
LOCAL_SRC_FILES := \
    bridge.cpp \
    bridge_capture.cpp \
    bridge_frame_buffer.cpp \
    bridge_preview.cpp \
    bridge_input.cpp \
    misc.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_CPPFLAGS := -std=c++17 -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden
LOCAL_CFLAGS := -O2 -ffunction-sections -fdata-sections -fvisibility=hidden
LOCAL_LDFLAGS := -Wl,--gc-sections
LOCAL_LDLIBS := -llog -landroid -ljnigraphics -lmediandk -lEGL -lGLESv2
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := launcher
LOCAL_SRC_FILES := launcher.c
LOCAL_CFLAGS := -O2 -ffunction-sections -fdata-sections
LOCAL_LDFLAGS := -Wl,--gc-sections
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)