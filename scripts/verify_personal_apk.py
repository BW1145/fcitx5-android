"""Verify the single-APK offline engine and bundled dictionaries."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

apk = Path(sys.argv[1])
abi = sys.argv[2]
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    required = {
        f"lib/{abi}/librime.so",
        "assets/usr/share/fcitx5/addon/rime.conf",
        "assets/usr/share/fcitx5/inputmethod/rime.conf",
        "assets/usr/share/rime-data/default.yaml",
        "assets/usr/share/rime-data/rime_ice.schema.yaml",
        "assets/usr/share/rime-data/rime_ice.dict.yaml",
        "assets/usr/share/rime-data/lua/date_translator.lua",
        "assets/usr/share/rime-data/rime-ice.LICENSE",
        "assets/personal-defaults/config/profile",
        "assets/personal-defaults/data/rime/default.custom.yaml",
    }
    assert required <= names, f"Missing APK entries: {required - names}"
    assert any(n.startswith("assets/usr/share/rime-data/cn_dicts/") for n in names)
    assert any(n.startswith("assets/usr/share/rime-data/en_dicts/") for n in names)
    descriptor = json.loads(archive.read("assets/descriptor.json"))
    assert descriptor["symlinks"]["usr/share/rime-data/opencc"] == "usr/share/opencc"
    for name in required:
        if name.startswith("assets/"):
            digest = hashlib.sha256(archive.read(name)).hexdigest()
            assert descriptor["files"][name.removeprefix("assets/")] == digest, name
    print(f"Verified bundled Rime, dictionaries, Lua, and asset checksums: {apk.name}")
