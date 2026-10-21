"""Prepare pinned sources, generated adaptations and verified offline data."""
import hashlib
import io
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path
from apply_personal_patches import apply

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build/personal-rime"
LOCK = json.loads((ROOT / "scripts/personal-rime-dependencies.json").read_text(encoding="utf-8"))


def verified_file(path, url, digest):
    if path.exists():
        cached = path.read_bytes()
        if hashlib.sha256(cached).hexdigest() == digest:
            return cached
    data = urllib.request.urlopen(url, timeout=120).read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"Checksum mismatch for {url}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return data


def prepare():
    WORK.mkdir(parents=True, exist_ok=True)
    for entry in LOCK["sources"]:
        name = entry["repo"].split("/")[-1]
        source = WORK / "sources" / entry["sha256"] / name
        if not source.exists():
            data = verified_file(WORK / "downloads" / f'{entry["sha256"]}.tar.gz',
                f'https://codeload.github.com/{entry["repo"]}/tar.gz/{entry["ref"]}', entry["sha256"])
            parent = source.parent
            parent.mkdir(parents=True, exist_ok=True)
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                folder = archive.getmembers()[0].name.split("/")[0]
                archive.extractall(parent, filter="data")
            (parent / folder).rename(source)
        apply(name, source, WORK / name)
    rime = WORK / "librime"
    for name in ("lua", "octagram", "predict"):
        source = WORK / ("librime-" + name)
        target = rime / "plugins" / name
        marker = ".personal-patches.json"
        if not (target / marker).exists() or (target / marker).read_bytes() != (source / marker).read_bytes():
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(source, target)
    data_dir = ROOT / "app/src/main/assets/personal-defaults/data/rime"
    for entry in LOCK["data"]:
        verified_file(data_dir / entry["name"], entry["url"], entry["sha256"])
    schema = (ROOT / "third_party/rime-ice/rime_ice.schema.yaml").read_text(encoding="utf-8")
    (data_dir / "rime_ice.schema.yaml").write_text(schema +
        "\n__patch:\n  - personal_ice:/patch\n  - rime_ice.custom:/patch?\n", encoding="utf-8")


if __name__ == "__main__":
    prepare()
