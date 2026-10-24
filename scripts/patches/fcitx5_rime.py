from .common import replace
from .rime_edit import apply as apply_edit

def apply(source):
    replace(source / "rimeengine.h", '#include "rimesession.h"',
            '#include "rimesession.h"\n#include "personal-rime-public.h"')
    replace(source / "rimeengine.h", '    auto &factory() { return factory_; }', '''    auto &factory() { return factory_; }
    std::vector<std::string> personalContext(InputContext *ic) {
        auto *s = state(ic);
        if (!s) return {};
        const auto session = s->session(false);
        if (!session) return {};
        // Read directly: currentSchema()/getStatus() also synchronizes
        // _no_learning in our privacy patch, which can recompute composition.
        RIME_STRUCT(RimeStatus, status);
        if (!api_->get_status(session, &status)) return {};
        const std::string schema = status.schema_id ? status.schema_id : "";
        api_->free_status(&status);
        const char *input = api_->get_input(session);
        return {schema, input ? input : ""};
    }
    FCITX_ADDON_EXPORT_FUNCTION(RimeEngine, personalContext);
    std::vector<std::string> personalSchemas(const std::string &dictionary) {
        std::vector<std::string> result;
        RimeSchemaList schemas{};
        if (!api_->get_schema_list(&schemas)) return result;
        for (size_t i = 0; i < schemas.size; ++i) {
            RimeConfig config{};
            const char *id = schemas.list[i].schema_id;
            if (!api_->schema_open(id, &config)) continue;
            char value[1024]{};
            bool found = api_->config_get_string(&config, "translator/user_dict", value, sizeof(value));
            if (!found || !value[0]) api_->config_get_string(&config, "translator/dictionary", value, sizeof(value));
            if (dictionary == value) result.emplace_back(id);
            api_->config_close(&config);
        }
        api_->free_schema_list(&schemas);
        return result;
    }
    FCITX_ADDON_EXPORT_FUNCTION(RimeEngine, personalSchemas);''')
    # Publish recomputed candidates after a menu action, just as after a key event.
    replace(source / "rimeaction.cpp",
            "    api->set_option(session, option_.c_str(), !oldValue);",
            '''    api->set_option(session, option_.c_str(), !oldValue);
    state->updateUI(ic);''')
    engine=source / "rimeengine.cpp"
    replace(engine, '    if (path == "deploy") {', '''    if (path.rfind("personal/", 0) == 0) {
        api_->join_maintenance_thread();
        releaseAllSession(true);
        api_->cleanup_all_sessions();
        instance_->inputContextManager().foreach([&](InputContext *ic) {
            const auto *entry = instance_->inputMethodEntry(ic);
            if (entry && entry->uniqueName() == "rime") {
                ic->inputPanel().reset();
                ic->updatePreedit();
                ic->updateUserInterface(UserInterfaceComponent::InputPanel);
            }
            return true;
        });
        personal::operate(api_, path.substr(9),
            StandardPaths::global().userDirectory(StandardPathsType::PkgData) / "rime");
        return;
    }
    if (path == "deploy") {''')
    engine.write_text('#include "personal-rime-dictionary.h"\n'+engine.read_text(encoding="utf-8"), encoding="utf-8")
    replace(source / "rimestate.cpp", "    auto session = this->session();", '''    auto session = this->session();
    if (session) engine_->api()->set_option(session, "_no_learning",
        ic_.capabilityFlags().test(CapabilityFlag::Sensitive) ||
        ic_.capabilityFlags().test(CapabilityFlag::Password));''')
    apply_edit(source)
