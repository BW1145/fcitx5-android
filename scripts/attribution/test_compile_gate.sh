#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/build/attribution-store-test"; mkdir -p "$out"
cmake="${CMAKE:-cmake}"
command -v "$cmake" >/dev/null # Do not confuse a missing compiler with gate rejection.
python3 - "$root" "$out" <<'PY'
import sys
from pathlib import Path
s=(Path(sys.argv[1])/'app/src/main/cpp/CMakeLists.txt').read_text()
s=s[s.index('option(FCITX_CANDIDATE_ATTRIBUTION'):s.index('# For reproducible build')]
(Path(sys.argv[2])/'gate.cmake').write_text(s)
PY
if "$cmake" -DFCITX_CANDIDATE_ATTRIBUTION=ON -DCMAKE_BUILD_TYPE=Release -P "$out/gate.cmake" > "$out/release-gate.txt" 2>&1; then
  echo 'ERROR: release profile option was accepted'; exit 1
fi
grep -q 'forbidden outside Debug' "$out/release-gate.txt"
"$cmake" -DFCITX_CANDIDATE_ATTRIBUTION=OFF -DCMAKE_BUILD_TYPE=Release -P "$out/gate.cmake"
echo 'PASS: Release rejects profiling ON and accepts default OFF; positive Debug path is built by CI'
