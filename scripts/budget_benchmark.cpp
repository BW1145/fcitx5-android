// SPDX-License-Identifier: LGPL-2.1-or-later
// Host-only helper benchmark. The source is a generated integer classifier:
// no Rime generation, Android dispatch, real typed text, or wall-time UI claim.
#include BUDGET_HEADER
#include <algorithm>
#include <cassert>
#include <iomanip>
#include <fstream>
#include <iostream>
#include <string>
using namespace fcitx;
using Clock=std::chrono::steady_clock;
static unsigned long long checksum=0;
int main(int argc, char **argv) {
 assert(argc==2);
 std::ofstream samples(argv[1]);
 if(!samples) return 2;
 samples<<"trial,path,hidden,elapsed_us,inspected,batches\n"<<std::fixed<<std::setprecision(6);
 std::cout<<"hidden_prefix,path,target_visible,first_visible,first_inspected,total_inspected,batches_min,batches_max,p50_us,p95_us,samples\n"<<std::fixed<<std::setprecision(3);
 for(int hidden:{0,10,100,1000,10000}) {
  for(const std::string path:{"first_batch_16","explicit_first_1","explicit_first_16","unlimited_16"}) {
   const int target=path=="explicit_first_1"?1:16;
   const bool once=path=="first_batch_16";
   const bool unlimited=path=="unlimited_16";
   std::vector<double> elapsed;size_t batchesMin=~size_t(0),batchesMax=0;
   size_t firstVisible=0,firstInspected=0,totalInspected=0;
   for(int trial=0;trial<521;++trial) {
    CandidateScan scan;size_t batches=0,inspected=0;
    const auto start=Clock::now();
    do {
     const auto result=scan.advance(target,hidden+64,[&](int i){return i<hidden?1:0;},unlimited?ScanBudget::unlimited():ScanBudget{});
     ++batches;inspected+=result.inspected;
     if(batches==1){firstVisible=scan.order.size();firstInspected=result.inspected;}
     if(!unlimited)assert(result.inspected<=32);
     assert(result.inspected || scan.exhausted || int(scan.order.size())>=target);
    } while(!once && !scan.exhausted && int(scan.order.size())<target);
    const auto end=Clock::now();
    for(size_t i=0;i<scan.order.size();++i)assert(scan.order[i]==hidden+int(i));
    assert(once || int(scan.order.size())==target);
    if(!once)assert(inspected==size_t(hidden+target));
    checksum+=scan.cursor+scan.order.size()+batches;
    if(trial>=20){const double us=std::chrono::duration<double,std::micro>(end-start).count();
     samples<<(trial-20)<<','<<path<<','<<hidden<<','<<us<<','<<inspected<<','<<batches<<'\n';
     elapsed.push_back(us);batchesMin=std::min(batchesMin,batches);batchesMax=std::max(batchesMax,batches);}
    totalInspected=inspected;
   }
   std::sort(elapsed.begin(),elapsed.end());
   std::cout<<hidden<<','<<path<<','<<target<<','<<firstVisible<<','<<firstInspected<<','<<totalInspected<<','<<batchesMin<<','<<batchesMax<<','<<elapsed[250]<<','<<elapsed[475]<<",501\n";
  }
 }
 // Full candidate order and existing order.size() >= 32 boundary stay intact.
 for(int hidden:{0,10,1000}) {
  CandidateScan scan;const int lowered=100;const int total=hidden+lowered+64;
  while(!scan.exhausted)scan.advance(total+1,total,[&](int i){return i<hidden?1:i<hidden+lowered?2:0;});
  std::vector<int> expected;
  for(int i=hidden+lowered;i<total;++i){expected.push_back(i);if(i==hidden+lowered+31)for(int j=hidden;j<hidden+lowered;++j)expected.push_back(j);}
  assert(scan.order==expected);
 }
 samples.close();if(!samples) return 3;
 std::cerr<<"PASS prefix indices, visible targets, complete order and order_size_32_boundary; checksum="<<checksum<<'\n';
}
