"""Record actual binary hashes, compiler versions and license-source provenance.

The inventory records evidence; it does not relabel a mixed-license APK as LGPL.
Only generated CI artifacts and repository/dependency license files are read.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


def capture(command):
    try:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=60)
        return {"command": command, "exit_code": result.returncode, "output": result.stdout}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"command": command, "error": str(error)}


def record(root, artifacts):
    root = root.resolve(); artifacts = artifacts.resolve()
    artifacts.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get("ANDROID_HOME", "/missing-android-sdk"))
    commands = [
        ["java", "-version"], [str(root / "gradlew"), "--version"],
        [str(sdk / "cmdline-tools/latest/bin/sdkmanager"), "--list_installed"],
        [str(sdk / "cmake/3.31.6/bin/cmake"), "--version"],
        [str(sdk / "ndk/28.0.13004108/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"), "--version"],
        [str(sdk / "emulator/emulator"), "-version"], ["adb", "version"],
        ["git", "submodule", "status", "--recursive"],
    ]
    files = []
    for path in sorted(artifacts.glob("*/*.apk")):
        files.append({"path": path.relative_to(artifacts).as_posix(), "bytes": path.stat().st_size,
                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    licenses = []
    # Source checkout, pinned git dependencies, and verified downloaded Rime source.
    # Exclude SDK/cache/build outputs; downloaded source tree is explicitly included.
    for directory in (root, root / "build/personal-rime/librime",
                      root / "build/personal-rime/librime-lua",
                      root / "build/personal-rime/librime-octagram",
                      root / "build/personal-rime/librime-predict"):
        if not directory.exists(): continue
        for current, folders, names in os.walk(directory):
            folders[:] = [name for name in folders if name not in
                          (".git", ".gradle", ".cxx", "build", "node_modules")]
            for name in names:
                if name.upper().split(".")[0] not in ("LICENSE", "LICENCE", "COPYING", "NOTICE"):
                    continue
                path = Path(current) / name
                if not path.is_file() or path.is_symlink(): continue
                relative = path.relative_to(root).as_posix()
                data = path.read_bytes()
                licenses.append({"source_path": relative, "bytes": len(data),
                                 "sha256": hashlib.sha256(data).hexdigest()})
                destination = artifacts / "license-sources" / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
    metadata = {"schema": 1, "artifact_purpose": "Disposable Android emulator diagnostics; debug/test APKs, not a signed personal release",
                "license_scope": "Repository LICENSE and dependency license texts retain their original terms. APKs contain multiple components; this evidence inventory is not a complete legal review or a new license grant.",
                "run_id": os.environ.get("GITHUB_RUN_ID"), "candidate_sha": os.environ.get("GITHUB_SHA"),
                "source_repository": os.environ.get("GITHUB_REPOSITORY"),
                "source_commits": {path.stem: path.read_text().strip() for path in artifacts.glob("*-commit.txt")},
                "apks": files, "license_sources": licenses,
                "toolchain": [capture(command) for command in commands]}
    for name in ("scripts/personal-rime-dependencies.json", "gradle/libs.versions.toml",
                 "gradle/wrapper/gradle-wrapper.properties"):
        destination = artifacts / "source-locks" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / name, destination)
    (artifacts / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (artifacts / "SHA256SUMS").write_text("".join(f'{row["sha256"]}  {row["path"]}\n' for row in files))


if __name__ == "__main__": record(Path.cwd(), Path(sys.argv[1]))
