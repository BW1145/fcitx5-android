// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <algorithm>
#include <fstream>
#include <filesystem>
#include <map>
#include <sstream>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <sys/stat.h>

namespace fcitx {
inline std::string preferenceHex(const std::string &s) {
    static const char *digits="0123456789abcdef";
    std::string result;
    for(unsigned char c:s) { result+=digits[c>>4];result+=digits[c&15]; }
    return result;
}
inline std::string preferenceContext(const std::string &s) {
    std::string result;
    for(unsigned char c:s) {
        if(c>=128 || std::isalnum(c)) result+=static_cast<char>(std::tolower(c));
    }
    return result;
}
class CandidatePreferences {
public:
    explicit CandidatePreferences(std::filesystem::path path) : path_(std::move(path)) {}
    // All frontend contexts run on the Fcitx thread and share the same file cache.
    bool load(bool force=false) {
        auto version=stamp();
        auto &value=cache();
        if(!force && value.loaded && value.version==version) return false;
        std::map<std::string,int> rules;
        std::ifstream input(path_);std::string line;
        while(std::getline(input,line)) {
            auto split=line.rfind('\t');
            if(split!=std::string::npos) rules[line.substr(0,split)]=line.substr(split+1)=="hide"?1:2;
        }
        value.rules=std::move(rules);value.version=version;value.loaded=true;
        return true;
    }
    int mode(const std::string &scope,const std::string &word) const {
        const auto &rules=cache().rules;
        auto found=rules.find(scope+"\t"+preferenceHex(word));
        return found==rules.end()?0:found->second;
    }
    bool hasScope(const std::string &scope) const {
        const auto prefix=scope+"\t";
        const auto &rules=cache().rules;
        auto found=rules.lower_bound(prefix);
        return found!=rules.end() && found->first.compare(0,prefix.size(),prefix)==0;
    }
    void set(const std::string &scope,const std::string &word,int mode) {
        load(true);auto &value=cache();value.rules[scope+"\t"+preferenceHex(word)]=mode;
        const auto target=path_;std::filesystem::create_directories(target.parent_path());
        auto temp=target;temp+=".tmp";
        std::ofstream output(temp,std::ios::trunc);
        for(const auto &[key,mode]:value.rules) output<<key<<'\t'<<(mode==1?"hide":"lower")<<'\n';
        output.close();
        if(!output) throw std::runtime_error("Unable to save candidate preferences");
        std::filesystem::rename(temp,target);
        value.version=stamp();
    }
private:
    struct Stamp {
        bool exists=false;
        int64_t modified=0;
        int64_t nanos=0;
        uintmax_t size=0;
        uintmax_t inode=0;
        bool operator==(const Stamp &other) const {
            return exists==other.exists && modified==other.modified && nanos==other.nanos && size==other.size && inode==other.inode;
        }
    };
    struct Cache { bool loaded=false;Stamp version;std::map<std::string,int> rules; };
    std::filesystem::path path_;
    Cache &cache() const {
        static std::map<std::filesystem::path,Cache> files;
        return files[path_];
    }
    Stamp stamp() const {
        struct stat info{};
        if(::stat(path_.string().c_str(),&info)!=0) {
            if(errno==ENOENT) return {};
            throw std::system_error(errno,std::generic_category(),"Unable to inspect candidate preferences");
        }
        return {true,info.st_mtim.tv_sec,info.st_mtim.tv_nsec,static_cast<uintmax_t>(info.st_size),static_cast<uintmax_t>(info.st_ino)};
    }
};
}
