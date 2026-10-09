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
# Spike BS3, inside the Linux test image: runs one configuration of the matrix and leaves its evidence in
# spikes/BS3/target/runs/<configuration>/ (stdout.log and stderr.log of the target JVM, probe.log, result.properties,
# attach.log for the ATTACH configurations, safepoint.log on Java 25, java-version.txt, exit codes in exit.txt).
# Called by spikes/BS3/run.sh through ci/run-in-linux.sh; the image's JDK 25 is /opt/java/openjdk, JDK 8 is /opt/jdk8.
#
#   scripts/run-config.sh <configuration> [runs directory name, default "runs"]
#
# Configurations (target JDK, load mode, what differs):
#   jdk8-javaagent, jdk25-javaagent            -javaagent, Spy in the project's package, the full probe with the sweep
#   jdk8-attach, jdk25-attach                  dynamic attach from the JDK 25 client; Java 25 without any flag
#   jdk25-attach-enabled                       Java 25 target started with -XX:+EnableDynamicAgentLoading
#   jdk25-attach-disabled                      Java 25 target started with -XX:-EnableDynamicAgentLoading (refusal)
#   jdk8-javaspy, jdk25-javaspy                Spy relocated into a java.* package (item 1)
#   jdk8-hostlibs, jdk25-hostlibs              the host ships Byte Buddy 1.12.19, ASM 5.0.3, JNA 4.5.2 (item 2)
#   jdk8-hostlibs-unrelocated, jdk25-hostlibs-unrelocated
#                                              the same host, and the control engine whose Byte Buddy is not relocated
#   jdk8-nexus-default, jdk25-nexus-default    Byte Buddy's Nexus injection left at its default (item 6)
#   jdk25-xshare-off                           Java 25 target with class data sharing off (-Xshare:off)
#   jdk25-system-append, jdk8-system-append    also appends a jar to the system class path at the end, for comparison
set -uo pipefail
config="$1"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dist="$here/target/bs3"
out="$here/target/${2:-runs}/$config"
rm -rf "$out"
mkdir -p "$out"
j25=/opt/java/openjdk/bin/java
j8=/opt/jdk8/bin/java
cp="$dist/bs3-host.jar:$dist/lib/*"
main=ai.skillroy.testjava.spikes.bs3.host.LendingDeskHost
engine=bs3-engine.jar
spy=bs3-spy.jar
expect=relocated
sweep=true
nexus=disabled
sysappend=false
mode=javaagent
wait=300
flags=()
case "$config" in
  jdk8-javaagent)   java=$j8 ;;
  jdk25-javaagent)  java=$j25 ;;
  jdk8-attach)      java=$j8;  mode=attach ;;
  jdk25-attach)     java=$j25; mode=attach ;;
  jdk25-attach-enabled)  java=$j25; mode=attach; flags+=(-XX:+EnableDynamicAgentLoading) ;;
  jdk25-attach-disabled) java=$j25; mode=attach; sweep=false; wait=15; flags+=(-XX:-EnableDynamicAgentLoading) ;;
  jdk8-javaspy)     java=$j8;  engine=bs3-engine-javaspy.jar; spy=bs3-spy-java.jar; sweep=false ;;
  jdk25-javaspy)    java=$j25; engine=bs3-engine-javaspy.jar; spy=bs3-spy-java.jar; sweep=false ;;
  jdk8-hostlibs)    java=$j8;  cp="$cp:$dist/hostlibs/*"; sweep=false ;;
  jdk25-hostlibs)   java=$j25; cp="$cp:$dist/hostlibs/*"; sweep=false; flags+=(-Dnet.bytebuddy.experimental=true) ;;
  jdk8-hostlibs-unrelocated)  java=$j8;  cp="$cp:$dist/hostlibs/*"; engine=bs3-engine-unrelocated.jar; expect=none; sweep=false ;;
  jdk25-hostlibs-unrelocated) java=$j25; cp="$cp:$dist/hostlibs/*"; engine=bs3-engine-unrelocated.jar; expect=none; sweep=false
                    flags+=(-Dnet.bytebuddy.experimental=true) ;;
  jdk8-nexus-default)  java=$j8;  nexus=default; sweep=false ;;
  jdk25-nexus-default) java=$j25; nexus=default; sweep=false ;;
  jdk25-xshare-off)    java=$j25; sweep=false; flags+=(-Xshare:off) ;;
  jdk25-system-append) java=$j25; sweep=false; sysappend=true ;;
  jdk8-system-append)  java=$j8;  sweep=false; sysappend=true ;;
  *) echo "unknown configuration $config" >&2; exit 2 ;;
esac
# Safepoint evidence beside the probe's own pause measurement.
if [ "$java" = "$j25" ]; then
  flags+=("-Xlog:safepoint=info:file=$out/safepoint.log")
else
  flags+=(-XX:+PrintGCApplicationStoppedTime)
fi
"$java" -version > "$out/java-version.txt" 2>&1
opts="out=$out,mode=$mode,engine=$engine,spy=$spy,expectEngine=$expect,sweep=$sweep,nexus=$nexus,sysAppend=$sysappend"
start=$(date +%s)
if [ "$mode" = javaagent ]; then
  "$java" "${flags[@]}" "-javaagent:$dist/bs3-agent.jar=$opts" -cp "$cp" "$main" --out "$out" \
      --toy-jar "$dist/bs3-toy.jar" --wait-seconds "$wait" > "$out/stdout.log" 2> "$out/stderr.log"
  echo "host=$?" > "$out/exit.txt"
else
  "$java" "${flags[@]}" -cp "$cp" "$main" --out "$out" --toy-jar "$dist/bs3-toy.jar" --wait-seconds "$wait" \
      > "$out/stdout.log" 2> "$out/stderr.log" &
  host=$!
  for _ in $(seq 1 600); do [ -s "$out/host.ready" ] && break; sleep 0.1; done
  pid="$(cat "$out/host.ready" 2>/dev/null)"
  "$j25" -cp "$dist/bs3-host.jar" ai.skillroy.testjava.spikes.bs3.host.Attach "$pid" "$dist/bs3-agent.jar" "$opts" \
      > "$out/attach.log" 2>&1
  echo "attach=$?" > "$out/exit.txt"
  wait "$host"
  echo "host=$?" >> "$out/exit.txt"
fi
echo "seconds=$(( $(date +%s) - start ))" >> "$out/exit.txt"
cat "$out/exit.txt" | tr '\n' ' '
echo
