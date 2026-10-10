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
# Spike BS11: the macOS leg ("mac-j25"), natively on this machine's display: the desk, the covering window and the toy
# appear on the desktop for about a minute; no input is sent to the desktop (the agent's own in-JVM calls only).
#
# Environment: BS11_MAC_JAVA_HOME, a macOS Java 25 (default: /usr/libexec/java_home -v 25); BS11_DISPLAY_LOCK, a
# directory used as a lock against other agents showing windows on the same desktop (taken with mkdir, retried every
# 30 s for at most 30 minutes, removed at the end, also on failure); without it no lock is taken.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jh="${BS11_MAC_JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
lock="${BS11_DISPLAY_LOCK:-}"
if [ -n "$lock" ]; then
  waited=0
  until mkdir "$lock" 2>/dev/null; do
    if [ "$waited" -ge 1800 ]; then
      echo "mac-j25: the display lock $lock was not free within 30 minutes: leg skipped" >&2
      exit 3
    fi
    sleep 30
    waited=$((waited + 30))
  done
  trap 'rmdir "$lock" 2>/dev/null || true' EXIT
fi
printf '%-22s ' mac-j25
BS11_HARNESS_JAVA="$jh/bin/java" BS11_MOVE_TOOL=none BS11_CAPTURES="${BS11_CAPTURES:-100}" \
  BS11_LAUNCHES="${BS11_LAUNCHES:-3}" "$here/scripts/run-env.sh" mac-j25 "$jh/bin/java" \
  > "$here/target/console-mac-j25.txt" 2>&1 || true
head -1 "$here/target/console-mac-j25.txt" | grep -o 'BS11 run.*' || tail -3 "$here/target/console-mac-j25.txt"
