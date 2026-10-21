// SPDX-License-Identifier: LGPL-2.1-or-later
#include "../app/src/main/cpp/androidfrontend/candidatepreferences.h"
#include <cassert>
#include <chrono>
#include <iostream>
#include <vector>

using namespace fcitx;
namespace fs=std::filesystem;

int main(int argc,char **argv) {
    if(argc!=2) return 2;
    const fs::path directory=argv[1];
    fs::create_directories(directory);
    const auto file=directory/"rules.tsv";
    const std::string scope="72696d65\t6e6968616f";
    fs::remove(file);
    CandidatePreferences first(file),second(file);
    assert(first.load());assert(!second.load());
    assert(!first.hasScope(scope));
    first.set(scope,"你好",2);
    assert(!second.load());assert(second.mode(scope,"你好")==2);
    // A replacement with the same length/time still has a different file identity.
    const auto time=fs::last_write_time(file);
    auto replacement=file;replacement+=".replacement";
    { std::ofstream out(replacement);out<<scope<<'\t'<<preferenceHex("你好")<<"\thide\n\n"; }
    assert(fs::file_size(replacement)==fs::file_size(file));
    fs::last_write_time(replacement,time);
    fs::rename(replacement,file);
    assert(second.load());assert(first.mode(scope,"你好")==1);
    fs::remove(file);
    assert(first.load());assert(first.mode(scope,"你好")==0);
    { std::ofstream out(file);
      for(int i=0;i<5000;++i) out<<scope<<'\t'<<preferenceHex("词条"+std::to_string(i))<<"\tlower\n";
    }
    assert(first.load());
    using clock=std::chrono::steady_clock;
    std::vector<double> baseline,cached;
    for(int run=0;run<33;++run) {
        auto start=clock::now();assert(first.load(true));
        auto middle=clock::now();assert(!second.load());
        assert(second.mode(scope,"词条4999")==2);
        auto end=clock::now();
        if(run>=3) {
            baseline.push_back(std::chrono::duration<double,std::milli>(middle-start).count());
            cached.push_back(std::chrono::duration<double,std::milli>(end-middle).count());
        }
    }
    for(auto *values:{&baseline,&cached}) std::sort(values->begin(),values->end());
    std::cout<<"5000 candidate rules; shared cache, replacement, deletion checks passed\n"
             <<"legacy_parse_p50_ms="<<baseline[15]<<" legacy_parse_p95_ms="<<baseline[28]<<'\n'
             <<"cached_lookup_p50_ms="<<cached[15]<<" cached_lookup_p95_ms="<<cached[28]<<'\n';
    fs::remove(file);
}
