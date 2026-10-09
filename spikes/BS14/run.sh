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
# Spike BS14: runs the configurations in the Linux test image (ci/run-in-linux.sh), one container each, and prints one
# line per configuration. Build first (offline: Byteman 4.0.27 is in the local Maven repository):
#
#   mvn -o -f spikes/BS14/pom.xml package
#   SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-test-java-ci:bs8-arm64 SKILLROY_NO_XVFB=1 \
#     SKILLROY_JDK8_HOME=/path/to/linux-jdk8 spikes/BS14/run.sh [configuration ...]
#
# The configurations are listed in scripts/run-config.sh; the default is all of them. Evidence: target/runs/<config>/,
# or target/$BS14_RUNS/<config>/ when BS14_RUNS names another directory.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
if [ ! -s "$here/target/bs14/bs14-agent.jar" ]; then
  echo "build first: mvn -o -f spikes/BS14/pom.xml package" >&2
  exit 2
fi
configs=("$@")
if [ ${#configs[@]} -eq 0 ]; then
  configs=(jdk25-javaagent jdk8-javaagent jdk25-contract-options jdk8-contract-options jdk25-order-reversed)
fi
for c in "${configs[@]}"; do
  printf '%-26s ' "$c"
  (cd "$repo" && ci/run-in-linux.sh bash spikes/BS14/scripts/run-config.sh "$c" "${BS14_RUNS:-runs}") 2>&1 | tail -1
  r="$here/target/${BS14_RUNS:-runs}/$c"
  verdict="$(grep '^probe.verdict=' "$r/result.properties" 2>/dev/null | cut -d= -f2- || true)"
  printf '%-26s verdict: %s\n' "" "${verdict:-none}"
done
echo "BS14 DONE"
