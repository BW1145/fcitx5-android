// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <algorithm>
#include <vector>

namespace fcitx {
// The order and current index use the same coordinates: global for bulk lists,
// page-local for paged lists. Moving a page replaces order before returning.
template <typename MovePage>
int preferredCandidateIndex(const std::vector<int> &order, int current,
                            bool advance, MovePage movePage) {
    auto selected = std::find(order.begin(), order.end(), current);
    if (selected != order.end()) {
        if (!advance) return *selected;
        if (++selected != order.end()) return *selected;
    } else if (!order.empty()) {
        return order.front();
    }
    while (true) {
        const bool moved = movePage();
        if (!order.empty()) return order.front();
        if (!moved) return -1;
    }
}
}
