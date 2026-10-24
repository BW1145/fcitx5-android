// SPDX-License-Identifier: LGPL-2.1-or-later
// Small deterministic backend test. This is not a substitute for real Rime/UI tests.
#include "../app/src/main/cpp/personal-rime-edit.h"
#include <cassert>
#include <iostream>
#include <vector>

using fcitx::rime::personal::EditCaret;
using fcitx::rime::personal::EditPreedit;
using fcitx::rime::personal::plainPinyin;

struct Backend {
    uint64_t id = 1;
    std::string raw = "niho";
    size_t pos = 4, selected = 0;
    int writes = 0, captures = 0, steps = 0, lostSelections = 0;
    bool failCapture = false, mutateOnStep = false, jumpOnStep = false;
    std::vector<size_t> visited;
    uint64_t sessionId() const { return id; }
    std::string input() const { return raw; }
    size_t caret() const { return pos; }
    void setCaret(size_t p) {
        assert(p <= raw.size());
        ++writes; visited.push_back(p); pos = p;
        if (selected && p < selected) { selected = 0; ++lostSelections; }
    }
    void stepCharacter(bool forward) {
        ++steps;
        if (mutateOnStep) { raw += "z"; pos = raw.size(); return; }
        const size_t distance = jumpOnStep ? 2 : 1;
        setCaret(forward ? pos + distance : pos - distance);
    }
    std::optional<EditPreedit> capture() {
        ++captures;
        if (failCapture) return std::nullopt;
        if (pos == 0) {
            // Actual Rime can translate a segment after caret zero. Do not use
            // an unformatted fake zero-caret suffix as the sole regression.
            auto text = raw;
            if (raw.size() > 2) text.insert(2, " ");
            return EditPreedit{text, 0, 0, static_cast<int>(text.size())};
        }
        // Like upstream: converted prefix, formatted input through the caret,
        // followed by verbatim raw suffix. Insert a separator after "ni".
        std::string prefix = selected ? "你好" : "";
        prefix += raw.substr(selected, pos - selected);
        if (!selected && pos > 2) prefix.insert(2, " ");
        const int cursor = prefix.size();
        return EditPreedit{prefix + raw.substr(pos), cursor,
            selected ? 6 : 0, cursor};
    }
    std::string candidatesFor() const { return raw.substr(0, pos); }
    void insert(char ch) { raw.insert(pos, 1, ch); ++pos; }
    void eraseBefore() { assert(pos); raw.erase(--pos, 1); }
    void eraseAfter() { assert(pos < raw.size()); raw.erase(pos, 1); }
};

int main() {
    auto eligible = [](const std::string &input) { return plainPinyin(input); };
    {
        Backend b; EditCaret state; int checks = 0;
        for (int i = 0; i < 100; ++i) {
            b.insert('a');
            state.finish(b, [&](const auto &) { ++checks; return true; });
        }
        assert(!state.active() && b.writes == 0 && b.captures == 0 && checks == 0);
    }
    {
        Backend b; EditCaret state;
        assert(state.move(b, 4, eligible)); // ni h|o
        assert(state.active() && state.caret() == 3 && b.pos == 4);
        assert(state.view().cursor == 4 && state.view().text == "ni ho");
        assert(state.resume(b)); b.insert('a'); state.finish(b, eligible);
        assert(b.raw == "nihao" && state.caret() == 4 && b.pos == 5);
        assert(state.view().text == "ni hao" && state.view().cursor == 5);
        assert(b.candidatesFor() == "nihao"); // actual engine state is whole input
        assert(state.resume(b)); b.insert('A'); state.finish(b, eligible);
        assert(state.active() && b.raw == "nihaAo" && b.pos == b.raw.size());
        assert(state.resume(b)); b.eraseBefore(); state.finish(b, eligible);
        assert(state.resume(b)); b.eraseBefore(); state.finish(b, eligible);
        assert(b.raw == "niho" && state.caret() == 3 && b.pos == 4);
        assert(state.resume(b)); b.insert('a'); state.finish(b, eligible);
        assert(state.resume(b)); b.eraseAfter(); state.finish(b, eligible);
        assert(b.raw == "niha" && b.pos == 4 && !state.active());
    }
    {
        Backend b; EditCaret state;
        assert(state.move(b, 2, eligible)); // ni|ho: raw2, unchanged suffix ho
        assert(state.caret() == 2 && state.view().text == "niho" && state.view().cursor == 2);
        assert(state.move(b, 3, eligible)); // OLD niho cp3 is nih|o, not ni|ho.
        assert(state.caret() == 3 && state.view().cursor == 4);
        assert(state.resume(b)); b.insert('a'); state.finish(b, eligible);
        assert(b.raw == "nihao" && state.caret() == 4 && b.pos == b.raw.size());
        assert(state.move(b, 2, eligible)); // backward also resolves the OLD string.
        assert(state.caret() == 2 && state.view().text == "nihao");
        assert(state.move(b, 4, eligible)); // OLD raw suffix: niha|o
        assert(state.caret() == 4 && state.view().cursor == 5);
    }
    {
        Backend b; EditCaret state;
        assert(state.move(b, 0, eligible));
        assert(state.caret() == 0 && b.pos == b.raw.size());
        assert(state.view().text == "ni ho"); // Rime's zero-caret exception
        assert(state.move(b, 3, eligible)); // OLD formatted separator before h
        assert(state.caret() == 2 && state.view().cursor == 2);
        assert(state.move(b, 999, eligible));
        assert(b.pos == b.raw.size() && !state.active());
        assert(state.move(b, 1, eligible));
        assert(state.resume(b)); // End is routed as editing, unlike menu paging.
        b.setCaret(b.raw.size());
        state.finish(b, eligible);
        assert(!state.active());
        b.insert('a');
        assert(b.raw == "nihoa");
    }
    {
        Backend b; b.raw = "nihaoma"; b.pos = 7; b.selected = 5;
        EditCaret state;
        assert(!state.move(b, 1, eligible)); // selected Chinese prefix: legacy fallback, no probing
        assert(b.writes == 0 && b.lostSelections == 0);
        assert(state.move(b, 3, eligible)); // 你好m|a: codepoints, not bytes
        assert(state.caret() == 6 && b.pos == 7 && b.selected == 5);
        assert(b.lostSelections == 0);
        assert(!state.move(b, 1, eligible) && state.active() && b.pos == 7);
        assert(state.resume(b)); // Native adapter restores before legacy fallback.
        state.clear();
        assert(b.pos == 6 && b.selected == 5 && b.lostSelections == 0);
        // Selection can retain the SAME raw input; integration must clear it.
        state.clear(); b.selected = 7;
        assert(!state.active());
    }
    {
        Backend b; EditCaret state;
        assert(state.move(b, 4, eligible));
        const auto savedWrites = b.writes;
        ++b.id;
        assert(!state.resume(b) && !state.active() && b.writes == savedWrites);
        b.id = 1;
        assert(state.move(b, 4, eligible));
        b.raw += 'a'; b.pos = b.raw.size();
        assert(!state.resume(b) && !state.active());
        assert(state.move(b, 4, eligible));
        state.clear();
        assert(!state.active());
    }
    {
        Backend b; b.failCapture = true; EditCaret state;
        assert(!state.move(b, 1, eligible) && b.writes == 0); // failed read: restore/legacy fallback
        b.failCapture = false; b.mutateOnStep = true;
        assert(state.move(b, 1, eligible));
        assert(b.raw == "nihoz" && !state.active()); // never silently replay old input
    }
    {
        Backend b; b.raw = "R123"; EditCaret state;
        assert(!state.move(b, 1, eligible) && b.writes == 0 && b.captures == 0);
    }
    using fcitx::rime::personal::codepointByteOffset;
    using fcitx::rime::personal::rawTarget;
    assert(rawTarget({"xi an", 5, 0, 5}, "xian", 4, 2) == 2);
    assert(rawTarget({"xi an", 5, 0, 5}, "xian", 4, 3) == 2);
    assert(rawTarget({"xi an", 5, 0, 5}, "xi'an", 5, 2) == 2);
    assert(rawTarget({"xi an", 5, 0, 5}, "xi'an", 5, 3) == 3);
    assert(!rawTarget({"xi an", 5, 0, 5}, "xi''an", 6, 2));
    assert(!rawTarget({"jü", 3, 0, 3}, "jv", 2, 1)); // unknown formatting is not guessed
    assert(rawTarget({"qu", 2, 0, 2}, "qv", 2, 1) == 1);
    assert(rawTarget({"nve", 3, 0, 3}, "nue", 3, 1) == 1);
    assert(rawTarget({"lve", 0, 0, 3}, "lue", 0, 2) == 2);
    assert(codepointByteOffset("你😀a", 0) == 0);
    assert(codepointByteOffset("你😀a", 1) == 3);
    assert(codepointByteOffset("你😀a", 2) == 7);
    assert(codepointByteOffset("你😀a", 99) == 8);
    assert(plainPinyin("xi'an") && plainPinyin("niHao") && !plainPinyin("ni hao") && !plainPinyin("R123"));
    std::cout << "PASS edit caret: normal-path zero context/caret writes; whole-input state; "
                 "repeated insert/backspace/delete; formatting boundary; Unicode positions; "
                 "two-tap raw2-to-raw3 regression; old-snapshot mapping; selected-prefix preservation; "
                 "session/input invalidation; failure safety\n";
}
