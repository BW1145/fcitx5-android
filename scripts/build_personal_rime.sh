#!/usr/bin/env bash
set -euo pipefail
abi="$1"
root="$(pwd)"
cmake="$ANDROID_HOME/cmake/3.31.6/bin/cmake"
ndk="$ANDROID_HOME/ndk/28.0.13004108"
paths=""
for dep in boost glog yaml-cpp leveldb marisa opencc lua; do
  paths+="$root/prebuilt/$dep/$abi;"
done
"$cmake" -S build/personal-rime/librime -B "build/personal-rime/$abi" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-23 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC=ON -DBUILD_TEST=OFF -DBUILD_TOOLS=OFF \
  -DALSO_LOG_TO_STDERR=ON -DCMAKE_FIND_ROOT_PATH="$paths" \
  -DCMAKE_INSTALL_PREFIX="$root/prebuilt/librime/$abi" \
  -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$root=. -DBOOST_DISABLE_CURRENT_LOCATION -DBOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS"
"$cmake" --build "build/personal-rime/$abi" --parallel 4
"$cmake" --install "build/personal-rime/$abi"
