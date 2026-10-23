// SPDX-License-Identifier: LGPL-2.1-or-later
// Independent contract checks against the production CandidateScan helper.
#include "candidatescan.h"
#include <algorithm>
#include <cassert>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>
using namespace fcitx;

// Exhaustive oracle follows the old frontend's order/lowered flush semantics.
static std::vector<int> expected(const std::vector<int>& modes) {
    std::vector<int> order, lower;
    for (size_t i=0;i<modes.size();++i) {
        if(modes[i]==0) order.push_back(i);
        else if(modes[i]==2) lower.push_back(i);
        if(order.size()>=32) { order.insert(order.end(),lower.begin(),lower.end()); lower.clear(); }
    }
    order.insert(order.end(),lower.begin(),lower.end());return order;
}
static size_t verify(const std::vector<int>& modes,bool known,size_t cap=32) {
    CandidateScan scan;
    ScanBudget budget{cap,std::chrono::hours(1)};
    std::vector<int> delivered;
    size_t calls=0,generated=0,requests=0,emptyPending=0;
    while(!scan.exhausted || delivered.size()<scan.order.size()) {
        auto before=calls;const int cursor=scan.cursor;
        auto result=scan.advance(static_cast<int>(delivered.size()+16),known?static_cast<int>(modes.size()):-1,
            [&](int index) {++calls;if(index>=static_cast<int>(modes.size()))throw std::invalid_argument("end");++generated;return modes.at(index);},budget);
        assert(calls-before<=cap);
        assert(result.inspected<=cap);
        assert(scan.cursor>=cursor);
        assert(scan.exhausted || scan.cursor>cursor || scan.order.size()>=delivered.size()+16);
        if(!scan.exhausted && scan.order.size()==delivered.size())++emptyPending;
        const auto end=std::min(scan.order.size(),delivered.size()+16);
        delivered.insert(delivered.end(),scan.order.begin()+delivered.size(),scan.order.begin()+end);
        assert(++requests<=modes.size()+3);
    }
    assert(delivered==expected(modes));
    assert(generated==modes.size()); // No duplicate materialization; every hidden source counted.
    assert(calls==modes.size()+(known?0:1));
    const auto old=calls;
    scan.advance(static_cast<int>(delivered.size()+99),known?static_cast<int>(modes.size()):-1,
        [&](int){++calls;return 0;},budget);
    assert(calls==old);
    if(modes.size()>10000 && std::count(modes.begin(),modes.begin()+10000,1)>9990) assert(emptyPending>=300);
    return calls;
}
int main() {
    size_t scenarios=0,totalCalls=0;
    for(int n:{0,1,15,16,17,31,32,33,63,64,65})for(bool known:{false,true}) {
        for(int mode:{0,1,2}) {totalCalls+=verify(std::vector<int>(n,mode),known);++scenarios;}
    }
    for(int normal:{0,1,15,31,32,33,64})for(bool known:{false,true}) {
        std::vector<int> modes(9,2);modes.insert(modes.end(),normal,0);modes.insert(modes.end(),7,2);
        totalCalls+=verify(modes,known,7);++scenarios;
    }
    for(bool known:{false,true}) {
        std::vector<int> dense(10000,1);dense.insert(dense.end(),40,0);
        totalCalls+=verify(dense,known);++scenarios;
        dense={0,0,0};dense.insert(dense.end(),10000,1);dense.insert(dense.end(),40,0);
        totalCalls+=verify(dense,known);++scenarios;
        std::vector<int> mixed;for(int i=0;i<15000;++i)mixed.push_back((i*17+i/11)%3);
        totalCalls+=verify(mixed,known,13);++scenarios;
    }
    // Partial progress must not flush lowered entries before 32 normal choices.
    CandidateScan partial;ScanBudget tiny{1,std::chrono::hours(1)};
    partial.advance(16,100,[](int){return 2;},tiny);
    assert(partial.order.empty() && partial.lowered.size()==1 && !partial.exhausted);
    // New input/rule generation replaces the scanner. Cached original indexes cannot leak.
    partial.reset();assert(partial.cursor==0 && partial.order.empty() && partial.lowered.empty() && !partial.exhausted);
    partial.advance(1,1,[](int i){assert(i==0);return 0;},tiny);assert(partial.order==std::vector<int>{0});
    // Duration budget is checked between materializations, not inside engine calls.
    CandidateScan timed;int calls=0;
    timed.advance(16,100,[&](int){++calls;std::this_thread::sleep_for(std::chrono::milliseconds(4));return 1;},
                  ScanBudget{32,std::chrono::milliseconds(1)});
    assert(calls<=1 && !timed.exhausted);
    CandidateScan failure;bool caught=false;
    try {failure.advance(16,-1,[](int)->int{throw std::runtime_error("not end");},tiny);}
    catch(const std::runtime_error&){caught=true;}
    assert(caught && !failure.exhausted);
    assert(failure.cursor==0); // Retry must not silently discard the failed raw candidate.
    std::cout<<"PASS scenarios="<<scenarios<<" raw_source_calls="<<totalCalls
             <<"; bounded calls, complete order, original indexes, reset, duration, exception contracts\n";
}
