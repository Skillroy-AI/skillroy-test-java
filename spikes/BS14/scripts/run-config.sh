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
# Spike BS14, inside the Linux test image: runs one configuration and leaves its evidence in
# spikes/BS14/target/runs/<configuration>/ (stdout.log and stderr.log of the target JVM, probe.log, result.properties,
# java-version.txt, exit.txt, console.txt: the target's console lines that are not the probe's own markers).
# Called by spikes/BS14/run.sh through ci/run-in-linux.sh; the image's JDK 25 is /opt/java/openjdk, JDK 8 is /opt/jdk8;
# the Byteman jar is the local Maven repository's (mounted at /root/.m2).
#
#   scripts/run-config.sh <configuration> [runs directory name, default "runs"]
#
# Configurations (target JDK, what differs):
#   jdk8-javaagent, jdk25-javaagent                   the module's option string: listener:false,manager:<RuleManager>
#   jdk8-contract-options, jdk25-contract-options     the contract's first string, with prop:org.jboss.byteman.verbose=false
#   jdk25-order-reversed                              manager:<RuleManager>,listener:false (the order matters: control)
set -uo pipefail
config="$1"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dist="$here/target/bs14"
out="$here/target/${2:-runs}/$config"
rm -rf "$out"
mkdir -p "$out"
j25=/opt/java/openjdk/bin/java
j8=/opt/jdk8/bin/java
byteman="${BS14_BYTEMAN_JAR:-/root/.m2/repository/org/jboss/byteman/byteman/4.0.27/byteman-4.0.27.jar}"
manager=ai.skillroy.testjava.spikes.bs14.sys.RuleManager
bmopts="listener:false,manager:$manager"
case "$config" in
  jdk8-javaagent)          java=$j8 ;;
  jdk25-javaagent)         java=$j25 ;;
  jdk8-contract-options)   java=$j8;  bmopts="listener:false,manager:$manager,prop:org.jboss.byteman.verbose=false" ;;
  jdk25-contract-options)  java=$j25; bmopts="listener:false,manager:$manager,prop:org.jboss.byteman.verbose=false" ;;
  jdk25-order-reversed)    java=$j25; bmopts="manager:$manager,listener:false" ;;
  *) echo "unknown configuration $config" >&2; exit 2 ;;
esac
"$java" -version > "$out/java-version.txt" 2>&1
opts="out=$out;probe=$dist/bs14-probe.jar;sys=$dist/bs14-sys.jar;byteman=$byteman;bmopts=$bmopts"
start=$(date +%s)
"$java" "-javaagent:$dist/bs14-agent.jar=$opts" -cp "$dist/bs14-toy.jar" ai.skillroy.testjava.spikes.bs14.toy.ToyHost \
    --wait-seconds 120 > "$out/stdout.log" 2> "$out/stderr.log"
echo "host=$?" > "$out/exit.txt"
echo "seconds=$(( $(date +%s) - start ))" >> "$out/exit.txt"
# Every console line of the target that is neither the probe's marker nor the toy's own output.
{ sed 's/^/stdout: /' "$out/stdout.log"; sed 's/^/stderr: /' "$out/stderr.log"; } \
    | grep -v -e 'BS14-MARK' -e 'stdout: toy: ' > "$out/console.txt" || true
echo "consoleLines=$(wc -l < "$out/console.txt" | tr -d ' ')" >> "$out/exit.txt"
echo "jep451=$(grep -c -i 'loaded dynamically' "$out/stderr.log")" >> "$out/exit.txt"
tr '\n' ' ' < "$out/exit.txt"
echo
