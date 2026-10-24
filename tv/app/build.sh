#!/bin/bash
# Build the PocketPad TV app without Gradle: aapt -> javac -> d8 -> zipalign -> apksigner.
# Needs: aapt, zipalign, apksigner, a JDK, plus ANDROID_JAR and R8_JAR (defaults below).
# Signed with the repo's pocketpad keystore so updates install over each other.
set -e
cd "$(dirname "$0")"
AJ="${ANDROID_JAR:-$HOME/ocmobile/android/android.jar}"
R8="${R8_JAR:-$HOME/ocmobile/android/lib/r8.jar}"
OUT="$PWD/build"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$AJ" -F "$OUT/base.apk"
javac --release 8 -Xlint:-options -cp "$AJ" -d "$OUT/classes" $(find java "$OUT/gen" -name '*.java')
java -cp "$R8" com.android.tools.r8.D8 --release --min-api 24 --lib "$AJ" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
( cd "$OUT/dex" && aapt add "$OUT/base.apk" classes.dex >/dev/null )
zipalign -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
apksigner sign --ks ../../keystore/pocketpad.jks --ks-pass pass:pocketpad --key-pass pass:pocketpad \
  --ks-key-alias pocketpad --out "$OUT/PocketPad-TV.apk" "$OUT/aligned.apk"
ls -lh "$OUT/PocketPad-TV.apk"
