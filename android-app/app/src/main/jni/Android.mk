LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := audio_share_aaudio
LOCAL_SRC_FILES := AAudioOutput.cpp
LOCAL_CPPFLAGS := -Wall -Wextra -Werror
LOCAL_LDLIBS := -laaudio -llog
include $(BUILD_SHARED_LIBRARY)
