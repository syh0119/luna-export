#!/usr/bin/env bash
# luna-core 桌面构建 + 验证（纯 javac，无 Gradle）
set -e

ROOT="D:/WorkBuddy/2026-10-09-18-38-30/luna-lsp"
JDK="D:/WorkBuddy/2026-10-09-14-12-52/build/tools/jdk"
JAVAC="$JDK/bin/javac.exe"
JAVA="$JDK/bin/java.exe"
OUT="$ROOT/out"

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"

SRCS=$(find "$ROOT/core/src" "$ROOT/cli/src" -name '*.java' | tr '\n' ' ')
echo "源文件: $(echo $SRCS | wc -w) 个"

"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
  -d "$OUT/classes" $SRCS

echo "编译完成 -> $OUT/classes"
echo
echo "用法: $JAVA -cp $OUT/classes com.syh.luna.cli.Main <args>"
