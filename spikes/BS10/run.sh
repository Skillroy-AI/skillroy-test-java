#!/usr/bin/env bash
#
# Copyright 2026 Mike Mills.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Spike BS10: runs the harness (toy window, 16 cases x ROBOT, ROUTED, DISPATCH) in the environments asked for. The
# environments are spike BS9's (its derived image with openbox and its in-image script, spikes/BS9/docker and
# spikes/BS9/scripts/in-image.sh). Build first (offline):
#
#   mvn -o -q -f spikes/BS10/pom.xml package
#   SKILLROY_JDK8_HOME=/path/to/linux-arm64-jdk8 spikes/BS10/run.sh [leg ...]
#
# Legs (default: the four arm64 Linux legs): nowm-j8, nowm-j25 (the test image as the CI runs it, WM-less Xvfb),
# wm-j8, wm-j25 (openbox on the Xvfb display), amd64-nowm-j25 (the amd64 image, emulated), mac-j25 (this Mac's desktop,
# Java 25 natively, strict mode: Robot acts only while the toy holds the focused window, inside the toy's own windows,
# no keys, the pointer restored, 55 s at most; takes the display lock directory BS10_MAC_LOCK first, waiting up to 30
# minutes). Evidence: spikes/BS10/target/runs/<leg>/bs10/ (summary.md, results.tsv, events.txt) and <leg>/bs10.out.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
runs="$here/target/${BS10_RUNS:-runs}"
if [ ! -s "$here/target/bs10-harness.jar" ]; then echo "build first: mvn -o -q -f spikes/BS10/pom.xml package" >&2; exit 2; fi
legs=("$@")
if [ ${#legs[@]} -eq 0 ]; then legs=(nowm-j8 nowm-j25 wm-j8 wm-j25); fi
base_arm64="skillroy-test-java-ci:bs8-arm64"
derived_arm64="skillroy-spike-bs9:wm-arm64"
ensure_derived() {
  if ! docker image inspect "$derived_arm64" > /dev/null 2>&1; then
    docker build --platform linux/arm64 --build-arg BASE="$base_arm64" -t "$derived_arm64" "$repo/spikes/BS9/docker"
  fi
}
linux() { # <leg> <image> <platform> <wm> <java>
  local leg="$1" image="$2" platform="$3" wm="$4" jv="$5"
  local out="$runs/$leg"
  rm -rf "$out"; mkdir -p "$out"
  (cd "$repo" && SKILLROY_PLATFORM="$platform" SKILLROY_CI_IMAGE="$image" \
    ci/run-in-linux.sh bash spikes/BS9/scripts/in-image.sh "$leg" "$wm" "$jv" "$out" bs10) > "$out/container.log" 2>&1 \
    || echo "$leg: container exit $?"
  echo "$leg: $(tail -1 "$out/bs10.out" 2> /dev/null); summary in $out/bs10/summary.md"
}
mac() {
  local out="$runs/mac-j25" lock="${BS10_MAC_LOCK:-$runs/mac-display.lock}"
  local java; java="$(/usr/libexec/java_home -v 25)/bin/java"
  rm -rf "$out"; mkdir -p "$out"
  local waited=0
  until mkdir "$lock" 2> /dev/null; do
    if [ "$waited" -ge 1800 ]; then echo "mac-j25: display lock busy for 30 minutes; leg skipped"; return; fi
    sleep 30; waited=$((waited + 30))
  done
  trap 'rmdir "$lock" 2> /dev/null || true' EXIT
  "$java" -javaagent:"$here/target/bs10-harness.jar=out=$out/bs10,env=mac-j25,strict=true,deadline=55" \
    -Dbs10.x=160 -Dbs10.y=140 -cp "$here/target/bs10-harness.jar" ai.skillroy.testjava.spikes.bs10.Toy \
    > "$out/bs10.out" 2>&1 || true
  rmdir "$lock" 2> /dev/null || true
  trap - EXIT
  echo "mac-j25: $(tail -1 "$out/bs10.out"); summary in $out/bs10/summary.md"
}
for leg in "${legs[@]}"; do
  case "$leg" in
    nowm-j8) linux "$leg" "$base_arm64" linux/arm64 none 8 ;;
    nowm-j25) linux "$leg" "$base_arm64" linux/arm64 none 25 ;;
    wm-j8) ensure_derived; linux "$leg" "$derived_arm64" linux/arm64 openbox 8 ;;
    wm-j25) ensure_derived; linux "$leg" "$derived_arm64" linux/arm64 openbox 25 ;;
    amd64-nowm-j25) linux "$leg" skillroy-test-java-ci:local linux/amd64 none 25 ;;
    mac-j25) mac ;;
    *) echo "unknown leg $leg" >&2 ;;
  esac
done
echo "BS10 DONE"
