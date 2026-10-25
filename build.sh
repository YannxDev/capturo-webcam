#!/usr/bin/env bash
# Construit l'APK Capturo Webcam sans Gradle (aapt2 + javac + d8 + apksigner).
set -euo pipefail

cd "$(dirname "$0")"

VERSION="1.3"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM_DIR="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"

if [ -z "$BUILD_TOOLS" ] || [ -z "$PLATFORM_DIR" ]; then
    echo "SDK Android introuvable (build-tools / platforms)." >&2
    exit 1
fi

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
ANDROID_JAR="$PLATFORM_DIR/android.jar"

echo "SDK       : $SDK"
echo "Build-tools: $(basename "$BUILD_TOOLS")"
echo "Plateforme : $(basename "$PLATFORM_DIR")"

rm -rf build
mkdir -p build/classes build/gen build/dex dist

echo "→ compilation des ressources"
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link \
    -o build/base.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    --java build/gen \
    --auto-add-overlay

echo "→ compilation Java"
find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 --release 17 \
    -classpath "$ANDROID_JAR" \
    -d build/classes \
    @build/sources.txt

echo "→ conversion en DEX"
find build/classes -name '*.class' -print0 \
    | xargs -0 "$D8" --lib "$ANDROID_JAR" --min-api 24 --output build/dex

echo "→ assemblage de l'APK"
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)

"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk

KEYSTORE="${CAPTURO_KEYSTORE:-capturo-webcam.keystore}"
KEYSTORE_PASS="${CAPTURO_KEYSTORE_PASS:-}"
if [ -z "$KEYSTORE_PASS" ]; then
    echo "Définis CAPTURO_KEYSTORE_PASS (mot de passe de $KEYSTORE)." >&2
    exit 1
fi

if [ ! -f "$KEYSTORE" ]; then
    echo "→ création de la clé de signature"
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -alias capturo \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
        -dname "CN=Capturo Webcam, O=Yannick, C=FR"
fi

OUT="dist/Capturo-Webcam-${VERSION}.apk"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --out "$OUT" \
    build/aligned.apk

echo "→ vérification"
"$APKSIGNER" verify --print-certs "$OUT" | head -4
ls -lh "$OUT"
sha256sum "$OUT"
echo
echo "Installation : adb install -r $OUT"
