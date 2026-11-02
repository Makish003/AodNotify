#!/usr/bin/env bash
# Builds out/AodNotify-<version>.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Everything it needs is in tools/ and libs/; only a JDK (17+) and Python 3 are required.
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
TOOLS="${TOOLS:-$PROJECT/tools}"
ANDROID_JAR="$TOOLS/android.jar"
JDK_BIN="${JDK_BIN:-C:/Program Files/Java/jdk-25/bin}"
# compile-only: provided by LSPosed at runtime
API_JAR="$PROJECT/libs/libxposed-api-102.0.0.jar"
# packaged: the settings app talks to LSPosed through these
APP_JARS=("$PROJECT/libs/libxposed-service-102.0.0.jar" "$PROJECT/libs/libxposed-interface-102.0.0.jar")
WORK="${WORK:-${TEMP:-/tmp}/aodnotify-build}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/gen"
cp -r "$PROJECT/res" "$PROJECT/meta" "$PROJECT/src" "$PROJECT/AndroidManifest.xml" "$WORK/"
cd "$WORK"

# The AOD settings row is inflated by AOD's own Preference constructor, which cannot resolve our
# @string references, so xml/aod_entry.xml carries a literal title. One copy per language, titled
# with that language's aod_entry_title.
python -I -c "
import glob, os, re
src = open('res/xml/aod_entry.xml', encoding='utf-8').read()
for values in glob.glob('res/values-*/strings.xml'):
    m = re.search(r'<string name=\"aod_entry_title\">([^<]*)</string>', open(values, encoding='utf-8').read())
    if m:
        out = 'res/xml-' + os.path.basename(os.path.dirname(values))[len('values-'):]
        os.makedirs(out, exist_ok=True)
        open(out + '/aod_entry.xml', 'w', encoding='utf-8').write(
            src.replace('android:title=\"Notifications\"', 'android:title=\"' + m.group(1) + '\"'))
"

"$TOOLS/aapt2.exe" compile --dir res -o res.zip
"$TOOLS/aapt2.exe" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 35 --target-sdk-version 36 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java gen -o base.apk res.zip

CP="$ANDROID_JAR;$API_JAR;${APP_JARS[0]};${APP_JARS[1]}"
"$JDK_BIN/javac.exe" --release 17 -encoding UTF-8 -Xlint:-options -nowarn \
    -cp "$CP" -d classes $(find src gen -name '*.java')

java -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 --release --min-api 35 \
    --lib "$ANDROID_JAR" --classpath "$API_JAR" --output dex \
    $(find classes -name '*.class') "${APP_JARS[@]}"

# classes.dex plus the libxposed module descriptors under META-INF/xposed/
python -I -c "
import os, zipfile
z = zipfile.ZipFile('base.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('dex/classes.dex', 'classes.dex')
for root, _, files in os.walk('meta'):
    for f in files:
        path = os.path.join(root, f)
        z.write(path, os.path.relpath(path, 'meta').replace(os.sep, '/'))
z.close()
"
"$TOOLS/zipalign.exe" -p -f 4 base.apk aligned.apk

KEYSTORE="$PROJECT/keystore/aodnotify.jks"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$PROJECT/keystore"
    "$JDK_BIN/keytool.exe" -genkeypair -keystore "$KEYSTORE" -storepass aodnotify -keypass aodnotify \
        -alias aodnotify -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AodNotify" >/dev/null
fi
java --enable-native-access=ALL-UNNAMED -jar "$TOOLS/apksigner.jar" sign --ks "$KEYSTORE" \
    --ks-pass pass:aodnotify --key-pass pass:aodnotify --ks-key-alias aodnotify \
    --v1-signing-enabled false --out signed.apk aligned.apk

mkdir -p "$PROJECT/out"
cp signed.apk "$PROJECT/out/AodNotify-$VERSION_NAME.apk"
echo "Built $PROJECT/out/AodNotify-$VERSION_NAME.apk"
