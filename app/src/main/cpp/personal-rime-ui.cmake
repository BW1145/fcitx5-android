# Extend the pinned upstream engine from generated copies, keeping upstream clean.
set(PERSONAL_RIME_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../plugin/rime/src/main/cpp/fcitx5-rime/src")
file(READ "${PERSONAL_RIME_SOURCE}/rimeengine.cpp" engine)
set(subconfig "    if (path == \"deploy\") {")
string(FIND "${engine}" "${subconfig}" position)
if(position EQUAL -1)
    message(FATAL_ERROR "The pinned Rime subconfig entry has changed")
endif()
string(REPLACE "${subconfig}" [=[
    if (path.rfind("personal/", 0) == 0) {
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
    if (path == "deploy") {
]=] engine "${engine}")
string(PREPEND engine "#include \"personal-rime-dictionary.h\"\n")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/personal-rime-engine.cpp" "${engine}")
set_source_files_properties("${PERSONAL_RIME_SOURCE}/rimeengine.cpp" TARGET_DIRECTORY rime PROPERTIES HEADER_FILE_ONLY TRUE)
target_sources(rime PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/personal-rime-engine.cpp")

file(READ "${PERSONAL_RIME_SOURCE}/rimestate.cpp" state)
set(session_line "    auto session = this->session();")
string(FIND "${state}" "${session_line}" position)
if(position EQUAL -1)
    message(FATAL_ERROR "The pinned Rime session entry has changed")
endif()
string(REPLACE "${session_line}" [=[
    auto session = this->session();
    if (session) engine_->api()->set_option(session, "_no_learning",
        ic_.capabilityFlags().test(CapabilityFlag::Sensitive) ||
        ic_.capabilityFlags().test(CapabilityFlag::Password));
]=] state "${state}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/personal-rime-state.cpp" "${state}")
set_source_files_properties("${PERSONAL_RIME_SOURCE}/rimestate.cpp" TARGET_DIRECTORY rime PROPERTIES HEADER_FILE_ONLY TRUE)
target_sources(rime PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/personal-rime-state.cpp")
target_include_directories(rime PRIVATE "${PERSONAL_RIME_SOURCE}" "${CMAKE_CURRENT_SOURCE_DIR}")
