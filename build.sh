#!/usr/bin/env bash
set -euo pipefail
P="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
OUT="$P/build"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/res"

echo "[1/5] aapt2 compile resources"
"$BT/aapt2" compile --dir "$P/res" -o "$OUT/res.zip"

echo "[2/5] aapt2 link"
"$BT/aapt2" link \
  -I "$JAR" \
  --manifest "$P/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -o "$OUT/base.apk" \
  "$OUT/res.zip"

echo "[3/5] javac"
find "$P/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:none -source 17 -target 17 \
  -classpath "$JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -vE "^Note:|bootstrap class path|source value|target value|warning" || true
if [ -z "$(find "$OUT/classes" -name '*.class' -print -quit)" ]; then
  echo "COMPILE FAILED - no classes produced"; exit 1
fi

echo "[4/5] d8 -> dex"
"$BT/d8" --lib "$JAR" --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')

echo "[5/5] package + sign"
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
