#!/usr/bin/env bash
# 汽水导出 · 单个 APK（LSPosed 模块 + 普通应用 二合一）
# 无 Gradle 手动构建：aapt2 + javac + d8 + zipalign + apksigner
set -e

ROOT="D:/WorkBuddy/2026-10-09-18-38-30/luna-lsp"
TOOLS="D:/WorkBuddy/2026-10-09-14-12-52/build/tools"
SDK="$TOOLS/sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVAC="$TOOLS/jdk/bin/javac.exe"
AAPT2="$BT/aapt2.exe"
D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"
KS="D:/WorkBuddy/2026-10-09-14-12-52/build/debug.keystore"
XPOSED="$ROOT/lib/api-82.jar"
PY="C:/Users/Admin/.workbuddy/binaries/python/versions/3.13.12/python.exe"

# d8/apksigner 是 .bat，需要 JAVA_HOME
export JAVA_HOME="$(cygpath -w "$TOOLS/jdk" 2>/dev/null || echo "$TOOLS/jdk")"

APP="$ROOT/app"
OUT="$ROOT/out/apk"
MIN_SDK=29
TGT_SDK=34
VER_CODE=1
VER_NAME=1.0.0

echo "==================================================="
echo " 构建 汽水导出 (LSPosed 模块 + 独立 App 二合一)  v$VER_NAME"
echo "==================================================="

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/7] aapt2 compile 资源…"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/7] aapt2 link（含 assets/xposed_init）…"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TGT_SDK" \
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  "$OUT/res.zip"

echo "[3/7] javac 编译…"
SRCS=$(find "$ROOT/core/src" "$ROOT/android/src" "$ROOT/module/src" "$ROOT/app/src" "$OUT/gen" -name '*.java' 2>/dev/null | tr '\n' ' ')
echo "      源文件: $(echo $SRCS | wc -w) 个"
set +e
"$JAVAC" -encoding UTF-8 -nowarn \
  -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM;$XPOSED" \
  -d "$OUT/classes" \
  $SRCS > "$OUT/javac.log" 2>&1
RC=$?
set -e
grep -a -v "bootstrap class path\|source value 8\|target value 8\|deprecat\|warning" "$OUT/javac.log" | head -40 || true
if [ $RC -ne 0 ]; then echo "!! javac 失败 (rc=$RC)"; cat "$OUT/javac.log"; exit 1; fi
echo "      javac OK"

echo "[4/7] d8 转 dex…"
# ⚠️ class 一多，逐个文件传参会撞 Windows 命令行长度上限（"命令行太长"）。
#    先打成 jar 再交给 d8，参数就只剩一个路径。
"C:/Program Files/Java/jdk/bin/jar.exe" --version >/dev/null 2>&1 || true
JARBIN="$(dirname "$JAVAC")/jar.exe"
rm -f "$OUT/classes.jar"
"$JARBIN" cf "$OUT/classes.jar" -C "$OUT/classes" .
echo "      class 数: $(find "$OUT/classes" -name '*.class' | wc -l)  → classes.jar $(stat -c%s "$OUT/classes.jar") bytes"
set +e
"$D8" --release --min-api "$MIN_SDK" \
  --lib "$PLATFORM" --lib "$XPOSED" \
  --output "$OUT/dex" "$OUT/classes.jar" > "$OUT/d8.log" 2>&1
RC=$?
set -e
tail -20 "$OUT/d8.log"
if [ $RC -ne 0 ]; then echo "!! d8 失败 (rc=$RC)"; exit 1; fi
echo "      d8 OK"

echo "[5/7] 打包 classes.dex 进 APK…"
"$PY" - <<PYEOF
import zipfile, shutil, os
src = r"$OUT/base.apk"; dst = r"$OUT/unsigned.apk"; dex = r"$OUT/dex/classes.dex"
shutil.copy2(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("      classes.dex:", os.path.getsize(dex), "bytes")
PYEOF

echo "[6/7] zipalign…"
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] 签名…"
mkdir -p "$ROOT/dist"
"$APKSIGNER" sign \
  --ks "$(cygpath -w "$KS" 2>/dev/null || echo "$KS")" \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$ROOT/dist/luna-export-$VER_NAME.apk" "$OUT/aligned.apk"

echo
echo "==================================================="
ls -la "$ROOT/dist/luna-export-$VER_NAME.apk"
"$APKSIGNER" verify --print-certs "$ROOT/dist/luna-export-$VER_NAME.apk" 2>&1 | head -4
echo "==================================================="
echo "产物: $ROOT/dist/luna-export-$VER_NAME.apk"
