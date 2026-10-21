// SPDX-License-Identifier: LGPL-2.1-or-later
#include "../app/src/main/cpp/androidfrontend/preferredcandidate.h"
#include <cassert>
#include <iostream>

using namespace fcitx;

int main() {
    const auto noPage=[] { return false; };
    const std::vector<int> bulk{1,2,0}; // Raw candidate zero was lowered.
    assert(preferredCandidateIndex(bulk,-1,false,noPage)==1);
    assert(preferredCandidateIndex(bulk,1,true,noPage)==2);
    assert(preferredCandidateIndex(bulk,2,true,noPage)==0);
    assert(preferredCandidateIndex(bulk,0,true,noPage)==1);
    assert(preferredCandidateIndex({1,2},0,true,noPage)==1); // Hidden cursor.
    assert(preferredCandidateIndex({},0,true,noPage)==-1);

    // Page-local raw indices restart at zero on each engine page.
    const std::vector<std::vector<int>> pages{{1,0},{},{0,2}};
    int page=0,visits=0;
    std::vector<int> order=pages[page];
    auto next=[&](int current, bool advance=true, bool wrap=true) {
        const int start=page;
        return preferredCandidateIndex(order,current,advance,[&] {
            if (page+1==static_cast<int>(pages.size()) && !wrap) return false;
            page=(page+1)%pages.size();
            ++visits;
            order=pages[page];
            return page!=start;
        });
    };
    assert(next(1)==0 && page==0); // Lowered word remains selectable.
    assert(next(0)==0 && page==2 && visits==2); // Skip the all-hidden page.
    assert(next(0)==2 && page==2);
    assert(next(2)==1 && page==0); // Space wraps to the first displayed page.
    assert(next(0,false)==0 && page==0); // Page movement preserves a visible cursor.
    assert(next(7,false)==1 && page==0); // A hidden cursor uses a visible choice.
    page=1;order=pages[page];
    assert(next(-1,false,false)==0 && page==2); // Paging skips hidden pages too.
    assert(next(2,true,false)==0 && page==2); // End-of-list remains on this page.

    // Finite page traversal also terminates when every candidate is hidden.
    page=0;visits=0;order.clear();
    assert(preferredCandidateIndex(order,-1,true,[&] {
        page=(page+1)%3;
        ++visits;
        return page!=0;
    })==-1);
    assert(visits==3);
    std::cout<<"Preferred bulk and paged cursor, hidden pages, and wrap checks passed\n";
}
