from .common import replace

def apply(source):
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
