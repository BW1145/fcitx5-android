// SPDX-License-Identifier: LGPL-2.1-or-later
// Linux host-only test instrumentation. Not compiled into the Android app.
#include <cstdio>
#include <dlfcn.h>
static unsigned saves=0;
extern "C" int rename(const char *from,const char *to) {
    using Function=int(*)(const char *,const char *);
    static const auto real=reinterpret_cast<Function>(dlsym(RTLD_NEXT,"rename"));
    if(!real) return -1;
    const int result=real(from,to);
    if(result==0) ++saves;
    return result;
}
__attribute__((destructor)) static void report() {std::fprintf(stderr,"successful_renames=%u\n",saves);}
