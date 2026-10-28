#!/bin/bash
# Builds app/ into build/tvagent.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: ./build.sh <android-sdk> <jdk-17> [keystore.jks] [storepass]
# Needs: platforms;android-25 and build-tools;34.0.0 in the SDK, python3.
# Without an existing keystore a new key is created in keystore/ (not in git); updates must use the same key.
set -euo pipefail
SDK=${1:?android sdk}; JDK=${2:?jdk 17}
here=$(cd "$(dirname "$0")" && pwd)
KEYSTORE=${3:-$here/keystore/tvagent.jks}; STOREPASS=${4:-tvagent1}
app=$here/app; out=$here/build
bt=$SDK/build-tools/34.0.0
android_jar=$SDK/platforms/android-25/android.jar
export JAVA_HOME=$JDK PATH=$JDK/bin:$PATH

rm -rf "$out"; mkdir -p "$out"/{gen,classes,dex}

echo "aapt2 link"
"$bt/aapt2" link -I "$android_jar" --manifest "$app/AndroidManifest.xml" --java "$out/gen" \
    --min-sdk-version 24 --target-sdk-version 25 -o "$out/app.unsigned.apk"

echo "javac"
find "$app/src" "$out/gen" -name '*.java' > "$out/src.txt"
javac --release 8 -encoding UTF-8 -cp "$android_jar" -d "$out/classes" @"$out/src.txt"

echo "d8"
jar cf "$out/classes.jar" -C "$out/classes" .
"$bt/d8" --release --min-api 24 --lib "$android_jar" --output "$out/dex" "$out/classes.jar"

echo "package"
python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex", zipfile.ZIP_DEFLATED)' \
    "$out/app.unsigned.apk" "$out/dex/classes.dex"
"$bt/zipalign" -p -f 4 "$out/app.unsigned.apk" "$out/app.aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "new signing key (keep keystore/ - updates must be signed with the same key)"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$STOREPASS" -keypass "$STOREPASS" -alias tvagent \
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TV Agent, O=and-vin'
fi
java -jar "$bt/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass "pass:$STOREPASS" --key-pass "pass:$STOREPASS" \
    --ks-key-alias tvagent --out "$out/tvagent.apk" "$out/app.aligned.apk"
java -jar "$bt/lib/apksigner.jar" verify "$out/tvagent.apk"
echo "built: $out/tvagent.apk"
