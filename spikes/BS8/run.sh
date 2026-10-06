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
# Spike BS8: runs the JCEF probe in the Linux test image, in both rendering modes, on the image's JDK 25 and (when
# SKILLROY_JDK8_HOME is set) on a Java 8 target. Results: spikes/BS8/target/results/<configuration>/result.json plus
# the PNGs, cef.log and probe.log of each run, and <configuration>.log with the JVM's own output.
#
#   mvn -f spikes/BS8/pom.xml -q package            # the probe jar and target/lib (the natives are 152 MB)
#   SKILLROY_JDK8_HOME=/path/to/linux-x64-jdk8 spikes/BS8/run.sh [configuration ...]
#
# Configurations: java25-windowed java25-osr java8-windowed java8-osr (default: all that can run). The remote-debugging
# port is set in every run (9229) so that the target ids can be compared with /json/list; the extra arguments after
# "--" go to the probe (for example "-- --arg --disable-gpu").
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
results="$here/target/results"
mkdir -p "$results"
cp="$here/target/bs8-probe.jar:$here/target/lib/*"
# One natives install directory per image platform (jcefmaven extracts the bundle of the running platform into it).
arch="$(echo "${SKILLROY_PLATFORM:-linux/amd64}" | tr '/' '-')"
install="$here/target/jcef-bundle-$arch"
probe_args=()
configs=()
while [ $# -gt 0 ]; do
  if [ "$1" = "--" ]; then shift; probe_args=("$@"); break; fi
  configs+=("$1"); shift
done
if [ ${#configs[@]} -eq 0 ]; then
  configs=(java25-windowed java25-osr)
  if [ -n "${SKILLROY_JDK8_HOME:-}" ]; then configs+=(java8-windowed java8-osr); fi
fi
# JOGL (off-screen rendering) needs these on JDK 16 or newer (jcefmaven README).
osr_exports=(--add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED
             --add-exports java.desktop/sun.java2d=ALL-UNNAMED)
run() {
  local name="$1" java="$2" mode="$3"; shift 3
  local out="$results/$name"
  rm -rf "$out"; mkdir -p "$out"
  echo "== $name ($java, $mode)"
  local start=$(date +%s)
  set +e
  (cd "$repo" && ci/run-in-linux.sh "$java" "$@" -Djava.awt.headless=false -cp "$cp" \
      ai.skillroy.testjava.spikes.bs8.Bs8Probe --mode "$mode" --out "$out" --install "$install" \
      --port 9229 --budget 600 ${probe_args[@]+"${probe_args[@]}"}) > "$results/$name.log" 2>&1
  local rc=$?
  set -e
  echo "   exit $rc after $(( $(date +%s) - start )) s; $(grep -c '"error"' "$out/result.json" 2>/dev/null || echo '?') step errors"
}
for c in "${configs[@]}"; do
  case "$c" in
    java25-windowed) run "$c" /opt/java/openjdk/bin/java windowed ;;
    java25-osr)      run "$c" /opt/java/openjdk/bin/java osr "${osr_exports[@]}" ;;
    java8-windowed)  run "$c" /opt/jdk8/bin/java windowed ;;
    java8-osr)       run "$c" /opt/jdk8/bin/java osr ;;
    *) echo "unknown configuration $c" >&2; exit 2 ;;
  esac
done
echo "BS8 DONE"
