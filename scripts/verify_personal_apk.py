"""Verify the delivered APK, bundled data, and matching external plugins."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sdk_tool(name):
    found = shutil.which(name)
    if found:
        return found
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    relative = ("cmdline-tools/latest/bin/" if name == "apkanalyzer"
                else "build-tools/36.1.0/")
    tool = sdk / relative / name
    require(tool.is_file(), f"Android SDK tool is missing: {tool}")
    return str(tool)


def read_manifest(apk):
    result = subprocess.run(
        [sdk_tool("apkanalyzer"), "manifest", "print", str(apk)],
        check=True, capture_output=True, text=True,
    )
    return ET.fromstring(result.stdout)


def names(manifest, path):
    return {node.get(ANDROID + "name") for node in manifest.findall(path)}


def verify_manifest(apk, verifier, *args):
    manifest = read_manifest(apk)
    try:
        return verifier(manifest, *args)
    except ValueError:
        print(f"Final merged manifest from {apk}:", file=sys.stderr)
        ET.indent(manifest)
        print(ET.tostring(manifest, encoding="unicode"), file=sys.stderr)
        raise


def verify_version(manifest, version_code, version_name):
    code = int(manifest.get(ANDROID + "versionCode", "0"), 0)
    name = manifest.get(ANDROID + "versionName")
    if version_code is not None:
        require(code == version_code, f"Final APK versionCode is {code}, expected {version_code}")
    if version_name is not None:
        require(name == version_name, f"Final APK versionName is {name}, expected {version_name}")
    return code, name


def verify_app_manifest(manifest, application_id, version_code, version_name):
    require(manifest.get("package") == application_id, "Unexpected application ID")
    code, name = verify_version(manifest, version_code, version_name)
    require("android.permission.INTERNET" in names(manifest, "uses-permission"),
            "Missing permission for the user-configured AI service")
    require("android.permission.RECORD_AUDIO" in names(manifest, "uses-permission"),
            "Missing permission for the speech service test")
    require(application_id + ".plugin.MANIFEST" in names(manifest, "queries/intent/action"),
            "The IME does not query its own plugin discovery action")
    for permission in ("IPC", "PLUGIN"):
        node = next((item for item in manifest.findall("permission")
                     if item.get(ANDROID + "name") == application_id + ".permission." + permission), None)
        level = node.get(ANDROID + "protectionLevel", "") if node is not None else ""
        try:
            signature_only = int(level, 0) == 2
        except ValueError:
            signature_only = level == "signature"
        require(node is not None and signature_only,
                f"Missing signature permission: {permission}")
    return code, name


def verify_plugin_manifest(manifest, application_id, version_code, version_name):
    debug = application_id.endswith(".debug")
    base_id = application_id.removesuffix(".debug") if debug else application_id
    suffix = ".debug" if debug else ""
    package = manifest.get("package", "")
    pattern = re.escape(base_id) + r"\.plugin\.[a-z0-9_]+" + re.escape(suffix)
    require(re.fullmatch(pattern, package), f"Plugin package is not isolated with its IME: {package}")
    verify_version(manifest, version_code, version_name)
    require(application_id in names(manifest, "queries/package"),
            f"{package} cannot query its matching IME")
    discovery = {name for name in names(manifest, "application/activity/intent-filter/action")
                 if name and name.endswith(".plugin.MANIFEST")}
    require(discovery == {application_id + ".plugin.MANIFEST"},
            f"{package} advertises a different IME: {discovery}")
    metadata = names(manifest, "application/meta-data")
    require(application_id + ".plugin.METADATA" in metadata, f"{package} lacks its plugin metadata")
    require(application_id + ".plugin.ABOUT_LIBRARIES" in metadata, f"{package} lacks its licenses")
    for service in manifest.findall("application/service"):
        actions = names(service, "intent-filter/action")
        plugin_actions = {name for name in actions if name and name.endswith(".plugin.SERVICE")}
        if plugin_actions:
            require(plugin_actions == {application_id + ".plugin.SERVICE"},
                    f"{package} advertises a different service action")
            require(service.get(ANDROID + "permission") == application_id + ".permission.PLUGIN",
                    f"{package} uses a different service permission")
            require(application_id + ".permission.IPC" in names(manifest, "uses-permission"),
                    f"{package} lacks the matching IPC permission")
    if package == base_id + ".plugin.clipboard_filter" + suffix:
        require(application_id + ".plugin.SERVICE" in names(manifest, "application/service/intent-filter/action"),
                "The clipboard filter service is missing")
    return package


def certificate_digests(report):
    digests = set(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", report))
    require(digests, "No signer certificate SHA-256 digest in apksigner output")
    return {digest.lower() for digest in digests}


def verify_signature(apk):
    result = subprocess.run(
        [sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    )
    return certificate_digests(result.stdout), result.stdout


def verify_assets(apk, abi):
    with zipfile.ZipFile(apk) as archive:
        entries = set(archive.namelist())
        abis = {name.split("/")[1] for name in entries if name.startswith("lib/") and name.endswith(".so")}
        require(abis == {abi}, f"Unexpected native ABIs: {abis}")
        required = {
            f"lib/{abi}/librime.so",
            f"lib/{abi}/libanthy.so",
            "assets/usr/share/fcitx5/addon/anthy.conf",
            "assets/usr/share/fcitx5/inputmethod/anthy.conf",
            "assets/usr/share/anthy/anthy.dic",
            "assets/usr/share/anthy/anthy-unicode.conf",
            "assets/usr/share/fcitx5/addon/rime.conf",
            "assets/usr/share/fcitx5/inputmethod/rime.conf",
            "assets/usr/share/rime-data/default.yaml",
            "assets/usr/share/rime-data/rime_ice.schema.yaml",
            "assets/usr/share/rime-data/rime_ice.dict.yaml",
            "assets/usr/share/rime-data/lua/date_translator.lua",
            "assets/usr/share/rime-data/rime-ice.LICENSE",
            "assets/personal-defaults/config/profile",
            "assets/personal-defaults/data/rime/default.custom.yaml",
            "assets/personal-defaults/data/rime/personal_ice.yaml",
            "assets/personal-defaults/data/rime/zh-hans-t-essay-bgw-compact.gram",
            "assets/personal-defaults/data/rime/predict.db",
            "assets/usr/share/rime-data/personal_ice.yaml",
            "assets/usr/share/rime-data/lua/predict_simplify.lua",
            "assets/usr/share/opencc/s2t.json",
            "assets/usr/share/opencc/t2s.json",
            "assets/clearurls-rules.json",
        }
        require(required <= entries, f"Missing APK entries: {required - entries}")
        for dictionary in ("cn_dicts", "en_dicts"):
            require(any(name.startswith(f"assets/usr/share/rime-data/{dictionary}/") for name in entries),
                    f"Missing {dictionary}")
        lock = json.loads((ROOT / "scripts/personal-rime-dependencies.json").read_text())
        for item in lock["data"]:
            digest = hashlib.sha256(archive.read("assets/personal-defaults/data/rime/" + item["name"])).hexdigest()
            require(digest == item["sha256"], f"Data checksum mismatch: {item['name']}")
        descriptor = json.loads(archive.read("assets/descriptor.json"))
        require(descriptor["symlinks"]["usr/share/rime-data/opencc"] == "usr/share/opencc",
                "Invalid OpenCC symlink")
        for name in required:
            if name.startswith("assets/"):
                digest = hashlib.sha256(archive.read(name)).hexdigest()
                require(descriptor["files"][name.removeprefix("assets/")] == digest,
                        f"Asset descriptor checksum mismatch: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("abi")
    parser.add_argument("--application-id", default="org.fcitx.fcitx5.android.bw1145")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    parser.add_argument("--plugin-apk", action="append", type=Path, default=[])
    parser.add_argument("--previous-signature", type=Path)
    parser.add_argument("--signature-report", type=Path)
    args = parser.parse_args()
    verify_assets(args.apk, args.abi)
    code, name = verify_manifest(args.apk, verify_app_manifest,
                                args.application_id, args.version_code, args.version_name)
    signers, report = verify_signature(args.apk)
    if args.previous_signature:
        require(signers == certificate_digests(args.previous_signature.read_text()),
                "The release signing identity differs from the installed personal version")
    if args.signature_report:
        args.signature_report.write_text(report)
    for plugin in args.plugin_apk:
        package = verify_manifest(plugin, verify_plugin_manifest,
                                  args.application_id, args.version_code, args.version_name)
        plugin_signers, _ = verify_signature(plugin)
        require(plugin_signers == signers, f"Plugin signer differs from the IME: {package}")
        print(f"Verified plugin package, discovery, permissions and signer: {package}")
    print(f"Verified {args.application_id} {name} (versionCode {code}), {args.abi}, signature and offline data")


if __name__ == "__main__":
    main()

