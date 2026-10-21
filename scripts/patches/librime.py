from .common import replace

def apply(source):
    rime = source
    # Retain upstream Android option persistence for menu toggles.
    replace(rime / "src/rime/engine.cc", "  an<Switcher> switcher_;", "")
    replace(rime / "src/rime/engine.h", "class Context;", "class Context;\nclass Switcher;")
    replace(rime / "src/rime/engine.h", "  RIME_DLL static Engine* Create();", "  RIME_DLL static Engine* Create();\n  an<Switcher> switcher_;")
    replace(rime / "src/rime/service.h", "  the<Engine> engine_;", "")
    replace(rime / "src/rime/service.h", " private:\n  void OnCommit", "  the<Engine> engine_;\n\n private:\n  void OnCommit")
    replace(rime / "src/rime_api_impl.h", "#include <rime/switches.h>", "#include <rime/switches.h>\n#include <rime/engine.h>\n#include <rime/switcher.h>")
    replace(rime / "src/rime_api_impl.h", "  ctx->set_option(option, !!value);", """  ctx->set_option(option, !!value);
  // Rebuild zero-input predictions immediately when their option changes.
  if (std::string(option) == "prediction" && ctx->input().empty()) {
    ctx->Clear();
  }
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
    replace(rime / "src/rime/gear/poet.cc",
            "inline static Grammar* create_grammar(Config* config) {",
            "inline static Grammar* create_grammar(Config* config) {\n  if (!config) return nullptr;")
