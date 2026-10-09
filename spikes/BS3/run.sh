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
# Spike BS3: runs the matrix in the Linux test image (ci/run-in-linux.sh), one container per configuration, and prints
# one line per configuration. Build first (online the first time, for Byte Buddy and the old host libraries):
#
#   mvn -f spikes/BS3/pom.xml package
#   SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-test-java-ci:bs8-arm64 SKILLROY_NO_XVFB=1 \
#     SKILLROY_JDK8_HOME=/path/to/linux-jdk8 spikes/BS3/run.sh [configuration ...]
#
# The configurations are listed in scripts/run-config.sh; the default is all of them. Evidence: target/runs/<config>/,
# or target/$BS3_RUNS/<config>/ when BS3_RUNS names another directory (to keep a repeated matrix beside the first).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
if [ ! -s "$here/target/bs3/bs3-agent.jar" ]; then
  echo "build first: mvn -f spikes/BS3/pom.xml package" >&2
  exit 2
fi
configs=("$@")
if [ ${#configs[@]} -eq 0 ]; then
  configs=(jdk25-javaagent jdk8-javaagent jdk25-attach jdk25-attach-enabled jdk25-attach-disabled jdk8-attach
           jdk25-javaspy jdk8-javaspy jdk25-hostlibs jdk8-hostlibs jdk25-hostlibs-unrelocated jdk8-hostlibs-unrelocated
           jdk25-nexus-default jdk8-nexus-default jdk25-xshare-off jdk25-system-append jdk8-system-append)
fi
for c in "${configs[@]}"; do
  printf '%-28s ' "$c"
  (cd "$repo" && ci/run-in-linux.sh bash spikes/BS3/scripts/run-config.sh "$c" "${BS3_RUNS:-runs}") 2>&1 | tail -1
  r="$here/target/${BS3_RUNS:-runs}/$c"
  verdict="$(grep '^probe.verdict=' "$r/result.properties" 2>/dev/null | cut -d= -f2- || true)"
  warnings="$(grep -ci 'warning' "$r/stderr.log" 2>/dev/null || true)"
  printf '%-28s verdict: %s; stderr lines with "warning": %s\n' "" "${verdict:-none}" "${warnings:-0}"
done
echo "BS3 DONE"
