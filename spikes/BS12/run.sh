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
# Spike BS12 (LIVE: model calls cost money; never in CI). Runs the harness in the arm64 Linux test image under the
# WM-less Xvfb (ci/run-in-linux.sh), where it launches the sample lending desk with the agent and talks to the model.
#
#   mvn -o -f spikes/BS12/pom.xml package        # the harness jar
#   spikes/BS12/run.sh prepare                    # copies the CLI's lib/, the agent home and the desk's jars to target/
#   spikes/BS12/run.sh probe                      # no model call: captures, marks, snapshots, ground truth -> target/probe/
#   zsh -lc 'spikes/BS12/run.sh count'            # free token counts
#   zsh -lc 'spikes/BS12/run.sh ground --models sonnet,opus,haiku --effort low --reps 1 --threads 4 --tag main1'
#   zsh -lc 'spikes/BS12/run.sh session --task lend --variant v2-toolset --model sonnet'
#   spikes/BS12/run.sh summary                    # host-side: target/summary.md and results/summary.json
#   spikes/BS12/run.sh spend                      # host-side: the total recorded so far
#
# The model key comes from the login shell's environment (zsh -lc) as ANTHROPIC_API_KEY and reaches the container by
# name only, through ci/run-in-linux.sh; it is never on a command line. The image and platform default to the native
# arm64 image; SKILLROY_JDK8_HOME (a Linux JDK 8 for that platform) makes the desk run on Java 8 (/opt/jdk8).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cmd="${1:-}"
[ -n "$cmd" ] || { sed -n '17,33p' "$0"; exit 2; }
shift
main=ai.skillroy.testjava.spikes.bs12.Bs12
case "$cmd" in
  prepare)
    rm -rf "$here/target/lib" "$here/target/agent-home" "$here/target/desk"
    mkdir -p "$here/target/desk"
    cp -R "$repo/controller/skillroy-cli/target/skillroy-cli/lib" "$here/target/lib"
    cp -R "$repo/samples/skillroy-sample-lending-scenarios/target/skillroy-agent" "$here/target/agent-home"
    cp "$repo/samples/skillroy-sample-lending-scenarios/target/it-target/skillroy-sample-lending-app.jar" \
       "$repo/samples/skillroy-sample-lending-scenarios/target/it-target/hsqldb.jar" "$here/target/desk/"
    echo "prepared: $(ls "$here/target/lib" | wc -l | tr -d ' ') jars in target/lib, agent home, desk jars"
    ;;
  summary|spend)
    java -Dbs12.home="$here" -cp "$here/target/bs12-harness.jar:$here/target/lib/*" "$main" "$cmd" "$@"
    ;;
  probe|count|ground|session)
    [ -s "$here/target/bs12-harness.jar" ] || { echo "build first: mvn -o -f spikes/BS12/pom.xml package" >&2; exit 2; }
    [ -d "$here/target/lib" ] || { echo "run '$0 prepare' first" >&2; exit 2; }
    export SKILLROY_PLATFORM="${SKILLROY_PLATFORM:-linux/arm64}"
    export SKILLROY_CI_IMAGE="${SKILLROY_CI_IMAGE:-skillroy-test-java-ci:bs8-arm64}"
    target_java=/opt/java/openjdk/bin/java
    if [ -n "${SKILLROY_JDK8_HOME:-}" ]; then target_java=/opt/jdk8/bin/java; fi
    cd "$repo"
    exec ci/run-in-linux.sh /opt/java/openjdk/bin/java -Djava.awt.headless=false -Dbs12.home="$here" \
      -Dbs12.targetJava="$target_java" -cp "$here/target/bs12-harness.jar:$here/target/lib/*" "$main" "$cmd" "$@"
    ;;
  *)
    echo "unknown command $cmd" >&2
    exit 2
    ;;
esac
