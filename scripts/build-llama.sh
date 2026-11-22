#!/usr/bin/env bash
# Builds llama.cpp's llama-server for arm64 Android and installs it as libllama-server.so (gitignored).
# Needs: Android NDK (sdkmanager "ndk;27.2.12479018"), cmake, ninja, git.
set -euo pipefail
cd "$(dirname "$0")/.."
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/27.2.12479018}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
git clone -q --depth 1 https://github.com/ggml-org/llama.cpp "$WORK/src"
# armv8.2-a+dotprod = Snapdragon 855/865 and newer; static libc++ so no extra .so is needed.
cmake -S "$WORK/src" -B "$WORK/build" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON \
  -DCMAKE_C_FLAGS="-march=armv8.2-a+dotprod" -DCMAKE_CXX_FLAGS="-march=armv8.2-a+dotprod"
cmake --build "$WORK/build" --target llama-server -j"$(nproc)"
mkdir -p app/src/main/jniLibs/arm64-v8a
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o app/src/main/jniLibs/arm64-v8a/libllama-server.so "$WORK/build/bin/llama-server"
echo "installed libllama-server.so"
