cmake_minimum_required(VERSION 3.22)
project(local_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp 预编译产物（hymt-unified 分支, NDK r29）由 packaging 脚本拷到
# app/src/main/cpp/prebuilt/${ANDROID_ABI}/ （include + libllama/libggml/libggml-base）
set(HT_PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})

add_library(local_engine SHARED local_engine.cpp)

target_include_directories(local_engine PRIVATE ${HT_PREBUILT}/include)

target_link_directories(local_engine PRIVATE ${HT_PREBUILT}/lib)
target_link_libraries(local_engine
    llama            # libllama.so
    ggml             # libggml.so（含 load_all_from_path 转发? 实际在 libggml-base）
    ggml-base        # libggml-base.so
    log)

# 后端变体 libggml-cpu-android_*.so 与 libomp.so 不进 link 列表：
# 运行时由 ggml_backend_load_all_from_path dlopen。
