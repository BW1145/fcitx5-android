// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include "state.h"
#include <fcitx/candidatelist.h>

// A dictionary preview becomes a normal conversion when selected.
class AnthyTypingCandidate : public fcitx::CandidateWord {
public:
    AnthyTypingCandidate(AnthyState *state, const fcitx::Text &text, int index)
        : state_(state), index_(index) {
        setText(text);
    }

    void select(fcitx::InputContext *) const override {
        state_->action_convert();
        state_->selectCandidate(index_);
        state_->updateUI();
    }

private:
    AnthyState *state_;
    int index_;
};
