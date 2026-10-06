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
# Runs a command inside the Linux test image with the repo, the local Maven repository and the host's Maven
# installation mounted at their host paths, under a WM-less Xvfb (1024x768x24).
#
#   ci/run-in-linux.sh mvn -o -q verify -Dskillroy.headless=false
#   SKILLROY_JDK8_HOME=/path/to/linux-x64-jdk8 ci/run-in-linux.sh mvn -o verify -Dskillroy.it.targetJava=/opt/jdk8/bin/java
#
# Environment:
#   SKILLROY_CI_IMAGE   image name (default skillroy-test-java-ci:local; build with: docker build -t skillroy-test-java-ci:local ci)
#   SKILLROY_JDK8_HOME  optional Linux x86-64 JDK 8 directory, mounted read-only at /opt/jdk8
#   SKILLROY_MAVEN_HOME optional Maven home to mount (default: the host mvn's own "Maven home")
#   SKILLROY_NO_XVFB=1  run without Xvfb (headless)
#   SKILLROY_PLATFORM   the image platform (default linux/amd64, the project's CI architecture; linux/arm64 runs an
#                       arm64 build of the image natively on an arm64 development machine)
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="${SKILLROY_CI_IMAGE:-skillroy-test-java-ci:local}"
platform="${SKILLROY_PLATFORM:-linux/amd64}"
m2="${SKILLROY_M2:-$HOME/.m2}"
# Maven is pure Java: reuse the host installation ("Maven home" as reported by mvn itself) instead of baking one in.
mvn_home="${SKILLROY_MAVEN_HOME:-$(mvn -v 2>/dev/null | sed -n 's/^Maven home: //p')}"
mounts=(-v "$repo:$repo" -v "$m2:/root/.m2" -w "$PWD")
env=(-e "MAVEN_OPTS=${MAVEN_OPTS:-}")
if [ -n "$mvn_home" ] && [ -d "$mvn_home" ]; then
  mounts+=(-v "$mvn_home:/opt/maven:ro")
  env+=(-e "PATH=/opt/maven/bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
fi
if [ -n "${SKILLROY_JDK8_HOME:-}" ]; then mounts+=(-v "$SKILLROY_JDK8_HOME:/opt/jdk8:ro"); fi
if [ "${SKILLROY_NO_XVFB:-0}" = "1" ]; then
  exec docker run --rm --init --platform "$platform" "${mounts[@]}" "${env[@]}" "$image" "$@"
fi
# --init: xvfb-run waits for a signal from Xvfb that never arrives when xvfb-run itself is PID 1.
exec docker run --rm --init --platform "$platform" "${mounts[@]}" "${env[@]}" "$image" \
  xvfb-run -a -s "-screen 0 1024x768x24 -nolisten tcp" "$@"
