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
# Spike BS11: one environment's run of the harness (inside the Linux image through ci/run-in-linux.sh, or natively on
# macOS). Usage: run-env.sh <label> <target java> [target JVM option ...]
#
# Environment: BS11_XVFB_SCREEN (e.g. 1024x768x16) starts an Xvfb of that screen first (the container must then be
# started with SKILLROY_NO_XVFB=1); BS11_MOVE_TOOL (xdotool or none, default: xdotool when it is installed);
# BS11_CAPTURES (default 100), BS11_LAUNCHES (default 5); BS11_RUNS (the runs directory below target/, default runs);
# BS11_HARNESS_JAVA (default java on the PATH) and BS11_HARNESS_OPTS (options of the harness JVM).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$here/../.." && pwd)"
if [ -n "${BS11_XVFB_SCREEN:-}" ] && [ -z "${DISPLAY:-}" ]; then
  exec xvfb-run -a -s "-screen 0 $BS11_XVFB_SCREEN -nolisten tcp" "$0" "$@"
fi
label="$1"; shift
target_java="$1"; shift
args=()
for a in "$@"; do args+=(--jvm-arg "$a"); done
move="${BS11_MOVE_TOOL:-}"
if [ -z "$move" ]; then
  if command -v xdotool >/dev/null 2>&1; then move=xdotool; else move=none; fi
fi
out="$here/target/${BS11_RUNS:-runs}/$label"
rm -rf "$out"
mkdir -p "$out"
harness_java="${BS11_HARNESS_JAVA:-java}"
cp="$here/target/bs11/bs11-harness.jar:$repo/controller/skillroy-cli/target/skillroy-cli/lib/*"
# Bounded: a run that hangs is ended after 20 minutes (the harness bounds every wait itself; this is the backstop).
"$harness_java" ${BS11_HARNESS_OPTS:-} -cp "$cp" ai.skillroy.testjava.spikes.bs11.Bs11 run \
  --out "$out" --label "$label" --java "$target_java" --dist "$here/target/bs11" \
  --desk-home "$here/target/home-desk/skillroy-agent" --plain-home "$here/target/home-plain/skillroy-agent" \
  --app-jar "$repo/samples/skillroy-sample-lending-app/target/skillroy-sample-lending-app-0.1.0-SNAPSHOT.jar" \
  --captures "${BS11_CAPTURES:-100}" --launches "${BS11_LAUNCHES:-5}" --move-tool "$move" \
  ${args[@]+"${args[@]}"} > "$out/harness.log" 2>&1 &
pid=$!
( sleep 1200; kill "$pid" 2>/dev/null ) &
watchdog=$!
status=0
wait "$pid" || status=$?
kill "$watchdog" 2>/dev/null || true
cat "$out/harness.log"
echo "exit=$status" > "$out/exit.txt"
exit "$status"
