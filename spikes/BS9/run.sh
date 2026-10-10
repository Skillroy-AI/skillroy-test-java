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
# Spike BS9 (with BS10's harness in the same environments): runs the legs asked for and prints where the evidence is.
# Build both projects first (offline):
#
#   mvn -o -q -f spikes/BS9/pom.xml package && mvn -o -q -f spikes/BS10/pom.xml package
#   SKILLROY_JDK8_HOME=/path/to/linux-arm64-jdk8 spikes/BS9/run.sh [leg ...]
#
# Legs (default: the Linux ones; run mac-j25 last and on its own, it acts on the desktop of the person at this Mac):
#   nowm-j8, nowm-j25     the arm64 test image as the CI runs it (WM-less Xvfb): BS10 harness, key probe, sample probe
#   nowmx-j8, nowmx-j25   the derived image (xdotool, xprop) still without a window manager: the key probe (F13),
#                         and the click-to-activate scenarios five times each
#   wm-j8, wm-j25         the derived image with openbox started on the Xvfb display first: all three parts
#   amd64-nowm-j25        the amd64 test image (emulated, slow): BS10 harness and key probe, for the architecture
#   mac-j25               this Mac's own desktop, Java 25 natively, strict mode (see README.md); takes the display lock
#                         directory BS9_MAC_LOCK (default target/runs/mac-display.lock) first, waiting up to 30 minutes
# Evidence: spikes/BS9/target/runs/<leg>/ (env.txt, *.out, bs10/, keys/, sample/). BS9_RUNS names another directory
# (relative to target/, or absolute: the mac-j25 leg's ROBOT captures show whatever is on that desktop, so write them
# outside the repository and look at them before keeping any).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
runs="${BS9_RUNS:-runs}"
case "$runs" in /*) ;; *) runs="$here/target/$runs" ;; esac
for jar in "$here/target/bs9-probe.jar" "$repo/spikes/BS10/target/bs10-harness.jar" \
    "$repo/samples/skillroy-sample-lending-app/target/skillroy-sample-lending-app-0.1.0-SNAPSHOT.jar"; do
  if [ ! -s "$jar" ]; then echo "missing $jar: build first (see the header)" >&2; exit 2; fi
done
legs=("$@")
if [ ${#legs[@]} -eq 0 ]; then legs=(nowm-j8 nowm-j25 nowmx-j8 nowmx-j25 wm-j8 wm-j25); fi
base_arm64="skillroy-test-java-ci:bs8-arm64"
derived_arm64="skillroy-spike-bs9:wm-arm64"
ensure_derived() {
  if ! docker image inspect "$derived_arm64" > /dev/null 2>&1; then
    docker build --platform linux/arm64 --build-arg BASE="$base_arm64" -t "$derived_arm64" "$here/docker"
  fi
}
linux() { # <leg> <image> <platform> <wm> <java> <parts...>
  local leg="$1" image="$2" platform="$3" wm="$4" jv="$5"; shift 5
  local out="$runs/$leg"
  rm -rf "$out"; mkdir -p "$out"
  (cd "$repo" && SKILLROY_PLATFORM="$platform" SKILLROY_CI_IMAGE="$image" \
    ci/run-in-linux.sh bash spikes/BS9/scripts/in-image.sh "$leg" "$wm" "$jv" "$out" "$@") > "$out/container.log" 2>&1 \
    || echo "$leg: container exit $?"
  echo "$leg: $(grep -c 'exit 0' "$out/env.txt" 2> /dev/null || echo 0) JVM(s) ended with exit 0; evidence in $out"
}
mac() {
  # A directory used as a lock (mkdir is atomic) so that no other process puts windows on this desktop meanwhile.
  local out="$runs/mac-j25" lock="${BS9_MAC_LOCK:-$runs/mac-display.lock}"
  local java_home; java_home="$(/usr/libexec/java_home -v 25)"
  local java="$java_home/bin/java"
  local hsqldb="$HOME/.m2/repository/org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4-jdk8.jar"
  local app_jar="$repo/samples/skillroy-sample-lending-app/target/skillroy-sample-lending-app-0.1.0-SNAPSHOT.jar"
  rm -rf "$out"; mkdir -p "$out"
  local waited=0
  until mkdir "$lock" 2> /dev/null; do
    if [ "$waited" -ge 1800 ]; then echo "mac-j25: display lock busy for 30 minutes; leg skipped" | tee "$out/env.txt"; return; fi
    sleep 30; waited=$((waited + 30))
  done
  trap 'rmdir "$lock" 2> /dev/null || true' EXIT
  {
    echo "label=mac-j25 java=$("$java" -version 2>&1 | head -1) arch=$(uname -m) os=$(sw_vers -productVersion)"
    local rc
    "$java" -javaagent:"$repo/spikes/BS10/target/bs10-harness.jar=out=$out/bs10,env=mac-j25,strict=true,deadline=55" \
      -Dbs10.x=160 -Dbs10.y=140 -cp "$repo/spikes/BS10/target/bs10-harness.jar" ai.skillroy.testjava.spikes.bs10.Toy \
      > "$out/bs10.out" 2>&1 && rc=0 || rc=$?
    echo "bs10 exit $rc"
    for sc in activated unactivated-hover unactivated-click after-dialog; do
      "$java" -cp "$here/target/bs9-probe.jar" ai.skillroy.testjava.spikes.bs9.KeysProbe \
        "out=$out/keys,env=mac-j25,strict=true,scenario=$sc,x=200,y=200" > "$out/keys-$sc.out" 2>&1 && rc=0 || rc=$?
      echo "keys-$sc exit $rc"
    done
    "$java" -javaagent:"$here/target/bs9-probe.jar=out=$out/sample,env=mac-j25,strict=true,deadline=55" \
      -cp "$app_jar:$hsqldb" ai.skillroy.testjava.samples.lending.LendingApp > "$out/sample.out" 2>&1 && rc=0 || rc=$?
    echo "sample exit $rc"
  } | tee "$out/env.txt"
  rmdir "$lock" 2> /dev/null || true
  trap - EXIT
  echo "mac-j25: evidence in $out"
}
for leg in "${legs[@]}"; do
  case "$leg" in
    nowm-j8) linux "$leg" "$base_arm64" linux/arm64 none 8 bs10 keys sample ;;
    nowm-j25) linux "$leg" "$base_arm64" linux/arm64 none 25 bs10 keys sample ;;
    nowmx-j8) ensure_derived; linux "$leg" "$derived_arm64" linux/arm64 none 8 keys keysrepeat ;;
    nowmx-j25) ensure_derived; linux "$leg" "$derived_arm64" linux/arm64 none 25 keys keysrepeat ;;
    wm-j8) ensure_derived; linux "$leg" "$derived_arm64" linux/arm64 openbox 8 bs10 keys sample ;;
    wm-j25) ensure_derived; linux "$leg" "$derived_arm64" linux/arm64 openbox 25 bs10 keys sample ;;
    amd64-nowm-j25) linux "$leg" skillroy-test-java-ci:local linux/amd64 none 25 bs10 keys ;;
    mac-j25) mac ;;
    *) echo "unknown leg $leg" >&2 ;;
  esac
done
echo "BS9 DONE"
