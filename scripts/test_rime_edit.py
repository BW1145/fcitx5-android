#!/usr/bin/env python3
"""Host-only state/routing checks; does not build Android or run real Rime."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

from patches.rime_edit import BACKEND

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--fcitx-headers", type=Path, default=ROOT / "lib/fcitx5/src/main/cpp/fcitx5/src/lib")
args = parser.parse_args()
headers = args.fcitx_headers.resolve()
assert (headers / "fcitx-utils/keysym.h").is_file(), headers

source = r'''
#include <cassert>
#include <iostream>
#include <fcitx-utils/flags.h>
#include <fcitx-utils/keysym.h>
#include "personal-rime-edit.h"
using namespace fcitx;
using KeyStates = Flags<KeyState>;
struct Key { FcitxKeySym symbol; KeyStates modifiers;
    FcitxKeySym sym() const { return symbol; }
    KeyStates states() const { return modifiers; }
};
struct KeyEvent { Key key; bool release = false;
    const Key &rawKey() const { return key; }
    bool isRelease() const { return release; }
};
ROUTING
using fcitx::rime::personal::EditCaret;
using fcitx::rime::personal::EditPreedit;
struct SequenceBackend {
    std::string raw = "niho"; size_t pos = 4; int writes = 0;
    mutable int inputReads = 0, caretReads = 0; int captures = 0;
    uint64_t sessionId() const { return 1; }
    std::string input() const { ++inputReads; return raw; }
    size_t caret() const { ++caretReads; return pos; }
    void setCaret(size_t p) { pos = p; ++writes; }
    void stepCharacter(bool forward) { setCaret(forward ? pos + 1 : pos - 1); }
    std::optional<EditPreedit> capture() { ++captures; return EditPreedit{raw, static_cast<int>(pos), 0, static_cast<int>(pos)}; }
};
int main() {
    for (auto sym : {FcitxKey_a, FcitxKey_Z, FcitxKey_apostrophe,
                    FcitxKey_BackSpace, FcitxKey_Delete, FcitxKey_Left, FcitxKey_KP_Right,
                    FcitxKey_End, FcitxKey_KP_End}) {
        KeyEvent e{{sym, KeyStates{}}};
        assert(personalEditingKey(e));
        e.release = true;
        assert(!personalEditingKey(e) && !personalMenuNavigationKey(e));
    }
    for (auto sym : {FcitxKey_space, FcitxKey_1, FcitxKey_0, FcitxKey_KP_1,
                    FcitxKey_KP_0, FcitxKey_Return, FcitxKey_bracketleft,
                    FcitxKey_bracketright, FcitxKey_Escape}) {
        KeyEvent e{{sym, KeyStates{}}};
        assert(!personalEditingKey(e) && !personalMenuNavigationKey(e));
    }
    for (auto sym : {FcitxKey_Up, FcitxKey_Down, FcitxKey_Page_Up,
                    FcitxKey_Page_Down, FcitxKey_Home}) {
        KeyEvent e{{sym, KeyStates{}}};
        assert(!personalEditingKey(e) && personalMenuNavigationKey(e));
    }
    assert(personalEditingKey({{FcitxKey_BackSpace, KeyStates{KeyState::Ctrl}}}));
    assert(!personalEditingKey({{FcitxKey_Delete, KeyStates{KeyState::Ctrl}}}));
    assert(!personalEditingKey({{FcitxKey_a, KeyStates{KeyState::Ctrl}}}));
    assert(!personalEditingKey({{FcitxKey_a, KeyStates{KeyState::Alt}}}));
    {
        SequenceBackend backend; EditCaret edit;
        auto eligible = [](const auto &) { return true; };
        for (int n = 0; n < 100; ++n) {
            const KeyEvent event{{FcitxKey_a, KeyStates{}}};
            const bool finish = personalNeedsEditFinish(event, edit.active(), false);
            backend.raw += 'a'; backend.pos = backend.raw.size();
            if (finish) edit.finish(backend, eligible);
        }
        assert(backend.inputReads == 0 && backend.caretReads == 0 && backend.captures == 0 && backend.writes == 0);
        assert(personalNeedsEditFinish({{FcitxKey_Left, KeyStates{}}}, false, false));
        assert(personalNeedsEditFinish({{FcitxKey_a, KeyStates{}}}, false, true));
        assert(!personalNeedsEditFinish({{FcitxKey_a, KeyStates{}}, true}, true, true));
    }
    for (auto modifier : {FcitxKey_Shift_L, FcitxKey_Shift_R, FcitxKey_Control_L, FcitxKey_Control_R}) {
        SequenceBackend backend; EditCaret edit;
        auto eligible = [](const auto &s) { return fcitx::rime::personal::plainPinyin(s); };
        assert(edit.move(backend, 2, eligible)); // ni|ho
        auto route = [&](const KeyEvent &event) {
            switch (personalEditKeyAction(event)) {
            case PersonalEditKeyAction::Edit: edit.resume(backend); break;
            case PersonalEditKeyAction::Clear: edit.clear(); break;
            case PersonalEditKeyAction::Keep: break;
            }
        };
        const int writes = backend.writes;
        route({{modifier, KeyStates{}}}); // standalone modifier keydown
        assert(edit.active() && backend.writes == writes && edit.caret() == 2);
        if (modifier == FcitxKey_Shift_L || modifier == FcitxKey_Shift_R) {
            route({{FcitxKey_A, KeyStates{KeyState::Shift}}});
            assert(backend.pos == 2);
            backend.raw.insert(backend.pos++, 1, 'A'); // simulated accepted character
            edit.finish(backend, eligible);
            assert(backend.raw == "niAho");
            route({{FcitxKey_A, KeyStates{KeyState::Shift}}, true});
        } else {
            route({{FcitxKey_BackSpace, KeyStates{KeyState::Ctrl}}});
            assert(backend.pos == 2); // real Rime then deletes a syllable HERE, not at end
            backend.raw.erase(0, backend.pos); backend.pos = 0;
            edit.finish(backend, eligible);
            assert(backend.raw == "ho");
            route({{FcitxKey_BackSpace, KeyStates{KeyState::Ctrl}}, true});
        }
        const int afterEdit = backend.writes;
        route({{modifier, KeyStates{}}, true});
        assert(edit.active() && backend.writes == afterEdit);
    }
    std::cout << "PASS actual injected key routing against pinned Fcitx key symbols: "
                 "key-up, edits, selections/keypad, page navigation, full Shift+A/Ctrl+Backspace sequences; "
                 "100 inactive appends with zero input/caret/context reads or writes\n";
}
'''.replace("ROUTING", BACKEND[BACKEND.index("bool personalEditingKey"):])

with tempfile.TemporaryDirectory(prefix="rime-edit-") as tmp:
    tmp = Path(tmp)
    (tmp / "routing.cpp").write_text(source)
    for source, name, extra in (
        (ROOT / "scripts/test_rime_edit.cpp", "state", []),
        (tmp / "routing.cpp", "routing", ["-I", str(headers), "-I", str(ROOT / "app/src/main/cpp")]),
    ):
        subprocess.run([os.environ.get("CXX", "g++"), "-std=c++17", "-Wall", "-Wextra", "-Werror",
                        *extra, str(source), "-o", str(tmp / name)], check=True)
        subprocess.run([str(tmp / name)], check=True)

print("Host checks only: real Rime candidate ranking, JNI/Android compilation, and UI behavior remain unverified.")
