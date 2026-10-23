// SPDX-License-Identifier: LGPL-2.1-or-later
// Synthetic preference-only microbenchmark, NOT an Android/engine latency test.
#include PREF_HEADER
#include <cassert>
#include <cstdlib>
#include <chrono>
#include <iostream>
#include <iomanip>
#include <numeric>
#include <tuple>
using namespace fcitx;
namespace fs=std::filesystem;
using Clock=std::chrono::steady_clock;
static uint64_t digest=1469598103934665603ULL;
static void observe(uint64_t x) { digest=(digest^x)*1099511628211ULL; }
static std::string word(int i) { return "合成词条"+std::to_string(i); }
static const std::string scope=preferenceHex("rime:synthetic")+"\t"+preferenceHex("ceshi");

// Faithful finite-list simulation of AndroidFrontend::fillOrder, including
// the existing 32-normal-candidate demotion boundary and incremental calls.
// Candidate strings are pre-generated: no Rime generation or UI cost is measured.
struct Order {
    CandidatePreferences &preferences;
    const std::vector<std::string> &words;
    int scanned=0;
    bool ended=false;
    std::vector<int> order,lowered;
    void fill(int count) {
        while(!ended && static_cast<int>(order.size())<count) {
            if(scanned>=static_cast<int>(words.size())) ended=true;
            else {
                const int index=scanned++;
                const int mode=preferences.mode(scope,words[index]);
                if(mode==2) lowered.push_back(index);
                else if(mode==0) order.push_back(index);
            }
            if(order.size()>=32 || ended) {
                order.insert(order.end(),lowered.begin(),lowered.end());lowered.clear();
            }
        }
    }
};
// Preserve measured samples in acquisition order, outside the timed region.
// Only generated fixture labels and durations are written; never candidate text.
static void saveSamples(const std::string &name,const std::vector<double> &times) {
    const char *path=std::getenv("CANDIDATE_SAMPLES_CSV");
    if (!path) return;
    static std::ofstream output(path,std::ios::trunc);
    static bool header=false;
    if (!output) throw std::runtime_error("Unable to write native benchmark samples");
    if (!header) { output<<"workload,sample_index,duration_us\n";header=true; }
    output<<std::setprecision(9);
    for (size_t i=0;i<times.size();++i) output<<name<<','<<i<<','<<times[i]<<'\n';
    output.flush();
    if (!output) throw std::runtime_error("Unable to flush native benchmark samples");
}
template<class F> void timing(const std::string &name,F fn,int repeats=301) {
    std::vector<double> times;
    for(int i=0;i<repeats+20;++i) {
        auto begin=Clock::now();fn();auto end=Clock::now();
        if(i>=20) times.push_back(std::chrono::duration<double,std::micro>(end-begin).count());
    }
    saveSamples(name,times);
    std::sort(times.begin(),times.end());
    std::cout<<"timing "<<name<<" p50_us="<<times[times.size()/2]
             <<" p95_us="<<times[(times.size()-1)*95/100]<<" n="<<times.size()<<'\n';
}
template<class Setup,class F> void coldTiming(const std::string &name,Setup setup,F fn) {
    std::vector<double> times;
    for(int run=0;run<61;++run) {
        setup();auto start=Clock::now();fn();auto end=Clock::now();
        if(run>=10) times.push_back(std::chrono::duration<double,std::micro>(end-start).count());
    }
    saveSamples(name,times);
    std::sort(times.begin(),times.end());
    std::cout<<"timing "<<name<<" p50_us="<<times[25]<<" p95_us="<<times[47]<<" n=51\n";
}
void fixture(const fs::path &file,int count,int mode) {
    std::ofstream out(file,std::ios::trunc);
    for(int i=0;i<count;++i) out<<scope<<'\t'<<preferenceHex(word(i))<<'\t'<<(mode==1?"hide":"lower")<<'\n';
}
int main(int argc,char **argv) {
    if(argc!=2 && argc!=3) return 2;
    fs::path dir=argv[1];fs::create_directories(dir);
    const auto file=dir/"bench.tsv";
    std::vector<std::string> words;
    for(int i=0;i<6000;++i) words.push_back(word(i));
    CandidatePreferences p(file),other(file);
    if(argc==3) {
        // Dedicated syscall probe: setup writes directly, only migration renames.
        const auto legacy=preferenceHex("anthy")+"\t"+preferenceHex("japanese");
        {std::ofstream out(file);for(int i=0;i<1000;++i) out<<legacy<<'\t'<<preferenceHex(word(i))<<"\thide\n";}
        p.load(true);std::vector<std::string> claimed(words.begin(),words.begin()+16);
#ifdef CURRENT_VERSION
        p.migrateWords(legacy,scope,claimed);
#else
        for(const auto &text:claimed) p.migrateWord(legacy,scope,text);
#endif
        for(const auto &text:claimed) assert(p.mode(scope,text)==1 && p.mode(legacy,text)==0);
        fs::remove(file);return 0;
    }
    std::cout<<std::fixed<<std::setprecision(3);
    for(const auto &[name,count,mode]:std::vector<std::tuple<std::string,int,int>>{
        {"no_rules",0,1},{"hide_1",1,1},{"lower_1",1,2},{"hide_10",10,1},{"lower_10",10,2},
        {"hide_100",100,1},{"lower_100",100,2},{"hide_5000",5000,1},{"lower_5000",5000,2}}) {
        fixture(file,count,mode);p.load(true);
        // Independent oracle: normal candidates first, demoted entries after
        // the 32nd normal choice (or after all normal choices for short lists).
        Order full{p,words};full.fill(16);full.fill(32);full.fill(7000);
        std::vector<int> expected;
        for(int i=count;i<6000;++i) {
            expected.push_back(i);
            if(mode==2 && i==count+31) for(int j=0;j<count;++j) expected.push_back(j);
        }
        if(mode==2 && 6000-count<32) for(int j=0;j<count;++j) expected.push_back(j);
        assert(full.order==expected);
        for(int index:full.order) observe(index);
        observe(full.scanned);
        timing(name+"_first16",[&] {
            p.load();
            if(!p.hasScope(scope)) { observe(16);return; }
            Order order{p,words};order.fill(16);
            observe(order.order.size());observe(order.scanned);
        });
        timing(name+"_lookup256",[&] {
            for(int i=0;i<256;++i) observe(p.mode(scope,words[i]));
        });
        if(count==5000 && mode==1) {
            timing("reload_5000",[&] { p.load(true);observe(p.mode(scope,word(42))); },41);
            coldTiming("cold_scope_lookup_5000",[&] {p.load(true);},[&] {observe(p.mode(scope,words[42]));});
            coldTiming("cold_first16_hide5000",[&] {p.load(true);},[&] {
                Order order{p,words};order.fill(16);observe(order.scanned);observe(order.order.size());
            });
        }
    }
    // Ten realistic separate readings share a file; only one reading is active.
    {std::ofstream out(file,std::ios::trunc);
     for(int reading=0;reading<10;++reading) for(int i=0;i<500;++i)
         out<<preferenceHex("rime:synthetic")<<'\t'<<preferenceHex("reading"+std::to_string(reading))<<'\t'<<preferenceHex(word(i))<<"\thide\n";
    }
    p.load(true);
    const auto activeScope=preferenceHex("rime:synthetic")+"\t"+preferenceHex("reading4");
    timing("ten_scopes_500_lookup256",[&] {for(int i=0;i<256;++i) observe(p.mode(activeScope,words[i]));});
    timing("ten_scopes_500_reload",[&] {p.load(true);observe(p.mode(activeScope,words[42]));},41);
    coldTiming("ten_scopes_cold_lookup",[&] {p.load(true);},[&] {observe(p.mode(activeScope,words[42]));});
    // Short/all-hidden/all-lowered and mixed-mode finite lists, independently
    // check full output against an oracle preserving the 32-normal boundary.
    for(int style=0;style<3;++style) {
        std::vector<std::string> small(words.begin(),words.begin()+47);
        std::vector<int> modes(47),normals,lowers;
        {std::ofstream out(file);
         for(int i=0;i<47;++i) {
             modes[i]=style==0?1:style==1?2:(i%3);
             if(modes[i]) out<<scope<<'\t'<<preferenceHex(small[i])<<'\t'<<(modes[i]==1?"hide":"lower")<<'\n';
             if(modes[i]==0) normals.push_back(i);
             if(modes[i]==2) lowers.push_back(i);
         }}
        p.load(true);Order actual{p,small};actual.fill(16);actual.fill(32);actual.fill(100);
        std::vector<int> expected=normals;
        expected.insert(expected.begin()+std::min<size_t>(32,expected.size()),lowers.begin(),lowers.end());
        assert(actual.order==expected);for(int i:actual.order) observe(i);
    }
    // Save/load, cross-instance invalidation, replacement, deletion, failed load.
    fixture(file,0,1);p.load(true);
#ifdef CURRENT_VERSION
    assert(p.empty() && other.empty());
#endif
    p.set(scope,word(1),1);
#ifdef CURRENT_VERSION
    assert(!p.empty() && !other.empty());
#endif
    assert(other.mode(scope,word(1))==1);assert(!other.load());
    p.set(scope,word(1),2);assert(other.mode(scope,word(1))==2);
    auto timestamp=fs::last_write_time(file);auto replacement=dir/"replacement.tsv";
    {std::ofstream out(replacement);out<<scope<<'\t'<<preferenceHex(word(1))<<"\thide\n\n";}
    assert(fs::file_size(file)==fs::file_size(replacement));
    fs::last_write_time(replacement,timestamp);fs::rename(replacement,file);
    assert(other.load());assert(p.mode(scope,word(1))==1);
    {std::ofstream out(file);out<<"invalid\n";}
    bool rejected=false;try {other.load(true);} catch(const std::runtime_error &) {rejected=true;}
    assert(rejected);assert(p.mode(scope,word(1))==1);
    fs::remove(file);assert(other.load());assert(p.mode(scope,word(1))==0);
#ifdef CURRENT_VERSION
    assert(p.empty() && other.empty());
#endif
    // Legacy migration conflict semantics and durable round trip.
    const auto legacy=preferenceHex("rime")+"\t"+preferenceHex("ceshi");
    p.set(legacy,word(1),1);p.set(legacy,word(2),1);p.set(scope,word(1),2);
    p.migrateScope(legacy,scope);assert(!p.hasScope(legacy));
    assert(p.mode(scope,word(1))==2 && p.mode(scope,word(2))==1);
    p.set(legacy,word(3),2);p.migrateWord(legacy,scope,word(3));
    assert(!p.hasScope(legacy));assert(p.mode(scope,word(3))==2);
    p.merge({{scope+"\t"+preferenceHex(word(1)),1}});assert(p.mode(scope,word(1))==2);
    assert(CandidatePreferences::read(file).size()==3);
    timing("legacy_scope_no_hit",[&] {p.migrateScope(legacy,scope);observe(p.mode(scope,word(1)));});
    timing("legacy_word_no_hit",[&] {p.migrateWord(legacy,scope,word(3));observe(p.mode(scope,word(3)));});
    timing("save_3_rules",[&] {p.set(scope,word(1),2);observe(p.mode(scope,word(1)));},41);
    coldTiming("legacy_scope_hit_3",[&] {
        fixture(file,0,1);p.load(true);p.set(legacy,word(1),1);p.set(legacy,word(2),2);p.set(scope,word(1),2);
    },[&] {p.migrateScope(legacy,scope);observe(p.mode(scope,word(1)));observe(p.mode(scope,word(2)));});
    coldTiming("legacy_word_hit",[&] {
        fixture(file,0,1);p.load(true);p.set(legacy,word(1),1);
    },[&] {p.migrateWord(legacy,scope,word(1));observe(p.mode(scope,word(1)));});
    coldTiming("legacy_anthy_16_hits_in_1000",[&] {
        std::ofstream out(file,std::ios::trunc);
        for(int i=0;i<1000;++i) out<<legacy<<'\t'<<preferenceHex(word(i))<<"\thide\n";
        out.close();p.load(true);
    },[&] {for(int i=0;i<16;++i) {p.migrateWord(legacy,scope,word(i));observe(p.mode(scope,word(i)));}});
    std::vector<std::string> claimed(words.begin(),words.begin()+16);
    coldTiming("frontend_legacy_16_hits_in_1000",[&] {
        std::ofstream out(file,std::ios::trunc);
        for(int i=0;i<1000;++i) out<<legacy<<'\t'<<preferenceHex(word(i))<<"\thide\n";
        out.close();p.load(true);
    },[&] {
#ifdef CURRENT_VERSION
        p.migrateWords(legacy,scope,claimed);
#else
        for(const auto &text:claimed) p.migrateWord(legacy,scope,text);
#endif
        for(const auto &text:claimed) observe(p.mode(scope,text));
    });
    // Batch API must preserve duplicate, conflict, absent-word, empty and
    // same-scope semantics of the original single-word migration sequence.
    auto migrate=[&](const std::string &from,const std::string &to,const std::vector<std::string> &texts) {
#ifdef CURRENT_VERSION
        p.migrateWords(from,to,texts);
#else
        for(const auto &text:texts) p.migrateWord(from,to,text);
#endif
    };
    fixture(file,0,1);p.load(true);
    p.set(legacy,word(1),1);p.set(legacy,word(2),2);p.set(scope,word(1),2);
    migrate(legacy,scope,{word(1),word(1),word(2),word(999)});
    assert(p.mode(scope,word(1))==2 && p.mode(scope,word(2))==2 && !p.hasScope(legacy));
    auto saved=CandidatePreferences::read(file);
    migrate(legacy,scope,{});migrate(legacy,scope,{word(999)});
    assert(CandidatePreferences::read(file)==saved);
    migrate(scope,scope,{word(1),word(1)});assert(p.mode(scope,word(1))==0);
    // Model the actual old/new frontend migration timing, not merely the API.
    auto legacyOrder=[&](const std::string &from,const std::string &to,const std::vector<std::string> &texts) {
        std::vector<int> order,lowered;std::vector<std::string> claims;
        for(size_t i=0;i<texts.size();++i) {
            int mode;
#ifdef CURRENT_VERSION
            mode=p.mode(to,texts[i]);const int oldMode=p.mode(from,texts[i]);
            if(oldMode || p.hasWord(from,texts[i])) {claims.push_back(texts[i]);if(from==to) mode=0;else if(!mode && !p.hasWord(to,texts[i])) mode=oldMode;}
#else
            p.migrateWord(from,to,texts[i]);mode=p.mode(to,texts[i]);
#endif
            if(mode==2) lowered.push_back(i);else if(mode==0) order.push_back(i);
            if(order.size()>=32) {order.insert(order.end(),lowered.begin(),lowered.end());lowered.clear();}
        }
        order.insert(order.end(),lowered.begin(),lowered.end());
#ifdef CURRENT_VERSION
        p.migrateWords(from,to,claims);
#endif
        return order;
    };
    fixture(file,0,1);p.load(true);
    p.set(legacy,word(1),1);p.set(scope,word(1),2);p.set(legacy,word(2),2);
    assert((legacyOrder(legacy,scope,{word(1),word(1),word(2),word(999)})==std::vector<int>{3,0,1,2}));
    assert(!p.hasScope(legacy));
    assert((legacyOrder(scope,scope,{word(1),word(1),word(2)})==std::vector<int>{0,1,2}));
    p.set(legacy,word(1),1);p.set(legacy,word(2),1);
    assert(legacyOrder(legacy,scope,{word(1),word(2)}).empty());
    assert(legacyOrder(legacy,scope,{}).empty());
    assert((legacyOrder(legacy,scope,{word(999)})==std::vector<int>{0}));
    // Noncanonical direct API mode zero is still a present target rule and
    // must beat a legacy hide rule; zero-valued legacy rows must be claimed.
    fixture(file,0,1);p.load(true);
    p.merge({{legacy+"\t"+preferenceHex(word(1)),1}, {scope+"\t"+preferenceHex(word(1)),0},
             {legacy+"\t"+preferenceHex(word(2)),0}});
    assert((legacyOrder(legacy,scope,{word(1),word(2)})==std::vector<int>{0,1}));
    assert(!p.hasScope(legacy));assert(p.mode(scope,word(1))==0 && p.mode(scope,word(2))==0);
    // Malformed merge keys remain non-matching without crashing the index.
    fixture(file,0,1);p.load(true);
    p.merge({{"no-separator",1},{scope+"\t0",1},{scope+"\tzz",1},{scope+"\tC3A9",1}});
    assert(p.mode(scope,"é")==0);assert(p.mode(scope,word(1))==0);
    fs::remove(file);p.load();
    std::cout<<"correctness order_and_cache_digest="<<digest<<" PASS\n";
    fs::remove(file);
}
