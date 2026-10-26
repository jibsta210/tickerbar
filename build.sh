#!/usr/bin/env bash
set -euo pipefail
P="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
OUT="$P/build"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/res"

GHOSTLOCK="$P/native/ghostlock"
GHOST_PROJECT="songyuan-OS3.0.309.0.WGNMIXM"
GHOST_BIN="$GHOSTLOCK/build/$GHOST_PROJECT/bin/cve-2026-43499-exec"
NDK="$SDK/ndk/29.0.14206865"

echo "[1/6] build native root module"
make -C "$GHOSTLOCK" NDK_ROOT="$NDK" PROJECT="$GHOST_PROJECT" exploit-exec
test -x "$GHOST_BIN"

echo "[2/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$P/res" -o "$OUT/res.zip"

echo "[3/6] aapt2 link"
"$BT/aapt2" link \
  -I "$JAR" \
  --manifest "$P/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -o "$OUT/base.apk" \
  "$OUT/res.zip"

mkdir -p "$OUT/native/lib/arm64-v8a"
cp "$GHOST_BIN" "$OUT/native/lib/arm64-v8a/libtickerroot.so"
(cd "$OUT/native" && zip -q "$OUT/base.apk" lib/arm64-v8a/libtickerroot.so)

echo "[4/6] javac"
find "$P/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:none -source 17 -target 17 \
  -classpath "$JAR:$(ls "$P"/libs/*.jar | tr '\n' ':')" \
  -d "$OUT/classes" @"$OUT/sources.txt"
if [ -z "$(find "$OUT/classes" -name '*.class' -print -quit)" ]; then
  echo "COMPILE FAILED - no classes produced"; exit 1
fi

echo "[5/6] d8 -> dex"
"$BT/d8" --lib "$JAR" --min-api 29 --output "$OUT" \
  $(find "$OUT/classes" -name '*.class') "$P"/libs/*.jar

echo "[6/6] package + sign"
cd "$OUT"
cp base.apk unsigned.apk
"$BT/aapt2" version >/dev/null
zip -q -j unsigned.apk classes.dex
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk

KS="$P/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias tickerbar -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TickerBar,O=Local,C=CA" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$P/tickerbar.apk" aligned.apk
"$BT/apksigner" verify --print-certs "$P/tickerbar.apk" | head -3
echo "BUILT: $P/tickerbar.apk"
ls -lh "$P/tickerbar.apk"
