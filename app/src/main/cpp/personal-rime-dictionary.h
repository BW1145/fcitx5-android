// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <rime_api.h>
#include <rime_levers_api.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <vector>
#include <memory>
#include <leveldb/db.h>
#include "androidfrontend/candidatepreferences.h"

namespace fcitx::rime {
namespace personal {
namespace fs=std::filesystem;
inline bool dictionary(const fs::path &path) {
    auto name=path.filename().string();
    return name.size()>7 && name.substr(name.size()-7)==".userdb";
}
inline void copyTree(const fs::path &from,const fs::path &to) {
    fs::create_directories(to.parent_path());
    fs::copy(from,to,fs::copy_options::recursive|fs::copy_options::overwrite_existing);
}
inline void syncFiles(const fs::path &from,const fs::path &to,bool remove=false) {
    if(!fs::exists(from)) return;
    std::vector<fs::path> files;
    for(const auto &item:fs::recursive_directory_iterator(from)) {
        auto name=item.path().filename().string();
        if(item.is_regular_file() && name.size()>11 && name.substr(name.size()-11)==".userdb.txt") files.push_back(item.path());
    }
    for(const auto &path:files) {
        if(remove) fs::remove(path);
        else copyTree(path,to/fs::relative(path,from));
    }
}
// Convert the saved LevelDB into Rime's uniform snapshot format. Keep the full
// values (c/d/t) and metadata; Rime's own restore API performs the actual merge.
inline void learningSnapshot(const fs::path &source,const fs::path &staging,const fs::path &output) {
    fs::remove_all(staging);copyTree(source,staging);
    leveldb::DB *raw=nullptr;
    const auto status=leveldb::DB::Open(leveldb::Options{},staging.string(),&raw);
    if(!status.ok()) throw std::runtime_error("Unable to open saved learning: "+status.ToString());
    std::unique_ptr<leveldb::DB> db(raw);
    std::unique_ptr<leveldb::Iterator> it(db->NewIterator(leveldb::ReadOptions{}));
    std::ofstream out(output);
    for(it->SeekToFirst();it->Valid();it->Next()) {
        auto key=it->key().ToString();auto value=it->value().ToString();
        if(!key.empty() && key[0]=='\x01') out<<"#@"<<key.substr(1)<<'\t'<<value<<'\n';
        else if(key>=" ") out<<key<<'\t'<<value<<'\n';
    }
    out.close();
    if(!it->status().ok() || !out) throw std::runtime_error("Unable to read saved learning");
}
inline void validateLearningSnapshot(const fs::path &source,const std::string &name) {
    std::ifstream input(source);std::string line,dbName,dbType;
    while(std::getline(input,line)) {
        if(!line.empty() && line.back()=='\r') line.pop_back();
        if(line.rfind("#@/db_name\t",0)==0) dbName=line.substr(11);
        if(line.rfind("#@/db_type\t",0)==0) dbType=line.substr(11);
    }
    if(!input.eof() || dbType!="userdb" || (dbName!=name && dbName!=name+".userdb"))
        throw std::runtime_error("Invalid learning snapshot metadata");
}
inline void operate(RimeApi *api,const std::string &operation,const fs::path &root) {
    const auto data=root.parent_path();
    const auto exchange=root/"personal-exchange";
    fs::create_directories(exchange);
    try {
        auto module=api->find_module("levers");
        if(!module || !module->get_api) throw std::runtime_error("Rime dictionary API is unavailable");
        auto levers=reinterpret_cast<RimeLeversApi *>(module->get_api());
        const auto exports=exchange/"exports";
        if(operation=="export" || operation.rfind("backup/",0)==0) {
            fs::remove_all(exports);fs::create_directories(exports);
            RimeUserDictIterator iter{};
            std::string failure;
            if(levers->user_dict_iterator_init(&iter)) {
                while(const char *name=levers->next_user_dict(&iter)) {
                    if(levers->export_user_dict(name,(exports/(std::string(name)+".txt")).c_str())<0) failure=name;
                }
                levers->user_dict_iterator_destroy(&iter);
            }
            if(!failure.empty()) throw std::runtime_error("Unable to export dictionary: "+failure);
        }
        if(operation=="import") {
            auto nameFile=exchange/"dictionary.txt";
            std::ifstream input(nameFile);std::string name;std::getline(input,name);
            if(name.empty() || name.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.")!=std::string::npos)
                throw std::runtime_error("Invalid dictionary name");
            if(levers->import_user_dict(name.c_str(),(exchange/"import.txt").c_str())<0)
                throw std::runtime_error("Unable to import user words");
        }
        auto slash=operation.find('/');
        if(slash!=std::string::npos) {
            auto action=operation.substr(0,slash),id=operation.substr(slash+1);
            if(id.empty() || id.find_first_not_of("0123456789-")!=std::string::npos) throw std::runtime_error("Invalid backup ID");
            auto snapshot=data/"input-history"/id;
            char syncBuffer[4096]{};api->get_sync_dir_s(syncBuffer,sizeof(syncBuffer));
            fs::path sync(syncBuffer);
            if(action=="backup") {
                if(fs::exists(snapshot/"db")) throw std::runtime_error("Backup already exists");
                fs::create_directories(snapshot/"db");
                fs::create_directories(snapshot/"learning");
                for(const auto &item:fs::directory_iterator(root)) {
                    if(!dictionary(item.path())) continue;
                    copyTree(item.path(),snapshot/"db"/item.path().filename());
                    learningSnapshot(item.path(),exchange/"backup-db",snapshot/"learning"/(item.path().filename().string()+".txt"));
                }
                fs::remove_all(exchange/"backup-db");
                copyTree(exports,snapshot/"words");
                if(!sync.empty()) syncFiles(sync,snapshot/"sync");
                auto prefs=data/"candidate-preferences.tsv";
                if(fs::exists(prefs)) copyTree(prefs,snapshot/"candidate-preferences.tsv");
            } else if(action=="restore") {
                if(!fs::is_directory(snapshot/"db")) throw std::runtime_error("Backup data is missing");
                CandidatePreferences::read(snapshot/"candidate-preferences.tsv");
                std::vector<fs::path> current;
                for(const auto &item:fs::directory_iterator(root)) if(dictionary(item.path())) current.push_back(item.path());
                for(const auto &path:current) fs::remove_all(path);
                for(const auto &item:fs::directory_iterator(snapshot/"db")) copyTree(item.path(),root/item.path().filename());
                // Prevent a later local sync from merging learning newer than this snapshot.
                if(!sync.empty()) { syncFiles(sync,{},true);syncFiles(snapshot/"sync",sync); }
                auto prefs=data/"candidate-preferences.tsv";
                if(fs::exists(snapshot/"candidate-preferences.tsv")) copyTree(snapshot/"candidate-preferences.tsv",prefs);
                else fs::remove(prefs);
            } else if(action=="merge") {
                if(!fs::is_directory(snapshot/"db")) throw std::runtime_error("Backup data is missing");
                const auto rules=CandidatePreferences::read(snapshot/"candidate-preferences.tsv");
                const auto staging=exchange/"merge-db";
                const auto uniform=exchange/"merge.userdb.txt";
                for(const auto &item:fs::directory_iterator(snapshot/"db")) {
                    if(!dictionary(item.path())) continue;
                    auto source=snapshot/"learning"/(item.path().filename().string()+".txt");
                    if(!fs::exists(source)) { learningSnapshot(item.path(),staging,uniform);source=uniform; }
                    validateLearningSnapshot(source,item.path().stem().string());
                    if(!levers->restore_user_dict(source.c_str()))
                        throw std::runtime_error("Unable to merge dictionary: "+item.path().filename().string());
                }
                fs::remove_all(staging);fs::remove(uniform);
                CandidatePreferences(data/"candidate-preferences.tsv").merge(rules);
            }
        }
        std::ofstream(exchange/"status.txt")<<"ok";
    } catch(const std::exception &error) {
        std::ofstream(exchange/"status.txt")<<error.what();
    }
}
}
}
