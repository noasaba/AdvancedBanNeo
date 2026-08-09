#!/usr/bin/env bash
set -euo pipefail

legacy_jar=$(find bundle/target -maxdepth 1 -type f -name 'AdvancedBan-Neo-*.jar' ! -name 'original-*' | head -n 1)
velocity_jar=$(find velocity/target -maxdepth 1 -type f -name 'AdvancedBan-Neo-Velocity-*.jar' ! -name 'original-*' | head -n 1)
if [[ -z ${legacy_jar:-} || -z ${velocity_jar:-} ]]; then
  echo 'AdvancedBan Neo artifacts were not found' >&2
  exit 1
fi

artifact_tmp=$(mktemp -d)
trap 'rm -rf -- "$artifact_tmp"' EXIT
mkdir -p "$artifact_tmp/legacy" "$artifact_tmp/velocity"
unzip -q "$legacy_jar" -d "$artifact_tmp/legacy"
unzip -q "$velocity_jar" -d "$artifact_tmp/velocity"

class_major() {
  local class_file=$1 first second
  read -r first second < <(od -An -tu1 -j6 -N2 "$class_file")
  echo $((first * 256 + second))
}

while IFS= read -r -d '' class_file; do
  major=$(class_major "$class_file")
  if (( major > 52 )); then
    echo "Legacy project class is newer than Java 8: $class_file (major $major)" >&2
    exit 1
  fi
done < <(find "$artifact_tmp/legacy/me/leoko/advancedban" \
  -path '*/shaded/*' -prune -o -type f -name '*.class' -print0)

while IFS= read -r -d '' class_file; do
  major=$(class_major "$class_file")
  if (( major != 69 )); then
    echo "Velocity adapter class is not Java 25: $class_file (major $major)" >&2
    exit 1
  fi
done < <(find "$artifact_tmp/velocity/me/leoko/advancedban/velocity" \
  -type f -name '*.class' -print0)

test -f "$artifact_tmp/legacy/plugin.yml"
test -f "$artifact_tmp/legacy/bungee.yml"
test -f "$artifact_tmp/velocity/velocity-plugin.json"
if [[ -d "$artifact_tmp/legacy/dev/chatsyncer" ]]; then
  echo 'ChatSyncer API classes must not be bundled' >&2
  exit 1
fi

echo 'Artifact descriptors, Java boundaries, and optional API isolation verified.'
