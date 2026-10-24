"""Keep Rime's candidate state whole while the pinyin row edits in the middle."""
from .common import replace

BACKEND = r'''
struct PersonalEditBackend {
    rime_api_t *api;
    RimeSessionId id;
    uint64_t sessionId() const { return id; }
    std::string input() const {
        const char *value = api->get_input(id);
        return value ? value : "";
    }
    size_t caret() const { return api->get_caret_pos(id); }
    void setCaret(size_t position) { api->set_caret_pos(id, position); }
    void stepCharacter(bool forward) {
        api->process_key(id, forward ? FcitxKey_KP_Right : FcitxKey_KP_Left, 0);
    }
    std::optional<personal::EditPreedit> capture() {
        RIME_STRUCT(RimeContext, context);
        if (!api->get_context(id, &context)) return std::nullopt;
        const auto &c = context.composition;
        std::optional<personal::EditPreedit> result;
        if (c.preedit && c.cursor_pos >= 0 && c.cursor_pos <= c.length &&
            c.sel_start >= 0 && c.sel_start <= c.sel_end && c.sel_end <= c.length) {
            result = personal::EditPreedit{c.preedit, c.cursor_pos, c.sel_start, c.sel_end};
        }
        api->free_context(&context);
        return result;
    }
};

bool personalEditingKey(const KeyEvent &event) {
    if (event.isRelease()) return false;
    const auto states = event.rawKey().states();
    if (states.testAny(KeyStates{KeyState::Alt, KeyState::Super})) return false;
    const auto key = event.rawKey().sym();
    if (key == FcitxKey_Left || key == FcitxKey_Right ||
        key == FcitxKey_KP_Left || key == FcitxKey_KP_Right ||
        key == FcitxKey_End || key == FcitxKey_KP_End ||
        key == FcitxKey_BackSpace) return true;
    if (states.test(KeyState::Ctrl)) return false; // Ctrl+Delete deletes a candidate.
    return key == FcitxKey_Delete || key == FcitxKey_KP_Delete || key == FcitxKey_apostrophe ||
           (key >= FcitxKey_a && key <= FcitxKey_z) ||
           (key >= FcitxKey_A && key <= FcitxKey_Z);
}

bool personalMenuNavigationKey(const KeyEvent &event) {
    if (event.isRelease()) return false;
    if (event.rawKey().states().testAny(KeyStates{KeyState::Ctrl, KeyState::Alt, KeyState::Super})) return false;
    switch (event.rawKey().sym()) {
    case FcitxKey_Up: case FcitxKey_Down: case FcitxKey_KP_Up: case FcitxKey_KP_Down:
    case FcitxKey_Page_Up: case FcitxKey_Page_Down:
    case FcitxKey_KP_Page_Up: case FcitxKey_KP_Page_Down:
    case FcitxKey_Home: case FcitxKey_KP_Home:
        return true;
    default: return false;
    }
}

bool personalModifierOnlyKey(const KeyEvent &event) {
    switch (event.rawKey().sym()) {
    case FcitxKey_Shift_L: case FcitxKey_Shift_R:
    case FcitxKey_Control_L: case FcitxKey_Control_R:
    case FcitxKey_Alt_L: case FcitxKey_Alt_R:
    case FcitxKey_Meta_L: case FcitxKey_Meta_R:
    case FcitxKey_Super_L: case FcitxKey_Super_R:
    case FcitxKey_Hyper_L: case FcitxKey_Hyper_R:
    case FcitxKey_Caps_Lock: case FcitxKey_Shift_Lock:
        return true;
    default: return false;
    }
}

enum class PersonalEditKeyAction { Edit, Keep, Clear };
PersonalEditKeyAction personalEditKeyAction(const KeyEvent &event) {
    if (event.isRelease() || personalModifierOnlyKey(event) || personalMenuNavigationKey(event)) {
        return PersonalEditKeyAction::Keep;
    }
    return personalEditingKey(event) ? PersonalEditKeyAction::Edit : PersonalEditKeyAction::Clear;
}

bool personalCaretNavigationKey(const KeyEvent &event) {
    switch (event.rawKey().sym()) {
    case FcitxKey_Left: case FcitxKey_Right: case FcitxKey_KP_Left: case FcitxKey_KP_Right:
    case FcitxKey_End: case FcitxKey_KP_End: return true;
    default: return false;
    }
}

bool personalNeedsEditFinish(const KeyEvent &event, bool active, bool pending) {
    return personalEditingKey(event) && (active || pending || personalCaretNavigationKey(event));
}
'''

METHODS = r'''
bool RimeState::personalEditEligible(const std::string &input) {
    if (!personal::plainPinyin(input)) return false;
    const auto id = session(false);
    if (!id) return false;
    // Read status directly: getStatus()/currentSchema() synchronize an option
    // in the privacy adapter and therefore can recompose the live menu.
    auto *api = engine_->api();
    RIME_STRUCT(RimeStatus, status);
    if (!api->get_status(id, &status)) return false;
    const bool eligible = !status.is_ascii_mode && !status.is_disabled &&
        status.schema_id && std::string(status.schema_id) == "rime_ice";
    api->free_status(&status);
    return eligible;
}

void RimeState::personalFinishEdit() {
    PersonalEditBackend backend{engine_->api(), session(false)};
    if (!backend.id) { personalEdit_.clear(); personalEditPending_ = false; return; }
    personalEdit_.finish(backend, [this](const auto &input) { return personalEditEligible(input); });
    // Track an unsupported legacy middle caret without probing every ordinary
    // append. A later deletion may turn that input into eligible plain spelling.
    personalEditPending_ = !personalEdit_.active() && backend.caret() < backend.input().size();
}

bool RimeState::personalMoveCursor(int position) {
    auto *api = engine_->api();
    const auto id = session(false);
    if (!id || api->is_maintenance_mode()) return false;
    PersonalEditBackend backend{api, id};
    const bool moved = personalEdit_.move(backend, static_cast<size_t>(std::max(position, 0)),
        [this](const auto &input) { return personalEditEligible(input); });
    if (!moved && personalEdit_.active()) {
        // The legacy path must see its real editing caret, not the hidden end
        // caret. In particular, never probe through a selected prefix here.
        if (personalEditEligible(backend.input())) personalEdit_.resume(backend);
        personalEdit_.clear();
    }
    personalEditPending_ = !personalEdit_.active() && backend.caret() < backend.input().size();
    updateUI(&ic_);
    return moved;
}
'''

def apply(source):
    header = source / "rimestate.h"
    replace(header, '#include "rimesession.h"', '#include "rimesession.h"\n#include "personal-rime-edit.h"')
    replace(header, '    void clear();', '    void clear();\n    bool personalMoveCursor(int position);')
    replace(header, 'private:\n', '''private:
    personal::EditCaret personalEdit_;
    bool personalEditPending_ = false;
    bool personalEditEligible(const std::string &input);
    void personalFinishEdit();
''')
    engine = source / "rimeengine.h"
    replace(engine, '    auto &factory() { return factory_; }', '''    auto &factory() { return factory_; }
    bool personalMoveCursor(InputContext *ic, int position) {
        auto *s = state(ic);
        return s && s->personalMoveCursor(position);
    }
    FCITX_ADDON_EXPORT_FUNCTION(RimeEngine, personalMoveCursor);''')
    state = source / "rimestate.cpp"
    replace(state, 'namespace {} // namespace', 'namespace {\n' + BACKEND + '\n} // namespace\n' + METHODS)
    replace(state, 'void RimeState::clear() {', 'void RimeState::clear() {\n    personalEdit_.clear();\n    personalEditPending_ = false;')
    replace(state, 'void RimeState::release() { session_.reset(); }',
            'void RimeState::release() { personalEdit_.clear(); personalEditPending_ = false; session_.reset(); }')
    for method in ('toggleLatinMode()', 'setLatinMode(bool latin)', 'selectSchema(const std::string &schema)'):
        replace(state, 'void RimeState::' + method + ' {',
                'void RimeState::' + method + ' {\n    personalEdit_.clear();\n    personalEditPending_ = false;')
    replace(state, '    if (!composeResult.empty()) {', '''    const bool personalMenuKey = personalMenuNavigationKey(event);
    const auto personalKeyAction = personalEditKeyAction(event);
    const bool personalFinishNeeded = personalNeedsEditFinish(event, personalEdit_.active(), personalEditPending_);
    if (!event.isRelease() && personalEdit_.active()) {
        PersonalEditBackend backend{api, session};
        if (!personalEditEligible(backend.input()) || !personalEdit_.valid(backend)) {
            personalEdit_.clear();
        } else if (personalKeyAction == PersonalEditKeyAction::Edit) {
            personalEdit_.resume(backend);
        } else if (personalKeyAction == PersonalEditKeyAction::Clear) {
            // Includes space, digits/keypad digits, Return, punctuation, and
            // custom commit commands. They operate on the displayed full menu.
            personalEdit_.clear();
        }
    }
    if (!composeResult.empty()) {''')
    replace(state, '    if (api->get_commit(session, &commit)) {',
            '    if (api->get_commit(session, &commit)) {\n        personalEdit_.clear();\n        personalEditPending_ = false;')
    replace(state, '    updateUI(ic);\n    if (!event.isRelease()', '''    if (personalFinishNeeded) personalFinishEdit();
    if (!event.isRelease() && personalMenuKey) {
        PersonalEditBackend backend{api, session};
        // Home may be consumed by Selector, or may actually move the raw
        // caret in Navigator. Only the latter starts/changes middle editing.
        if (backend.caret() < backend.input().size()) personalFinishEdit();
    }
    updateUI(ic);
    if (!event.isRelease()''')
    replace(state, '''void RimeState::selectCandidate(InputContext *inputContext, int idx,
                                bool global) {''', '''void RimeState::selectCandidate(InputContext *inputContext, int idx,
                                bool global) {
    // Preferred (hidden/reordered) space/digits bypass keyEvent and arrive here.
    // A partial selection can change segments without changing the raw input.
    personalEdit_.clear();
    personalEditPending_ = false;''')
    replace(state, 'void RimeState::updatePreedit(InputContext *ic, const RimeContext &context) {', '''void RimeState::updatePreedit(InputContext *ic, const RimeContext &context) {
    RimeContext visibleContext = context;
    if (personalEdit_.active()) {
        PersonalEditBackend backend{engine_->api(), session(false)};
        if (!backend.id || !personalEdit_.valid(backend) || !personalEditEligible(backend.input())) {
            personalEdit_.clear();
        } else {
            const auto &view = personalEdit_.view();
            visibleContext.composition.preedit = const_cast<char *>(view.text.c_str());
            visibleContext.composition.length = view.text.size();
            visibleContext.composition.cursor_pos = view.cursor;
            visibleContext.composition.sel_start = view.selectionStart;
            visibleContext.composition.sel_end = view.selectionEnd;
        }
    }
    // Only the pinyin presentation is substituted. In CommitPreview mode the
    // client still receives the full, live context.commit_text_preview.''')
    replace(state, 'preeditFromRimeContext(\n            context,', 'preeditFromRimeContext(\n            visibleContext,')
