// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#ifdef FCITX_CANDIDATE_ATTRIBUTION
#include <array>
#include <chrono>
#include <cstdint>
#include <vector>
#include <stdexcept>
namespace ime_attribution __attribute__((visibility("hidden"))) {
enum Metric { ProcessKey, GetContext, BeginPreferences, RulesLoad, ScopeResolve,
    Classify, RawScanFetch, OutputMaterialize, InitialUpdate, BatchRequest,
    CandidateJni, RimeFetchMiss, RimeFetchHit, ScanItems, OutputItems, InputPanel,
    MetricCount };
struct Store { int mode=0; std::array<int64_t,MetricCount*2> values{}; };
// One store per shared object, shared across its translation units. No interposition.
__attribute__((visibility("hidden"))) inline Store &store() { static Store value; return value; }
inline void configure(int mode) { store().mode=mode;store().values.fill(0); }
inline bool bypass() { return store().mode==2; }
template<class Addon> Addon *requireAddon(Addon *addon) {
    if (!addon) throw std::runtime_error("Attribution requires Rime activation before configure/drain");
    return addon;
}
inline void merge(std::vector<int64_t> &target,const std::vector<int64_t> &source) {
    if (source.size()!=target.size()) throw std::runtime_error("Attribution metric schema mismatch across addons");
    for (size_t i=0;i<target.size();++i) target[i]+=source[i];
}
inline void count(Metric metric) { if(store().mode) ++store().values[metric*2+1]; }
inline std::vector<int64_t> drain() {
    auto &s=store();std::vector<int64_t> result(s.values.begin(),s.values.end());
    s.values.fill(0);return result;
}
class Scope {
    Metric metric_; bool active_; std::chrono::steady_clock::time_point start_;
public:
    explicit Scope(Metric metric):metric_(metric),active_(store().mode!=0) {
        if(active_)start_=std::chrono::steady_clock::now();
    }
    ~Scope() { if(active_) { auto &s=store();
        s.values[metric_*2]+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start_).count();
        ++s.values[metric_*2+1];
    } }
};
template<class F> auto timed(Metric metric,F &&function)->decltype(function()) {
    Scope scope(metric);return function();
}
}
#define IME_ATTR_SCOPE(metric) ime_attribution::Scope imeAttributionScope(ime_attribution::metric)
#define IME_ATTR_COUNT(metric) ime_attribution::count(ime_attribution::metric)
#define IME_ATTR_TIME(metric, expression) ime_attribution::timed(ime_attribution::metric,[&]() -> decltype(auto) { return (expression); })
#else
#define IME_ATTR_SCOPE(metric) ((void)0)
#define IME_ATTR_COUNT(metric) ((void)0)
#define IME_ATTR_TIME(metric, expression) (expression)
#endif
