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

# Checks that every source file starts with the Apache-2.0 licence header, or adds it where it is missing.
#
#   ci/license-headers.sh            list the files without a header; exit 1 if there are any
#   ci/license-headers.sh --apply    add the header to those files
#
# Covered: *.java, *.js, *.xml, *.sh, *.properties, Dockerfile and the CLI launcher script.
# Not covered: documentation, scenario YAML and plan JSON (a plan carries the hash of its YAML), service files
# and other test resources.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-check}"
marker='Licensed under the Apache License, Version 2.0'

text='Copyright 2026 Mike Mills.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.'

# Prints the header in the comment style of the given kind (java | xml | hash).
header() {
  case "$1" in
    java) printf '/*\n'; printf '%s\n' "$text" | sed -e 's/^/ * /' -e 's/[[:space:]]*$//'; printf ' */\n' ;;
    xml)  printf '<!--\n'; printf '%s\n' "$text" | sed -e 's/^/  /' -e 's/[[:space:]]*$//'; printf -- '-->\n' ;;
    hash) printf '#\n'; printf '%s\n' "$text" | sed -e 's/^/# /' -e 's/[[:space:]]*$//'; printf '#\n' ;;
  esac
}

kind_of() {
  case "$1" in
    *.java|*.js) echo java ;;
    *.xml) echo xml ;;
    *.sh|*.properties|*/Dockerfile|*/src/main/sh/*) echo hash ;;
    *) echo none ;;
  esac
}

apply() { # file, kind
  local file="$1" kind="$2" tmp first
  tmp="$(mktemp)"
  first="$(head -n 1 "$file")"
  case "$kind:$first" in
    xml:'<?xml'*|hash:'#!'*)
      { printf '%s\n' "$first"; header "$kind"; tail -n +2 "$file"; } > "$tmp" ;;
    java:*)
      { header "$kind"; printf '\n'; cat "$file"; } > "$tmp" ;;
    *)
      { header "$kind"; cat "$file"; } > "$tmp" ;;
  esac
  cat "$tmp" > "$file"   # keeps the file's mode
  rm -f "$tmp"
}

missing=0
while IFS= read -r -d '' file; do
  kind="$(kind_of "$file")"
  [ "$kind" = none ] && continue
  if head -n 25 "$file" | grep -qF "$marker"; then continue; fi
  missing=$((missing + 1))
  if [ "$mode" = "--apply" ]; then
    apply "$file" "$kind"
    echo "added: ${file#"$repo"/}"
  else
    echo "missing: ${file#"$repo"/}"
  fi
done < <(find "$repo" \( -name .git -o -name target -o -name .idea -o -name .vscode -o -name node_modules \) -prune \
           -o -type f \( -name '*.java' -o -name '*.js' -o -name '*.xml' -o -name '*.sh' -o -name '*.properties' \
                         -o -name Dockerfile -o -path '*/src/main/sh/*' \) -print0)

if [ "$mode" = "--apply" ]; then
  echo "licence header added to $missing file(s)"
elif [ "$missing" -gt 0 ]; then
  echo "$missing file(s) without a licence header; run ci/license-headers.sh --apply" >&2
  exit 1
else
  echo "every source file carries the licence header"
fi
