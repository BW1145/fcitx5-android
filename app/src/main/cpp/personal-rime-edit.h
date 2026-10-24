// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>

namespace fcitx::rime::personal {

// Only the editable presentation is retained. Candidate objects/indices always
// belong to the live, end-of-input Rime session, never to this snapshot.
struct EditPreedit {
    std::string text;
    int cursor = -1;
    int selectionStart = 0;
    int selectionEnd = 0;
};

inline size_t codepointByteOffset(const std::string &text, size_t position) {
    size_t offset = 0;
    while (offset < text.size() && position--) {
        ++offset;
        while (offset < text.size() &&
               (static_cast<unsigned char>(text[offset]) & 0xc0) == 0x80) ++offset;
    }
    return offset;
}

inline bool plainPinyin(const std::string &input) {
    if (input.empty()) return false;
    for (unsigned char c : input) {
        if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && c != '\'') return false;
    }
    return true;
}

inline bool spellingDisplayMatches(char display, const std::string &input, size_t index) {
    if (display == input[index]) return true;
    // Length-preserving preedit_format rules in the pinned rime_ice schema.
    if (display == 'u' && input[index] == 'v' && index &&
        std::string("jqxy").find(input[index - 1]) != std::string::npos) return true;
    return display == 'v' && input[index] == 'u' && index && index + 1 < input.size() &&
        (input[index - 1] == 'n' || input[index - 1] == 'l') && input[index + 1] == 'e';
}

// Resolve against the OLD visible string before changing engine state. Later
// preedits may insert different separators and are not the same coordinate space.
inline std::optional<size_t> matchBackwards(const std::string &display,
        size_t begin, size_t end, const std::string &input, size_t rawEnd) {
    if (begin > end || end > display.size() || rawEnd > input.size()) return std::nullopt;
    while (end > begin) {
        const char ch = display[--end];
        if (ch == ' ') {
            if (rawEnd && input[rawEnd - 1] == '\'') {
                // A collapsed run of explicit delimiters is not invertible.
                if (rawEnd > 1 && input[rawEnd - 2] == '\'') return std::nullopt;
                --rawEnd;
            }
        } else if (rawEnd && spellingDisplayMatches(ch, input, rawEnd - 1)) {
            --rawEnd;
        } else {
            return std::nullopt; // Unknown correction/formatting: never guess.
        }
    }
    return rawEnd;
}

inline std::optional<size_t> rawTarget(const EditPreedit &shown,
        const std::string &input, size_t rawCaret, size_t target) {
    if (rawCaret > input.size() || shown.cursor < 0 || shown.selectionStart < 0 ||
        static_cast<size_t>(shown.cursor) > shown.text.size() ||
        target < static_cast<size_t>(shown.selectionStart) || target > shown.text.size()) return std::nullopt;
    if (target == shown.text.size()) return input.size();
    const auto cursor = static_cast<size_t>(shown.cursor);
    if (target == cursor) return rawCaret;
    if (target < cursor) return matchBackwards(shown.text, target, cursor, input, rawCaret);
    if (shown.text.substr(cursor) == input.substr(rawCaret)) {
        return rawCaret + target - cursor; // Rime appends the untouched raw suffix.
    }
    // At zero/a confirmed boundary, Rime may translate one segment AFTER the
    // caret. Only accept its formatting when the complete suffix maps exactly.
    const auto start = matchBackwards(shown.text, cursor, shown.text.size(), input, input.size());
    if (!start || *start != rawCaret) return std::nullopt;
    return matchBackwards(shown.text, target, shown.text.size(), input, input.size());
}

class EditCaret {
public:
    bool active() const { return view_.has_value(); }
    const EditPreedit &view() const { return *view_; }
    size_t caret() const { return caret_; }
    void clear() { view_.reset(); input_.clear(); session_ = 0; caret_ = 0; }

    template<class Backend>
    bool valid(Backend &backend) const {
        return active() && session_ == backend.sessionId() &&
               input_ == backend.input() && backend.caret() == input_.size();
    }

    template<class Backend>
    bool resume(Backend &backend) {
        if (!valid(backend)) { clear(); return false; }
        backend.setCaret(caret_);
        return true;
    }

    // This fast path adds no context reads or recompositions to normal typing.
    template<class Backend, class Eligible>
    void finish(Backend &backend, Eligible eligible,
                std::optional<EditPreedit> snapshot = std::nullopt) {
        const auto input = backend.input();
        const auto caret = backend.caret();
        if (input.empty() || caret >= input.size() || !eligible(input)) {
            clear();
            return;
        }
        if (!snapshot) snapshot = backend.capture();
        if (!snapshot || snapshot->cursor < 0) { clear(); return; }
        input_ = input;
        caret_ = caret;
        session_ = backend.sessionId();
        view_ = std::move(snapshot);
        backend.setCaret(input.size());
        if (!valid(backend)) clear();
    }

    // The requested position counts Unicode codepoints in the pinyin row, not
    // bytes or Android UTF-16 units. Walk from the current caret only: probing
    // from raw position zero would discard already-selected prefix segments.
    template<class Backend, class Eligible>
    bool move(Backend &backend, size_t position, Eligible eligible) {
        const auto input = backend.input();
        if (!eligible(input)) return false;
        if (active() && !valid(backend)) clear();
        auto shown = active() ? std::optional<EditPreedit>(view_) : backend.capture();
        if (!shown || shown->cursor < 0) return false;
        const auto target = codepointByteOffset(shown->text, position);
        const auto origin = active() ? caret_ : backend.caret();
        const auto destination = rawTarget(*shown, input, origin, target);
        // Unsupported formatting and selected-prefix taps retain legacy editing
        // semantics. The adapter restores any saved caret before that fallback.
        if (!destination) return false;
        // Formatting may add/remove separators while the caret moves. The end
        // of the displayed row always means the end of raw input, not a fixed
        // byte offset in the subsequently reformatted preedit.
        if (*destination == input.size()) {
            if (backend.caret() != input.size()) backend.setCaret(input.size());
            clear();
            return true;
        }
        if (active()) resume(backend);
        const bool forward = origin < *destination;
        for (size_t step = 0; step <= input.size(); ++step) {
            const auto raw = backend.caret();
            if (raw == *destination) break;
            if (forward ? raw >= input.size() : raw == 0) break;
            // KP arrows use Rime's character movement, unlike Left/Right which
            // may jump syllables. The bounds above prevent boundary wrapping.
            backend.stepCharacter(forward);
            if (backend.input() != input) { clear(); return true; }
            const auto nextRaw = backend.caret();
            if (nextRaw != (forward ? raw + 1 : raw - 1)) { clear(); return true; }
        }
        finish(backend, eligible);
        return true;
    }

private:
    uint64_t session_ = 0;
    size_t caret_ = 0;
    std::string input_;
    std::optional<EditPreedit> view_;
};

} // namespace fcitx::rime::personal
