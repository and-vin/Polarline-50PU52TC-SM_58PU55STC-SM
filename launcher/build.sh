#!/bin/bash
# Builds app/ into build/tvhome.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Linux/macOS counterpart of build.ps1.
# Usage: ./build.sh <android-sdk> <jdk-17> [keystore.jks] [storepass]
# Needs: platforms;android-25 and build-tools;34.0.0 in the SDK, python3 (for adding files to the APK).
# Without an existing keystore a new key is created in keystore/ (not in git). An APK signed with another key
# can only replace an installed one after uninstalling it.
set -euo pipefail
SDK=${1:?android sdk}; JDK=${2:?jdk 17}
here=$(cd "$(dirname "$0")" && pwd)
KEYSTORE=${3:-$here/keystore/tvhome.jks}; STOREPASS=${4:-tvhome1}
app=$here/app; out=$here/build
bt=$SDK/build-tools/34.0.0
android_jar=$SDK/platforms/android-25/android.jar
export JAVA_HOME=$JDK PATH=$JDK/bin:$PATH

rm -rf "$out"; mkdir -p "$out"/{gen,stubs,classes,dex}

echo "aapt2 compile/link"
"$bt/aapt2" compile --dir "$app/res" -o "$out/res.zip"
"$bt/aapt2" link -I "$android_jar" --manifest "$app/AndroidManifest.xml" --java "$out/gen" \
    --min-sdk-version 24 --target-sdk-version 25 -o "$out/app.unsigned.apk" "$out/res.zip"

echo "javac"
# java.* from the JDK's release-8 API, android.* from android.jar (its lack of LambdaMetafactory crashes javac
# when used as bootclasspath); d8 desugars the lambdas.
find "$here/stubs" -name '*.java' > "$out/stubs.txt"
javac -nowarn --release 8 -cp "$android_jar" -encoding UTF-8 -d "$out/stubs" @"$out/stubs.txt"
find "$app/src" "$out/gen" -name '*.java' > "$out/src.txt"
javac --release 8 -encoding UTF-8 -cp "$android_jar:$out/stubs" -d "$out/classes" @"$out/src.txt"

echo "d8"
jar cf "$out/classes.jar" -C "$out/classes" .
"$bt/d8" --release --min-api 24 --lib "$android_jar" --classpath "$out/stubs" --output "$out/dex" "$out/classes.jar"

echo "package"
# classes.dex compressed, JPEG assets stored (they do not shrink anyway).
python3 -I - "$out/app.unsigned.apk" "$out/dex/classes.dex" "$app/assets" <<'EOF'
import os, sys, zipfile
apk, dex, assets = sys.argv[1:4]
with zipfile.ZipFile(apk, "a") as z:
    z.write(dex, "classes.dex", zipfile.ZIP_DEFLATED)
    for root, _, files in os.walk(assets):
        for f in sorted(files):
            if f.lower().endswith(".jpg"):
                p = os.path.join(root, f)
                z.write(p, "assets/" + os.path.relpath(p, assets).replace(os.sep, "/"), zipfile.ZIP_STORED)
EOF
"$bt/zipalign" -p -f 4 "$out/app.unsigned.apk" "$out/app.aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "new signing key (keep keystore/ - updates must be signed with the same key)"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$STOREPASS" -keypass "$STOREPASS" -alias tvhome \
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TV Home, O=and-vin'
fi
java -jar "$bt/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass "pass:$STOREPASS" --key-pass "pass:$STOREPASS" \
    --ks-key-alias tvhome --out "$out/tvhome.apk" "$out/app.aligned.apk"
java -jar "$bt/lib/apksigner.jar" verify "$out/tvhome.apk"
echo "built: $out/tvhome.apk ($(stat -c %s "$out/tvhome.apk" 2>/dev/null || stat -f %z "$out/tvhome.apk") bytes)"
