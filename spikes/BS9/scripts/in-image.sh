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
# Spikes BS9 and BS10, inside the Linux test image (started by ci/run-in-linux.sh, so under xvfb-run on a WM-less
# Xvfb of 1024x768x24): optionally starts a window manager on that display first, then runs the parts asked for, each
# target JVM bounded by `timeout`, each writing under <out>/<part>/.
#
#   in-image.sh <label> <none|openbox> <8|25> <out> <part ...>
#
# Parts: bs10 (spike BS10's harness on its toy window), keys (BS9's key probe, five scenarios), keysrepeat (the two
# click-to-activate scenarios five times each), sample (BS9's sample probe in the sample lending desk launched from its
# built jar).
set -uo pipefail
label="$1"; wm="$2"; jv="$3"; out="$4"; shift 4
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
java=/opt/java/openjdk/bin/java
if [ "$jv" = 8 ]; then java=/opt/jdk8/bin/java; fi
mkdir -p "$out"
fork=()
# The amd64 image runs emulated on an arm64 machine, where a forked JVM needs the FORK launch mechanism (xdotool calls).
if [ "$(uname -m)" = x86_64 ]; then fork=(-Djdk.lang.Process.launchMechanism=FORK); fi
{
  echo "label=$label wm=$wm java=$("$java" -version 2>&1 | head -1) arch=$(uname -m) display=${DISPLAY:-none}"
  if command -v xdpyinfo > /dev/null; then
    echo "xdpyinfo: $(xdpyinfo | grep -E '^ *XTEST|dimensions' | tr -s ' ' | tr '\n' ';')"
  fi
} | tee "$out/env.txt"
wmpid=""
if [ "$wm" = openbox ]; then
  openbox --sm-disable > "$out/openbox.log" 2>&1 &
  wmpid=$!
  for _ in $(seq 1 100); do
    if xprop -root _NET_SUPPORTING_WM_CHECK 2> /dev/null | grep -q 'window id'; then break; fi
    sleep 0.1
  done
  echo "wm: $(xprop -root _NET_SUPPORTING_WM_CHECK 2>&1) $(xprop -root _NET_WM_NAME 2>&1 | head -1)" | tee -a "$out/env.txt"
fi
run() {
  local name="$1"; shift
  timeout -k 5 200 "$@" > "$out/$name.out" 2>&1
  echo "$name exit $?" | tee -a "$out/env.txt"
}
app_jar="$repo/samples/skillroy-sample-lending-app/target/skillroy-sample-lending-app-0.1.0-SNAPSHOT.jar"
hsqldb="/root/.m2/repository/org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4-jdk8.jar"
for part in "$@"; do
  case "$part" in
    bs10)
      run bs10 "$java" "${fork[@]+"${fork[@]}"}" \
        "-javaagent:$repo/spikes/BS10/target/bs10-harness.jar=out=$out/bs10,env=$label" \
        -cp "$repo/spikes/BS10/target/bs10-harness.jar" ai.skillroy.testjava.spikes.bs10.Toy ;;
    keys)
      for sc in activated unactivated-hover unactivated-click unactivated-xclick after-dialog; do
        run "keys-$sc" "$java" "${fork[@]+"${fork[@]}"}" \
          "-Djava.util.logging.config.file=$here/awt-key-logging.properties" \
          -cp "$repo/spikes/BS9/target/bs9-probe.jar" \
          ai.skillroy.testjava.spikes.bs9.KeysProbe "out=$out/keys,env=$label,scenario=$sc"
      done ;;
    keysrepeat)
      # the click that activates a never-activated window, five times each: whether the clicked field also gets the
      # focus is a race in the toolkit (see RESULT.md)
      for i in 1 2 3 4 5; do
        for sc in unactivated-click unactivated-xclick; do
          run "keysrepeat-$sc-$i" "$java" "${fork[@]+"${fork[@]}"}" -cp "$repo/spikes/BS9/target/bs9-probe.jar" \
            ai.skillroy.testjava.spikes.bs9.KeysProbe "out=$out/keysrepeat-$i,env=$label,scenario=$sc"
        done
      done ;;
    sample)
      run sample "$java" "${fork[@]+"${fork[@]}"}" \
        "-javaagent:$repo/spikes/BS9/target/bs9-probe.jar=out=$out/sample,env=$label" \
        -cp "$app_jar:$hsqldb" ai.skillroy.testjava.samples.lending.LendingApp ;;
    *) echo "unknown part $part" >&2 ;;
  esac
done
if [ -n "$wmpid" ]; then kill "$wmpid" 2> /dev/null; wait "$wmpid" 2> /dev/null; fi
exit 0
