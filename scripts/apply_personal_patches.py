"""Generate patched build copies while leaving pinned upstream sources untouched."""
import argparse
import hashlib
import importlib
import json
import shutil
from pathlib import Path

PATCHES = Path(__file__).parent / "patches"
COMPONENTS = {"librime", "librime-lua", "librime-predict", "librime-octagram", "fcitx5-rime", "fcitx5-anthy"}


def apply(component, source, output):
    source, output = source.resolve(), output.resolve()
    root = Path(__file__).resolve().parents[1]
    if not output.is_relative_to(root):
        raise ValueError("Generated sources must stay inside the project workspace")
    if output == source or source.is_relative_to(output) or output.is_relative_to(source):
        raise ValueError("Patched output must be separate from upstream sources")
    digest = hashlib.sha256(component.encode())
    files = sorted(p for p in source.rglob("*") if p.is_file())
    module = PATCHES / (component.replace("-", "_") + ".py")
    rules = [Path(__file__), PATCHES / "common.py"]
    if module.exists():
        rules.append(module)
    for path in [*rules, *files]:
        digest.update(str(path.relative_to(source) if path.is_relative_to(source) else path.name).encode())
        digest.update(path.read_bytes())
    signature = digest.hexdigest()
    marker = output / ".personal-patches.json"
    if marker.exists() and json.loads(marker.read_text(encoding="utf-8"))["signature"] == signature:
        return
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(source, output)
    if component != "librime-octagram":
        importlib.import_module("patches." + component.replace("-", "_")).apply(output)
    marker.write_text(json.dumps({"component": component, "signature": signature}), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--component", choices=sorted(COMPONENTS), required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    apply(args.component, args.source, args.output)
