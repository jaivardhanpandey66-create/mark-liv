#!/usr/bin/env bash
# ===========================================================================
#  build_apk.sh — MARK LIV offline Android build
#
#  Uses the Android SDK build-tools directly (aapt2 + javac + d8 + apksigner),
#  no Gradle and no Android Studio required.  Output: dist/MARK-LIV.apk
#
#  The APK bundles:
#    * the WebView UI            (assets/web)
#    * the rule brain            (assets/web/offline_brain.js)
#    * a Qwen GGUF model         (assets/models/*.gguf)
#    * llama.cpp built for arm64 + x86_64, wrapped in libmarkliv.so
#
#  The local brain needs no network.  INTERNET is declared only for the
#  optional cloud path: the user supplies an endpoint + API key at runtime
#  (Qwen/DashScope, OpenRouter, Groq, any OpenAI-compatible API).  No key is
#  compiled in, and the app is fully functional offline without one.
# ===========================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$HERE/app"
OUT="$HERE/build"
DIST="$HERE/dist"
SDK="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
NDK_VER="${NDK_VER:-27.0.12077973}"
LLAMA_SRC="${LLAMA_SRC:-/home/jai/llama.cpp}"
ABIS="${ABIS:-arm64-v8a}"
BT_VER="${BT_VER:-35.0.1}"
PLATFORM="${PLATFORM:-android-35}"

BT="$SDK/build-tools/$BT_VER"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
NDK="$SDK/ndk/$NDK_VER"
KEYSTORE="$HERE/markliv.keystore"

say() { printf '\033[1;33m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

[ -d "$BT" ]            || die "missing $BT  (sdkmanager \"build-tools;$BT_VER\")"
[ -f "$ANDROID_JAR" ]   || die "missing $ANDROID_JAR  (sdkmanager \"platforms;$PLATFORM\")"
[ -d "$NDK" ]           || die "missing NDK $NDK  (sdkmanager \"ndk;$NDK_VER\")"
[ -d "$LLAMA_SRC" ]     || die "missing llama.cpp at $LLAMA_SRC (git clone --depth 1 https://github.com/ggml-org/llama.cpp)"

# ---------------------------------------------------------------- 0. clean
rm -rf "$OUT"
mkdir -p "$OUT"/{res,gen,classes,dex,assets,lib,dist}
DIST="$OUT/dist"; mkdir -p "$DIST"

# ------------------------------------------------- 1. native: libmarkliv.so
build_native() {
  local abi="$1"
  local clang="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  [ -d "$clang" ] || die "NDK toolchain missing at $clang"

  local libdir="$OUT/lib/$abi"
  mkdir -p "$libdir"

  # llama.cpp shared libs for this ABI (built once by build_llama.sh, or here)
  local llama_build="$LLAMA_SRC/build-$abi"
  if [ ! -d "$llama_build" ]; then
    say "configuring llama.cpp for $abi"
    "$SDK/cmake/3.22.1/bin/cmake" -S "$LLAMA_SRC" -B "$llama_build" \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 \
      -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
      -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DGGML_NATIVE=OFF \
      -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF \
      -DLLAMA_BUILD_TOOLS=OFF > "$OUT/cmake-$abi.log" 2>&1
  fi
  if [ ! -f "$llama_build/src/libllama.so" ]; then
    say "building llama.cpp for $abi"
    "$SDK/cmake/3.22.1/bin/cmake" --build "$llama_build" -j"$(nproc)" --target llama \
      > "$OUT/ninja-$abi.log" 2>&1
  fi

  local lp
  lp="$(find "$llama_build" -name 'libllama.so' -o -name 'libggml*.so' | sort -u)"
  [ -n "$lp" ] || die "llama.cpp build produced no shared libs for $abi (see $OUT/ninja-$abi.log)"

  say "compiling JNI bridge for $abi"
  local inc=()
  while read -r so; do
    cp -f "$so" "$libdir/"
    inc+=("-I$(dirname "$so")")
  done <<< "$lp"

  # d8 wants the .so files as plain libraries on the link line
  local objs=()
  while read -r so; do objs+=("-L$(dirname "$so")"); done <<< "$lp"

  "$clang/clang++" --target="aarch64-linux-android24" -O3 -fPIC -shared -std=c++17 \
      -I"$LLAMA_SRC/include" -I"$LLAMA_SRC/ggml/include" "${inc[@]}" \
      -DGGML_USE_CPU \
      "$APP/jni/llama_jni.cpp" -o "$libdir/libmarkliv.so" \
      -llama -lggml -lggml-base -lggml-cpu -Wl,-rpath,'$ORIGIN' 2>/dev/null \
    || "$clang/clang++" --target="aarch64-linux-android24" -O3 -fPIC -shared -std=c++17 \
      -I"$LLAMA_SRC/include" -I"$LLAMA_SRC/ggml/include" "${inc[@]}" \
      "$APP/jni/llama_jni.cpp" -o "$libdir/libmarkliv.so" \
      -Wl,--no-undefined -Wl,-rpath,'$ORIGIN' -L"$libdir" -llama -lggml -lggml-base -lggml-cpu

  # x86_64 uses a different target triple
  if [ "$abi" = "x86_64" ]; then
    "$clang/clang++" --target=x86_64-linux-android24 -O3 -fPIC -shared -std=c++17 \
      -I"$LLAMA_SRC/include" -I"$LLAMA_SRC/ggml/include" "${inc[@]}" \
      "$APP/jni/llama_jni.cpp" -o "$libdir/libmarkliv.so" \
      -Wl,-rpath,'$ORIGIN' -L"$libdir" -llama -lggml -lggml-base -lggml-cpu
  fi
  say "native ok: $libdir"
}

case "$(uname -m)" in
  x86_64) NATIVE_TARGET=x86_64-linux-android24; NATIVE_LD=x86_64-linux-android24 ;;
  aarch64) NATIVE_TARGET=aarch64-linux-android24; NATIVE_LD=aarch64-linux-android24 ;;
  *) NATIVE_TARGET=x86_64-linux-android24; NATIVE_LD=x86_64-linux-android24 ;;
esac

build_native_local() {
  local abi="$1" cc_triple="$2"
  local clang="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  local llama_build="$LLAMA_SRC/build-$abi"
  if [ ! -d "$llama_build" ]; then
    say "configuring llama.cpp for $abi"
    "$SDK/cmake/3.22.1/bin/cmake" -S "$LLAMA_SRC" -B "$llama_build" \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 \
      -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
      -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DGGML_NATIVE=OFF \
      -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF \
      -DLLAMA_BUILD_TOOLS=OFF > "$OUT/cmake-$abi.log" 2>&1
  fi
  [ -f "$llama_build/src/libllama.so" ] || {
    say "building llama.cpp for $abi"
    "$SDK/cmake/3.22.1/bin/cmake" --build "$llama_build" -j"$(nproc)" --target llama \
      > "$OUT/ninja-$abi.log" 2>&1
  }
  local libdir="$OUT/lib/$abi"; mkdir -p "$libdir"
  local inc=()
  for so in $(find "$llama_build" \( -name 'libllama.so' -o -name 'libggml*.so' \) | sort -u); do
    cp -f "$so" "$libdir/"; inc+=("-I$(dirname "$so")")
  done
  say "compiling JNI bridge for $abi ($cc_triple)"
  local libs=()
  for so in "$libdir"/libllama.so "$libdir"/libggml.so "$libdir"/libggml-base.so "$libdir"/libggml-cpu.so; do
    [ -f "$so" ] && libs+=("$so")
  done
  [ ${#libs[@]} -gt 0 ] || die "no llama shared libs found in $libdir"
  "$clang/clang++" --target="$cc_triple" -O3 -fPIC -shared -std=c++17 \
    -I"$LLAMA_SRC/include" -I"$LLAMA_SRC/ggml/include" "${inc[@]}" \
    "$APP/jni/llama_jni.cpp" -o "$libdir/libmarkliv.so" \
    "${libs[@]}" -Wl,-rpath,'$ORIGIN'
  say "native ok: $libdir"
}

for ABI in $ABIS; do
  case "$ABI" in
    arm64-v8a) build_native_local "$ABI" aarch64-linux-android24 ;;
    x86_64)    build_native_local "$ABI" x86_64-linux-android24 ;;
    *) die "unsupported ABI $ABI" ;;
  esac
done

# ------------------------------------------------------------ 2. resources
say "aapt2 compile + link"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res/resources.zip"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 1 --version-name 1.0.0 \
  --auto-add-overlay \
  "$OUT/res/resources.zip"

# ----------------------------------------------------------------- 3. java
say "javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 17 -target 17 -nowarn \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" 2> "$OUT/javac.log" || { cat "$OUT/javac.log"; die "javac failed"; }

say "d8 (dex)"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" @"$OUT/classes.txt"

# ------------------------------------------------------------- 4. packaging
say "packaging"
# classes.dex must go in through the zip tool, not a raw copy, or the archive
# central directory is not updated and installers reject the APK.
mkdir -p "$OUT/stage"
cp -f "$OUT/dex/classes.dex" "$OUT/stage/classes.dex"
for ABI in $ABIS; do
  mkdir -p "$OUT/stage/lib/$ABI"
  cp -f "$OUT/lib/$ABI"/*.so "$OUT/stage/lib/$ABI/"
done
( cd "$OUT/stage" && zip -q -X "$OUT/base.apk" classes.dex && zip -q -X -r "$OUT/base.apk" lib )

if [ ! -f "$KEYSTORE" ]; then
  say "creating debug keystore"
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass markliv -keypass markliv \
    -alias markliv -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=MARK LIV, OU=Mr Jai, O=MARK LIV, L=, S=, C=IN" > /dev/null 2>&1
fi

say "zipalign + sign"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
APK="$HERE/MARK-LIV-$(date +%Y%m%d)-offline.apk"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:markliv --key-pass pass:markliv \
  --ks-key-alias markliv --min-sdk-version 24 \
  --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 24 "$APK" > /dev/null && say "signature OK"
cp -f "$APK" "$DIST/MARK-LIV.apk"
cp -f "$APK" "$HOME/Downloads/MARK-LIV.apk" 2>/dev/null || true

say "built: $APK"
ls -lh "$APK" | awk '{print $5, $9}'
"$BT/aapt2" dump badging "$APK" 2>/dev/null | head -4 || true
