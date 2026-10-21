// SPDX-License-Identifier: LGPL-2.1-or-later
// Host harness: real LevelDB storage; Rime's Levers calls are controlled doubles.
#include "../app/src/main/cpp/personal-rime-dictionary.h"
#include <cassert>
#include <iostream>

using namespace fcitx;
using namespace fcitx::rime::personal;

namespace {
fs::path root, syncDirectory;
std::string restored;
bool failMerge=false;
RimeLeversApi levers{};
RimeModule module{};

std::string read(const fs::path &file) {
    std::ifstream input(file);
    return {std::istreambuf_iterator<char>(input),std::istreambuf_iterator<char>()};
}
void write(const fs::path &file,const std::string &text) {
    fs::create_directories(file.parent_path());
    std::ofstream(file)<<text;
}
std::unique_ptr<leveldb::DB> openDb() {
    leveldb::DB *raw=nullptr;
    leveldb::Options options;options.create_if_missing=true;
    assert(leveldb::DB::Open(options,(root/"test.userdb").string(),&raw).ok());
    return std::unique_ptr<leveldb::DB>(raw);
}
void put(const std::string &key,const std::string &value) {
    auto db=openDb();
    assert(db->Put(leveldb::WriteOptions{},key,value).ok());
}
std::string value(const std::string &key) {
    auto db=openDb();
    std::unique_ptr<leveldb::Iterator> it(db->NewIterator(leveldb::ReadOptions{}));
    it->Seek(key);
    assert(it->Valid() && it->key().ToString()==key && it->status().ok());
    return it->value().ToString();
}
RimeCustomApi *getLevers() { return reinterpret_cast<RimeCustomApi *>(&levers); }
RimeModule *findModule(const char *) { return &module; }
Bool iteratorInit(RimeUserDictIterator *iter) { iter->i=0;return True; }
void iteratorDestroy(RimeUserDictIterator *) {}
const char *nextDictionary(RimeUserDictIterator *iter) { return iter->i++==0 ? "test" : nullptr; }
int exportDictionary(const char *,const char *file) {
    write(file,"学习验证\txue xi yan zheng\t7\n");return 1;
}
Bool restoreDictionary(const char *file) {
    restored=read(file);
    if (failMerge) {
        put("xue xi yan zheng \t学习验证","c=99 d=99 t=99");
        return False;
    }
    return True;
}
void getSyncDirectory(char *output,size_t size) {
    const auto path=syncDirectory.string();
    assert(path.size()<size);
    std::copy(path.begin(),path.end(),output);output[path.size()]='\0';
}
}

int main(int argc,char **argv) {
    if (argc!=2) return 2;
    const fs::path work=argv[1];fs::remove_all(work);
    root=work/"data/rime";syncDirectory=work/"sync";
    fs::create_directories(root);
    const std::string learned="c=7 d=5 t=1999999999";
    const std::string deleted="c=-6 d=2 t=1999999999";
    put("\1/db_name","test");put("\1/db_type","userdb");put("\1/tick","2000000000");
    put("xue xi yan zheng \t学习验证",learned);
    put("shan chu yan zheng \t删除验证",deleted);
    write(syncDirectory/"device/test.userdb.txt","old sync");
    const auto scope=preferenceHex("rime:test")+"\t"+preferenceHex("xuexi");
    const auto prefs=root.parent_path()/"candidate-preferences.tsv";
    CandidatePreferences preferences(prefs);preferences.set(scope,"学习验证",2);

    levers.user_dict_iterator_init=iteratorInit;
    levers.user_dict_iterator_destroy=iteratorDestroy;
    levers.next_user_dict=nextDictionary;
    levers.export_user_dict=exportDictionary;
    levers.restore_user_dict=restoreDictionary;
    module.get_api=getLevers;
    RimeApi api{};api.find_module=findModule;api.get_sync_dir_s=getSyncDirectory;
    const auto exchange=root/"personal-exchange";
    const auto snapshot=root.parent_path()/"input-history/100";
    operate(&api,"backup/100",root);
    assert(read(exchange/"status.txt")=="ok");
    const auto uniform=snapshot/"learning/test.userdb.txt";
    const auto records=read(uniform);
    assert(records.find("#@/tick\t2000000000\n")!=std::string::npos);
    assert(records.find("学习验证\t"+learned+"\n")!=std::string::npos);
    assert(records.find("删除验证\t"+deleted+"\n")!=std::string::npos);
    validateLearningSnapshot(uniform,"test");

    // New snapshots use the full learning file, preserving current rule conflicts.
    write(snapshot/"candidate-preferences.tsv",scope+"\t"+preferenceHex("学习验证")+"\thide\n"+
          scope+"\t"+preferenceHex("另一词")+"\thide\n");
    operate(&api,"merge/100",root);
    assert(read(exchange/"status.txt")=="ok" && restored==records);
    preferences.load(true);
    assert(preferences.mode(scope,"学习验证")==2 && preferences.mode(scope,"另一词")==1);

    // Legacy snapshots reconstruct exactly the same complete records from db/.
    fs::remove_all(snapshot/"learning");
    operate(&api,"merge/100",root);
    assert(read(exchange/"status.txt")=="ok" && restored==records);
    assert(!fs::exists(exchange/"merge-db") && !fs::exists(exchange/"merge.userdb.txt"));

    // A failed native merge reports failure. Restoring the automatic backup
    // uses the real raw DB and sync replacement path, undoing partial mutations.
    failMerge=true;
    operate(&api,"merge/100",root);
    assert(read(exchange/"status.txt").find("Unable to merge dictionary")!=std::string::npos);
    assert(value("xue xi yan zheng \t学习验证")!=learned);
    write(syncDirectory/"device/test.userdb.txt","new sync");
    write(syncDirectory/"other/newer.userdb.txt","newer sync");
    operate(&api,"restore/100",root);
    assert(read(exchange/"status.txt")=="ok");
    assert(value("xue xi yan zheng \t学习验证")==learned);
    assert(value("shan chu yan zheng \t删除验证")==deleted);
    assert(read(syncDirectory/"device/test.userdb.txt")=="old sync");
    assert(!fs::exists(syncDirectory/"other/newer.userdb.txt"));
    preferences.load(true);assert(preferences.mode(scope,"学习验证")==1);

    fs::remove_all(work);
    std::cout<<"Full learning serialization, legacy merge routing, preferences, and rollback storage checks passed\n";
}
