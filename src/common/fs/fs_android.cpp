// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "common/android/android_common.h"
#include "common/android/id_cache.h"
#include "common/assert.h"
#include "common/fs/fs_android.h"
#include "common/string_util.h"

namespace Common::FS::Android {

void RegisterCallbacks(JNIEnv* env, jclass clazz) {
    env->GetJavaVM(&g_jvm);
    native_library = clazz;

    s_get_parent_directory = env->GetStaticMethodID(native_library, "getParentDirectory",
                                                    "(Ljava/lang/String;)Ljava/lang/String;");
    s_get_filename = env->GetStaticMethodID(native_library, "getFilename",
                                            "(Ljava/lang/String;)Ljava/lang/String;");
    s_get_size = env->GetStaticMethodID(native_library, "getSize", "(Ljava/lang/String;)J");
    s_is_directory = env->GetStaticMethodID(native_library, "isDirectory", "(Ljava/lang/String;)Z");
    s_file_exists = env->GetStaticMethodID(native_library, "exists", "(Ljava/lang/String;)Z");
    s_open_content_uri = env->GetStaticMethodID(native_library, "openContentUri",
                                                "(Ljava/lang/String;Ljava/lang/String;)I");
    s_get_directory_entries = env->GetStaticMethodID(
        native_library, "getDirectoryEntries", "(Ljava/lang/String;)[Ljava/lang/String;");
}

void UnRegisterCallbacks() {
    s_get_parent_directory = nullptr;
    s_get_filename = nullptr;

    s_get_size = nullptr;
    s_is_directory = nullptr;
    s_file_exists = nullptr;

    s_open_content_uri = nullptr;
    s_get_directory_entries = nullptr;
}

bool IsContentUri(const std::string& path) {
    constexpr std::string_view prefix = "content://";
    if (path.size() < prefix.size()) [[unlikely]] {
        return false;
    }

    return path.find(prefix) == 0;
}

s32 OpenContentUri(const std::string& filepath, OpenMode openmode) {
    if (s_open_content_uri == nullptr)
        return -1;

    const char* mode = "";
    switch (openmode) {
    case OpenMode::Read:
        mode = "r";
        break;
    default:
        UNIMPLEMENTED();
        return -1;
    }
    auto env = Common::Android::GetEnvForThread();
    jstring j_filepath = Common::Android::ToJString(env, filepath);
    jstring j_mode = Common::Android::ToJString(env, mode);
    const s32 fd = env->CallStaticIntMethod(native_library, s_open_content_uri, j_filepath, j_mode);
    env->DeleteLocalRef(j_filepath);
    env->DeleteLocalRef(j_mode);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return fd;
}

u64 GetSize(const std::string& filepath) {
    if (s_get_size == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    auto j_path = Common::Android::ToJString(env, filepath);
    const auto size = static_cast<u64>(env->CallStaticLongMethod(native_library, s_get_size, j_path));
    env->DeleteLocalRef(j_path);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    return size;
}

bool IsDirectory(const std::string& filepath) {
    if (s_is_directory == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    auto j_path = Common::Android::ToJString(env, filepath);
    const bool is_directory = env->CallStaticBooleanMethod(native_library, s_is_directory, j_path);
    env->DeleteLocalRef(j_path);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return is_directory;
}

bool Exists(const std::string& filepath) {
    if (s_file_exists == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    auto j_path = Common::Android::ToJString(env, filepath);
    const bool exists = env->CallStaticBooleanMethod(native_library, s_file_exists, j_path);
    env->DeleteLocalRef(j_path);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return exists;
}

std::string GetParentDirectory(const std::string& filepath) {
    if (s_get_parent_directory == nullptr) {
        return {};
    }
    auto env = Common::Android::GetEnvForThread();
    auto j_path = Common::Android::ToJString(env, filepath);
    jstring j_return = static_cast<jstring>(
        env->CallStaticObjectMethod(native_library, s_get_parent_directory, j_path));
    env->DeleteLocalRef(j_path);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
    }
    if (!j_return) {
        return {};
    }
    auto result = Common::Android::GetJString(env, j_return);
    env->DeleteLocalRef(j_return);
    return result;
}

std::string GetFilename(const std::string& filepath) {
    if (s_get_filename == nullptr) {
        return {};
    }
    auto env = Common::Android::GetEnvForThread();
    auto j_path = Common::Android::ToJString(env, filepath);
    jstring j_return = static_cast<jstring>(
        env->CallStaticObjectMethod(native_library, s_get_filename, j_path));
    env->DeleteLocalRef(j_path);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
    }
    if (!j_return) {
        return {};
    }
    auto result = Common::Android::GetJString(env, j_return);
    env->DeleteLocalRef(j_return);
    return result;
}


std::vector<std::string> GetDirectoryEntries(const std::string& filepath) {
    std::vector<std::string> entries;
    if (!s_get_directory_entries) {
        return entries;
    }
    auto* env = Common::Android::GetEnvForThread();
    if (!env) {
        return entries;
    }
    jstring j_path = Common::Android::ToJString(env, filepath);
    auto* j_entries = static_cast<jobjectArray>(
        env->CallStaticObjectMethod(native_library, s_get_directory_entries, j_path));
    env->DeleteLocalRef(j_path);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return entries;
    }
    if (j_entries == nullptr) {
        return entries;
    }
    const jsize count = env->GetArrayLength(j_entries);
    entries.reserve(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto j_item = static_cast<jstring>(env->GetObjectArrayElement(j_entries, i));
        if (j_item != nullptr) {
            entries.emplace_back(Common::Android::GetJString(env, j_item));
            env->DeleteLocalRef(j_item);
        }
    }
    env->DeleteLocalRef(j_entries);
    return entries;
}

} // namespace Common::FS::Android
