#!/usr/bin/env bash
# Gesture, layout and JVM regressions for the on-screen pad and the second
# display -- the bash twin of test.ps1.
#
# Same two halves, same two prerequisites, no PowerShell:
#
#   1. DualScreenTests: the C# gesture/layout tests, compiled against the Unity
#      player module's managed assemblies.
#   2. SecondaryDisplayTest: the Java side of the second-display shell, compiled
#      against the player module's classes.jar and the SDK's android.jar.
#
# Needs the Unity Android player module (`make player`) and an Android SDK with
# a platform installed. No game depot: these are our own sources plus Unity's.
#
# Usage:
#   tools/silksong-patches/test.sh
#   PLAYER=/opt/unity-player/android/Variations/il2cpp/Managed test.sh
#   ANDROID_SDK=/opt/android-sdk test.sh
#
# The PowerShell version stays for Windows checkouts; this one exists so the
# same suite can run in the build container, where there is no pwsh.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tests="$here/tests"
shell_src="$(cd "$here/../depot-to-apk/shell" && pwd)"

PLAYER="${PLAYER:-${SILKSONG_PLAYER:-${HOME}/.cache/silksong/unity-player/android/Variations/il2cpp/Managed}}"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"

unity_jar="$(cd "$PLAYER/../Release/Classes" 2>/dev/null && pwd)/classes.jar"
if [[ ! -f "$unity_jar" ]]; then
    echo "test: no classes.jar beside $PLAYER (make player?)" >&2
    exit 2
fi
if [[ -z "$ANDROID_SDK" || ! -d "$ANDROID_SDK/platforms" ]]; then
    echo "test: no Android SDK platforms under '${ANDROID_SDK:-<unset>}' (set ANDROID_SDK)" >&2
    exit 2
fi
android_jar="$(find "$ANDROID_SDK/platforms" -name android.jar -print 2>/dev/null | sort -V | tail -1)"
if [[ -z "$android_jar" ]]; then
    echo "test: no android.jar under $ANDROID_SDK/platforms" >&2
    exit 2
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/silksong-pad-tests-XXXXXX")"
trap 'rm -rf "$work"' EXIT

echo "== managed: $tests/DualScreenTests.csproj"
dotnet build "$tests/DualScreenTests.csproj" -o "$work/managed" \
    "-p:Player=$PLAYER" "-p:BaseIntermediateOutputPath=$work/obj/" \
    --nologo -v quiet -nodeReuse:false -p:UseSharedCompilation=false
dotnet "$work/managed/DualScreenTests.dll"

echo "== java: SecondaryDisplayTest"
mkdir -p "$work/java"
cp="$unity_jar:$android_jar"
javac --release 17 -nowarn -cp "$cp" -d "$work/java" \
    "$shell_src/PlayerActivity.java" \
    "$shell_src/SecondaryDisplay.java" \
    "$tests/SecondaryDisplayTest.java"
java -cp "$work/java:$cp" dev.silksong.shell.SecondaryDisplayTest

echo "test: all regressions passed"
