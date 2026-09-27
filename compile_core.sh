#!/bin/bash
# compile_core.sh — 用缓存的 kotlin-compiler-embeddable 直接编译+运行 JVM core 单测（绕 Gradle）
set -e
cd /home/chenbin/.hermes/workspace/wa-translate-android
KDIR=/home/chenbin/.gradle/caches/modules-2/files-2.1
KC=$(ls $KDIR/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.24/*/kotlin-compiler-embeddable-1.9.24.jar)
STD=$(ls $KDIR/org.jetbrains.kotlin/kotlin-stdlib/1.9.24/*/kotlin-stdlib-1.9.24.jar)
TROVE=$(ls $KDIR/org.jetbrains.intellij.deps/trove4j/*/*/trove4j-*.jar)
ANN=$(ls $KDIR/org.jetbrains/annotations/13.0/*/annotations-13.0.jar)
rm -rf /tmp/kt-out && mkdir -p /tmp/kt-out
java -cp "$KC:$STD:$TROVE:$ANN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -no-reflect -no-stdlib -cp "$STD:$ANN" \
  app/src/main/java/id/co/watranslate/Core.kt \
  app/src/main/java/id/co/watranslate/ScanLogic.kt \
  core-test/CoreTest.kt -d /tmp/kt-out
java -cp "/tmp/kt-out:$STD:$ANN" id.co.watranslate.CoreTestKt
