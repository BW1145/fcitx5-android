"""Prepare pinned Rime sources and offline data for the personal APK."""
import hashlib
import io
import json
import tarfile
import shutil
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build/personal-rime"
LOCK = json.loads((ROOT / "scripts/personal-rime-dependencies.json").read_text())

def download(url, digest):
    data = urllib.request.urlopen(url, timeout=120).read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != digest:
        raise ValueError(f"Checksum mismatch for {url}: {actual}")
    return data

def replace(path, old, new):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise ValueError(f"Source changed: {path.name}")
    path.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

def patch_sources():
    rime = WORK / "librime"
    # Android uses the existing static Lua build.
    lua = WORK / "librime-lua/CMakeLists.txt"
    text = lua.read_text()
    start = text.index("  find_package(PkgConfig)")
    end = text.index("\nelse()", start)
    lua.write_text(text[:start] + "  find_package(Lua REQUIRED CONFIG)\n  set(LUA_TARGET ${LUA_LIBRARY})\n  include_directories(${LUA_INCLUDE_DIR})" + text[end:])
    # Retain upstream Android option persistence for menu toggles.
    replace(rime / "src/rime/engine.cc", "  an<Switcher> switcher_;", "")
    replace(rime / "src/rime/engine.h", "class Context;", "class Context;\nclass Switcher;")
    replace(rime / "src/rime/engine.h", "  RIME_DLL static Engine* Create();", "  RIME_DLL static Engine* Create();\n  an<Switcher> switcher_;")
    replace(rime / "src/rime/service.h", "  the<Engine> engine_;", "")
    replace(rime / "src/rime/service.h", " private:\n  void OnCommit", "  the<Engine> engine_;\n\n private:\n  void OnCommit")
    replace(rime / "src/rime_api_impl.h", "#include <rime/switches.h>", "#include <rime/switches.h>\n#include <rime/engine.h>\n#include <rime/switcher.h>")
    replace(rime / "src/rime_api_impl.h", "  ctx->set_option(option, !!value);", """  ctx->set_option(option, !!value);
  auto switcher = session->engine_->switcher_;
  if (switcher && switcher->IsAutoSave(option)) {
    if (Config* user_config = switcher->user_config()) {
      user_config->SetBool("var/option/" + std::string(option), value);
    }
  }""")
    # Keep the chosen enhancement state across focus changes and app restarts.
    replace(rime / "src/rime/engine.cc", "    if (option.reset_value >= 0) {", """    if (option.option_name == "octagram" || option.option_name == "prediction") {
      bool saved_value = false;
      if (switcher_ && switcher_->user_config() &&
          switcher_->user_config()->GetBool("var/option/" + option.option_name, &saved_value)) {
        context_->set_option(option.option_name, saved_value);
        return Switches::kContinue;
      }
    }
    if (option.reset_value >= 0) {""")
    # One menu option selects a model-backed or ordinary sentence composer.
    header = rime / "src/rime/gear/script_translator.h"
    replace(header, "  the<Poet> poet_;", "  the<Poet> poet_;\n  the<Poet> plain_poet_;")
    translator = rime / "src/rime/gear/script_translator.cc"
    replace(translator, "    poet_.reset(new Poet(language(), config));", "    poet_.reset(new Poet(language(), config));\n    plain_poet_.reset(new Poet(language(), nullptr));")
    replace(translator, "  auto result = New<ScriptTranslation>(", "  auto* active_poet = engine_->context()->get_option(\"octagram\") ? poet_.get() : plain_poet_.get();\n  auto result = New<ScriptTranslation>(")
    replace(translator, "this, corrector_.get(), poet_.get(), input", "this, corrector_.get(), active_poet, input")
    replace(translator, "return poet_->ContextualWeighted", "return active_poet->ContextualWeighted")
    predict = WORK / "librime-predict/src/predict_engine.cc"
    replace(predict, '#include "predict_db.h"', '#include "predict_db.h"\n#include <opencc/Config.hpp>\n#include <opencc/Converter.hpp>')
    replace(predict, "  if (const auto* candidates = db_->Lookup(context_query)) {", """  const auto* candidates = db_->Lookup(context_query);
  if (!candidates) {
    static auto traditional = opencc::Config().NewFromFile(
        (Service::instance().deployer().shared_data_dir / "opencc" / "s2t.json").u8string());
    candidates = db_->Lookup(traditional->Convert(context_query));
  }
  if (candidates) {""")
    for name in ("lua", "octagram", "predict"):
        target = rime / "plugins" / name
        shutil.copytree(WORK / ("librime-" + name), target)

if __name__ == "__main__":
    WORK.mkdir(parents=True, exist_ok=True)
    for source in LOCK["sources"]:
        directory = WORK / source["repo"].split("/")[-1]
        if directory.exists():
            raise RuntimeError("Run from a clean build directory")
        data = download(f'https://codeload.github.com/{source["repo"]}/tar.gz/{source["ref"]}', source["sha256"])
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            name = archive.getmembers()[0].name.split("/")[0]
            archive.extractall(WORK, filter="data")
        (WORK / name).rename(directory)
    patch_sources()
    data_dir = ROOT / "app/src/main/assets/personal-defaults/data/rime"
    for item in LOCK["data"]:
        (data_dir / item["name"]).write_bytes(download(item["url"], item["sha256"]))
    # Overlay defaults on the pinned schema before installing it into the APK.
    schema = (ROOT / "third_party/rime-ice/rime_ice.schema.yaml").read_text(encoding="utf-8")
    (data_dir / "rime_ice.schema.yaml").write_text(schema + "\n__patch:\n  - personal_ice:/patch\n  - rime_ice.custom:/patch?\n", encoding="utf-8")
