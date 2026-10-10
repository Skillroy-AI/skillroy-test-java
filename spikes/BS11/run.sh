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
# Spike BS11: PAINT capture determinism. Runs the harness in each environment (one container per environment through
# ci/run-in-linux.sh; "mac-j25" natively on this machine's display), then compares the runs. Build first (offline):
#
#   mvn -o -f spikes/BS11/pom.xml package
#   docker build --platform linux/arm64 -t skillroy-spike-bs11:tools    -f spikes/BS11/images/Dockerfile.tools    spikes/BS11/images
#   docker build --platform linux/arm64 -t skillroy-spike-bs11:fonts    -f spikes/BS11/images/Dockerfile.fonts    spikes/BS11/images
#   docker build --platform linux/arm64 -t skillroy-spike-bs11:fontpref -f spikes/BS11/images/Dockerfile.fontpref spikes/BS11/images
#   BS11_JDK8_ARM64=/path/to/linux-arm64-jdk8 BS11_JDK8_AMD64=/path/to/linux-x64-jdk8 spikes/BS11/run.sh [env ...]
#   spikes/BS11/run.sh compare
#
# Environments (default: all but mac-j25, which shows a window on this machine's desktop for a few seconds):
#   arm64-j25-base arm64-j25 arm64-j8 arm64-j25-aa-on arm64-j25-aa-off arm64-j25-aa-lcd arm64-j25-aatext arm64-j8-aa-lcd
#   arm64-j25-depth16 arm64-j25-fonts arm64-j8-fonts arm64-j25-fontpref amd64-j25 amd64-j8 mac-j25
# Evidence: target/runs/<env>/ (summary.json, captures.csv, env.json, png/, tree/, logs/); target/compare/.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
if [ ! -s "$here/target/bs11/bs11-harness.jar" ]; then
  echo "build first: mvn -o -f spikes/BS11/pom.xml package" >&2
  exit 2
fi

# The agent homes: the built distribution unpacked twice; the desk's also gets the sample's agent module.
prepare_homes() {
  local zip="$repo/agent/skillroy-agent-dist/target/skillroy-agent.zip"
  local lending="$repo/samples/skillroy-sample-lending-agent/target/skillroy-sample-lending-agent-0.1.0-SNAPSHOT.jar"
  rm -rf "$here/target/home-plain" "$here/target/home-desk"
  mkdir -p "$here/target/home-plain" "$here/target/home-desk"
  unzip -q "$zip" -d "$here/target/home-plain"
  unzip -q "$zip" -d "$here/target/home-desk"
  cp "$lending" "$here/target/home-desk/skillroy-agent/modules/skillroy-sample-lending-agent.jar"
}

J25=/opt/java/openjdk/bin/java
J8=/opt/jdk8/bin/java
ARM=(SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-spike-bs11:tools)

linux() { # <label> <host env...> -- <container env...> -- <java> [jvm options...]
  local label="$1"; shift
  local host=() inner=()
  while [ "$1" != "--" ]; do host+=("$1"); shift; done; shift
  while [ "$1" != "--" ]; do inner+=("$1"); shift; done; shift
  printf '%-22s ' "$label"
  (cd "$repo" && env ${host[@]+"${host[@]}"} ci/run-in-linux.sh env BS11_IMAGE="${label}" ${inner[@]+"${inner[@]}"} \
    bash spikes/BS11/scripts/run-env.sh "$label" "$@") > "$here/target/console-$label.txt" 2>&1 || true
  head -1 "$here/target/console-$label.txt" | grep -o 'BS11 run.*' || tail -3 "$here/target/console-$label.txt"
}

run_env() {
  local e="$1"
  local j8a="SKILLROY_JDK8_HOME=${BS11_JDK8_ARM64:-}" j8x="SKILLROY_JDK8_HOME=${BS11_JDK8_AMD64:-}"
  local few=(BS11_CAPTURES=20 BS11_LAUNCHES=2)
  case "$e" in
    arm64-j25-base) linux "$e" SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-test-java-ci:bs8-arm64 -- \
                      BS11_MOVE_TOOL=none "${few[@]}" -- "$J25" ;;
    arm64-j25)      linux "$e" "${ARM[@]}" -- -- "$J25" ;;
    arm64-j8)       linux "$e" "${ARM[@]}" "$j8a" -- -- "$J8" ;;
    arm64-j25-aa-on)  linux "$e" "${ARM[@]}" -- "${few[@]}" -- "$J25" -Dawt.useSystemAAFontSettings=on ;;
    arm64-j25-aa-off) linux "$e" "${ARM[@]}" -- "${few[@]}" -- "$J25" -Dawt.useSystemAAFontSettings=off ;;
    arm64-j25-aa-lcd) linux "$e" "${ARM[@]}" -- "${few[@]}" -- "$J25" -Dawt.useSystemAAFontSettings=lcd ;;
    arm64-j25-aatext) linux "$e" "${ARM[@]}" -- "${few[@]}" -- "$J25" -Dswing.aatext=true ;;
    arm64-j8-aa-lcd)  linux "$e" "${ARM[@]}" "$j8a" -- "${few[@]}" -- "$J8" -Dawt.useSystemAAFontSettings=lcd ;;
    arm64-j25-depth16) linux "$e" "${ARM[@]}" SKILLROY_NO_XVFB=1 -- BS11_XVFB_SCREEN=1024x768x16 "${few[@]}" -- "$J25" ;;
    arm64-j25-fonts)  linux "$e" SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-spike-bs11:fonts -- \
                        "${few[@]}" -- "$J25" ;;
    arm64-j8-fonts)   linux "$e" SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-spike-bs11:fonts "$j8a" -- \
                        "${few[@]}" -- "$J8" ;;
    arm64-j25-fontpref) linux "$e" SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-spike-bs11:fontpref -- \
                        "${few[@]}" -- "$J25" ;;
    amd64-j25)      linux "$e" SKILLROY_PLATFORM=linux/amd64 SKILLROY_CI_IMAGE=skillroy-test-java-ci:local -- \
                      BS11_CAPTURES=30 BS11_LAUNCHES=2 BS11_HARNESS_OPTS=-Djdk.lang.Process.launchMechanism=FORK -- "$J25" ;;
    amd64-j8)       linux "$e" SKILLROY_PLATFORM=linux/amd64 SKILLROY_CI_IMAGE=skillroy-test-java-ci:local "$j8x" -- \
                      BS11_CAPTURES=30 BS11_LAUNCHES=2 BS11_HARNESS_OPTS=-Djdk.lang.Process.launchMechanism=FORK -- "$J8" ;;
    mac-j25)        "$here/scripts/run-mac.sh" ;;
    *) echo "unknown environment $e" >&2; return 2 ;;
  esac
}

compare() {
  local cp="$here/target/bs11/bs11-harness.jar:$repo/controller/skillroy-cli/target/skillroy-cli/lib/*"
  local runs="$here/target/runs" cmp="$here/target/compare"
  rm -rf "$cmp"; mkdir -p "$cmp"
  local base="$runs/arm64-j25" noise=() changes=()
  for other in arm64-j25-base arm64-j8 amd64-j25 mac-j25 arm64-j25-aa-on arm64-j25-aa-off arm64-j25-aa-lcd \
               arm64-j25-aatext arm64-j25-depth16 arm64-j25-fonts arm64-j25-fontpref; do
    [ -f "$runs/$other/summary.json" ] || continue
    java -cp "$cp" ai.skillroy.testjava.spikes.bs11.Bs11 compare "$base" "$runs/$other" "$cmp/arm64-j25__$other.json" \
      > "$cmp/arm64-j25__$other.txt"
    noise+=("$cmp/arm64-j25__$other.json")
  done
  for pair in "arm64-j8 amd64-j8" "arm64-j8 arm64-j8-aa-lcd" "arm64-j8 arm64-j8-fonts" "amd64-j25 amd64-j8"; do
    set -- $pair
    [ -f "$runs/$1/summary.json" ] && [ -f "$runs/$2/summary.json" ] || continue
    java -cp "$cp" ai.skillroy.testjava.spikes.bs11.Bs11 compare "$runs/$1" "$runs/$2" "$cmp/$1__$2.json" \
      > "$cmp/$1__$2.txt"
    noise+=("$cmp/$1__$2.json")
  done
  for run in arm64-j25 arm64-j8; do
    [ -f "$runs/$run/summary.json" ] || continue
    for c in label disable colour tint; do
      java -cp "$cp" ai.skillroy.testjava.spikes.bs11.Bs11 pair "$runs/$run" toy-base "toy-$c" "$cmp/$run-toy-$c.json" \
        > /dev/null
      changes+=("$cmp/$run-toy-$c.json")
    done
    java -cp "$cp" ai.skillroy.testjava.spikes.bs11.Bs11 pair "$runs/$run" desk-typed desk-typed-1char \
      "$cmp/$run-desk-1char.json" > /dev/null
    changes+=("$cmp/$run-desk-1char.json")
  done
  java -cp "$cp" ai.skillroy.testjava.spikes.bs11.Bs11 table "${noise[@]}" -- "${changes[@]}" > "$cmp/tables.md"
  cat "$cmp/tables.md"
}

if [ "${1:-}" = "compare" ]; then
  compare
  exit 0
fi
prepare_homes
envs=("$@")
if [ ${#envs[@]} -eq 0 ]; then
  envs=(arm64-j25-base arm64-j25 arm64-j8 arm64-j25-aa-on arm64-j25-aa-off arm64-j25-aa-lcd arm64-j25-aatext
        arm64-j8-aa-lcd arm64-j25-depth16 arm64-j25-fonts arm64-j8-fonts arm64-j25-fontpref amd64-j25 amd64-j8)
fi
for e in "${envs[@]}"; do
  run_env "$e"
done
echo "BS11 DONE"
