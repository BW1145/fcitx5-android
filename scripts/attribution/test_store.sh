#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/build/attribution-store-test"; mkdir -p "$out"
cat > "$out/a.cpp" <<'CPP'
#include "candidateattribution.h"
#include <cassert>
extern "C" void testTimers() {
 ime_attribution::configure(1);
 { IME_ATTR_SCOPE(ProcessKey); auto result=IME_ATTR_TIME(GetContext, 7); assert(result==7); }
 auto sample=ime_attribution::drain();assert(sample[1]==1 && sample[3]==1);assert(sample[0]>=sample[2]);
}
extern "C" void setup(int mode) { ime_attribution::configure(mode); }
extern "C" long readCount() { return ime_attribution::drain()[ime_attribution::ScanItems*2+1]; }
CPP
cat > "$out/b.cpp" <<'CPP'
#include "candidateattribution.h"
extern "C" void mark() { IME_ATTR_COUNT(ScanItems); }
CPP
cat > "$out/main.cpp" <<'CPP'
#include <dlfcn.h>
#include <cassert>
#include <iostream>
int main(int argc,char**argv) {
 assert(argc==3);void *a=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL),*b=dlopen(argv[2],RTLD_NOW|RTLD_GLOBAL);assert(a&&b);
 auto setupA=(void(*)(int))dlsym(a,"setup"),setupB=(void(*)(int))dlsym(b,"setup");
 auto markA=(void(*)())dlsym(a,"mark"),markB=(void(*)())dlsym(b,"mark");
 auto readA=(long(*)())dlsym(a,"readCount"),readB=(long(*)())dlsym(b,"readCount");
 setupA(1);setupB(1);markA();markA();markB();assert(readA()==2);assert(readB()==1);assert(readA()==0);
 setupA(0);markA();assert(readA()==0);setupA(2);markA();assert(readA()==1);
 ((void(*)())dlsym(a,"testTimers"))();
 std::cout<<"PASS inclusive timers, per-DSO isolation, shared cross-TU store, drain/reset, disabled collection\n";
}
CPP
for name in one two; do
 "${CXX:-g++}" -std=c++20 -fPIC -shared -DFCITX_CANDIDATE_ATTRIBUTION=1 -I"$root/app/src/main/cpp" "$out/a.cpp" "$out/b.cpp" -o "$out/$name.so"
done
"${CXX:-g++}" -std=c++20 "$out/main.cpp" -ldl -o "$out/test"
"$out/test" "$out/one.so" "$out/two.so"
