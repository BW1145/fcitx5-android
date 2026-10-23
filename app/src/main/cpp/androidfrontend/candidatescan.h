// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <chrono>
#include <cstddef>
#include <limits>
#include <stdexcept>
#include <vector>

namespace fcitx {
struct ScanBudget {
    size_t maxCandidates = 32;
    std::chrono::nanoseconds maxDuration = std::chrono::milliseconds(2);
    static ScanBudget unlimited() {
        return {std::numeric_limits<size_t>::max(), std::chrono::nanoseconds::max()};
    }
};
struct ScanResult {
    size_t inspected = 0;
    bool budgetExhausted = false;
};
// Stable prefix of the original candidate order. Hidden words never enter the
// prefix; lowered words retain the existing visible-prefix size/end boundary.
class CandidateScan {
public:
    int cursor = 0;
    bool exhausted = false;
    std::vector<int> order, lowered;
    void reset() { cursor = 0; exhausted = false; order.clear(); lowered.clear(); }

    template<class ModeAt>
    ScanResult advance(int targetVisible, int knownTotal, ModeAt modeAt,
                       ScanBudget budget = {}) {
        ScanResult result;
        const auto start = std::chrono::steady_clock::now();
        while (!exhausted && static_cast<int>(order.size()) < targetVisible) {
            if (knownTotal >= 0 && cursor >= knownTotal) {
                exhausted = true;
            } else {
                // A single engine call cannot be preempted. The elapsed-time
                // limit is cooperative and checked between original candidates.
                if (result.inspected >= budget.maxCandidates ||
                    (result.inspected && std::chrono::steady_clock::now()-start >= budget.maxDuration)) {
                    result.budgetExhausted = true;
                    break;
                }
                const int index = cursor;
                ++result.inspected;
                try {
                    const int mode = modeAt(index);
                    ++cursor;
                    if (mode == 2) lowered.push_back(index);
                    else if (mode == 0) order.push_back(index);
                } catch (const std::invalid_argument &) {
                    ++cursor;
                    exhausted = true;
                }
            }
            if (order.size() >= 32 || exhausted) {
                order.insert(order.end(), lowered.begin(), lowered.end());
                lowered.clear();
            }
        }
        return result;
    }
};
}
