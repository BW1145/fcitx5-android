"""Compare actual full/lite APKs; allow only the declared bundled-data removals."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

from verify_personal_apk import require

ROOT = Path(__file__).resolve().parents[1]


def compare(full_path, lite_path):
    excluded = {
        "assets/" + line
        for line in (ROOT / "scripts/personal-lite-excludes.txt").read_text().splitlines()
        if line and not line.startswith("#")
    }
    with zipfile.ZipFile(full_path) as full, zipfile.ZipFile(lite_path) as lite:
        full_names, lite_names = set(full.namelist()), set(lite.namelist())
        require(excluded <= full_names, "Full APK is missing declared alternative input data")
        require(not (excluded & lite_names), "Lite APK still bundles excluded input data")
        for prefix in ("assets/usr/share/libime/", "assets/usr/share/fcitx5/table/"):
            require(not any(p.startswith(prefix) for p in lite_names),
                    f"Unlisted alternative input data remains: {prefix}")
        # Data descriptor and signing records change when assets are removed.
        def payload(names):
            return {p for p in names if not p.endswith("/") and
                    (p.startswith(("assets/", "lib/")) or p.endswith(".dex"))
                    and p != "assets/descriptor.json"}

        require(payload(lite_names) == payload(full_names) - excluded,
                "Lite APK changed files outside the declared data removals")
        for name in sorted(payload(lite_names)):
            require(full.read(name) == lite.read(name), f"Retained input/program data changed: {name}")
        descriptor = json.loads(lite.read("assets/descriptor.json"))
        for name in lite_names:
            if name.startswith("assets/") and name != "assets/descriptor.json" and not name.endswith("/"):
                digest = hashlib.sha256(lite.read(name)).hexdigest()
                require(descriptor["files"].get(name.removeprefix("assets/")) == digest,
                        f"Stale lite data descriptor: {name}")
        listed = {"assets/" + p for p, digest in descriptor["files"].items() if digest}
        require(listed == {p for p in lite_names if p.startswith("assets/") and
                           p != "assets/descriptor.json" and not p.endswith("/")},
                "Lite descriptor contains removed or missing files")
        require(descriptor["symlinks"] == json.loads(full.read("assets/descriptor.json"))["symlinks"],
                "Lite changed shared resource symlinks")
        removed = [{"path": p, "apk_bytes": full.getinfo(p).compress_size,
                    "uncompressed_bytes": full.getinfo(p).file_size} for p in sorted(excluded)]
    def sha256(path):
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest()

    full_size, lite_size = full_path.stat().st_size, lite_path.stat().st_size
    require(lite_size < full_size, "Lite APK is not smaller than full APK")
    return {"full_apk_bytes": full_size, "lite_apk_bytes": lite_size,
            "saved_apk_bytes": full_size - lite_size,
            "full_sha256": sha256(full_path),
            "lite_sha256": sha256(lite_path),
            "removed_files": removed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("full", type=Path)
    parser.add_argument("lite", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = compare(args.full, args.lite)
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.write_text(output)
    print(output)


if __name__ == "__main__":
    main()
