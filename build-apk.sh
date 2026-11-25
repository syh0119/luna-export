#!/usr/bin/env bash
# 汽水导出 · 单个 APK（LSPosed 模块 + 普通应用 二合一）
# 无 Gradle 手动构建：aapt2 + javac + d8 + zipalign + apksigner
#
# 路径全部自定位：仓库根 = 本脚本所在目录；工具链走环境变量，缺省找 D:/WorkBuddy/_tools。
set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# ⚠️ 统一用 Windows 形式（D:/...）作为基准：
#    · Git Bash 的 find/mkdir/rm 完全能处理 D:/... 路径；
#    · javac/aapt2/d8 这些原生 Windows 程序只认 D:/...，认不出 /d/...；
#    · 尤其 classpath 里的 ';' 会被 shell 当命令分隔 —— 两边形式一致才不会踩。
ROOTW="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")"
ROOT="$ROOTW"
TOOLS="${LUNA_TOOLS:-D:/WorkBuddy/_tools}"
SDK="${ANDROID_SDK_ROOT:-$TOOLS/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVAC="${JAVA_HOME:-$TOOLS/jdk}/bin/javac.exe"
AAPT2="$BT/aapt2.exe"
D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"
KS="${LUNA_KEYSTORE:-$TOOLS/keystore/debug.keystore}"
XPOSED="$ROOT/lib/api-82.jar"
PY="${LUNA_PYTHON:-C:/Users/Admin/.workbuddy/binaries/python/versions/3.13.12/python.exe}"

# d8/apksigner 是 .bat，需要 JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
  export JAVA_HOME="${LUNA_JDK:-$TOOLS/jdk}"
fi

APP="$ROOT/app"
OUT="$ROOT/out/apk"
MIN_SDK=29
TGT_SDK=34
# ⚠️ 版本号以 app/AndroidManifest.xml 为准 —— aapt2 link 的 --version-name/--version-code
#    压不过 manifest 里已声明的值（旧版本就栽在这：发布名叫 1.1.2，APK 内部却是 1.1.0）。
#    所以这里两个值都从 manifest 里读出来，保证文件名与包内版本永远一致。
VER_CODE="$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
VER_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
: "${VER_CODE:=1}"
: "${VER_NAME:=0.0.0}"

echo "==================================================="
echo " 构建 汽水导出 (LSPosed 模块 + 独立 App 二合一)  v$VER_NAME (code $VER_CODE)"
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

# ---- 先跑离线自测（暂存命令的 shell 语法 + 复制清单完整性）----
# 这一步只需要纯 Java（StageCmd / DirsSelfTest 不依赖 Android），失败就别打包了。
echo "      离线自测 …"
SELFTEST_OUT="$OUT/selftest"
rm -rf "$SELFTEST_OUT"; mkdir -p "$SELFTEST_OUT"
set +e
"$JAVAC" -encoding UTF-8 -nowarn -d "$SELFTEST_OUT" \
  "$ROOT/android/src/com/syh/luna/android/Tar.java" \
  "$ROOT/android/src/com/syh/luna/android/StageCmd.java" \
  "$ROOT/android/src/com/syh/luna/android/StageE2E.java" \
  "$ROOT/android/src/com/syh/luna/android/DirsSelfTest.java" > "$OUT/selftest.log" 2>&1
if [ $? -ne 0 ]; then
  set -e; echo "!! 自测编译失败"; cat "$OUT/selftest.log"; exit 1
fi
JAVA_BIN="$(dirname "$JAVAC")/java.exe"
"$JAVA_BIN" -Dfile.encoding=UTF-8 -cp "$SELFTEST_OUT" com.syh.luna.android.DirsSelfTest > "$OUT/selftest.log" 2>&1
RC=$?
set -e
grep -a "FAIL" "$OUT/selftest.log" | head -10 || true
if [ $RC -ne 0 ]; then echo "!! 自测未通过（详见 out/apk/selftest.log）"; cat "$OUT/selftest.log"; exit 1; fi
echo "      自测通过"

# ---- 端到端模拟：真跑一遍「su 只读 → tar 流 → App 解包」----
# 这一步用本机真实的 sh + tar 执行生成的命令，验证的不只是语法，而是整条链路真的通。
# ⚠️ 这就是当初「LIST 被 shell 分词」那个 bug 被抓出来的地方 —— 只有真跑才能发现。
set +e
"$JAVA_BIN" -Dfile.encoding=UTF-8 -cp "$SELFTEST_OUT" com.syh.luna.android.StageE2E > "$OUT/e2e.log" 2>&1
RC=$?
set -e
grep -a "FAIL" "$OUT/e2e.log" | head -10 || true
if [ $RC -ne 0 ]; then echo "!! 端到端模拟未通过（详见 out/apk/e2e.log）"; cat "$OUT/e2e.log"; exit 1; fi
echo "      端到端模拟通过"

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
