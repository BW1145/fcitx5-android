// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <algorithm>
#include <fstream>
#include <filesystem>
#include <map>
#include <vector>
#include <unordered_map>
#include <sstream>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <sys/stat.h>
#include <fcitx-utils/utf8.h>

namespace fcitx {
inline std::string preferenceHex(const std::string &s) {
    static const char *digits="0123456789abcdef";
    std::string result;
    result.reserve(s.size()*2);
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
    using Rules = std::map<std::string,int>;
    static Rules read(const std::filesystem::path &path) {
        Rules rules;
        std::ifstream input(path);std::string line;
        while(std::getline(input,line)) {
            if (!line.empty() && line.back()=='\r') line.pop_back();
            if (line.empty()) continue;
            if (std::count(line.begin(),line.end(),'\t')!=3)
                throw std::runtime_error("Invalid candidate preference row");
            std::vector<std::string> fields;
            std::istringstream row(line);std::string field;
            while (std::getline(row,field,'\t')) fields.push_back(field);
            if (fields.size()!=4 || (fields[3]!="hide" && fields[3]!="lower"))
                throw std::runtime_error("Invalid candidate preference row");
            for (size_t i=0;i<3;++i) {
                const auto &hex=fields[i];std::string decoded;
                if (hex.size()%2) throw std::runtime_error("Invalid candidate preference encoding");
                for (size_t j=0;j<hex.size();j+=2) {
                    if (!std::isxdigit(static_cast<unsigned char>(hex[j])) || !std::isxdigit(static_cast<unsigned char>(hex[j+1])))
                        throw std::runtime_error("Invalid candidate preference encoding");
                    decoded += static_cast<char>(std::stoi(hex.substr(j,2),nullptr,16));
                }
                if (!utf8::validate(decoded)) throw std::runtime_error("Invalid candidate preference text");
                fields[i]=preferenceHex(decoded);
            }
            rules[fields[0]+"\t"+fields[1]+"\t"+fields[2]]=fields[3]=="hide"?1:2;
        }
        if (input.bad() || (std::filesystem::exists(path) && !input.eof()))
            throw std::runtime_error("Unable to read candidate preferences");
        return rules;
    }
    explicit CandidatePreferences(std::filesystem::path path) : path_(std::move(path)) {}
    // All frontend contexts run on the Fcitx thread and share the same file cache.
    bool load(bool force=false) {
        auto version=stamp();
        auto &value=cache();
        if(!force && value.loaded && value.version==version) return false;
        auto rules=read(path_);
        value.replaceRules(std::move(rules));value.version=version;value.loaded=true;
        return true;
    }
    bool empty() const { return cache().rules.empty(); }
    int mode(const std::string &scope,const std::string &word) const {
        const auto &scopes=cache().scopes;
        const auto found=scopes.find(scope);
        if (found==scopes.end()) return 0;
        const auto entry=found->second.find(word);
        return entry==found->second.end()?0:entry->second;
    }
    bool hasWord(const std::string &scope,const std::string &word) const {
        const auto &scopes=cache().scopes;
        const auto found=scopes.find(scope);
        return found!=scopes.end() && found->second.find(word)!=found->second.end();
    }
    bool hasScope(const std::string &scope) const {
        const auto prefix=scope+"\t";
        const auto &rules=cache().rules;
        auto found=rules.lower_bound(prefix);
        return found!=rules.end() && found->first.compare(0,prefix.size(),prefix)==0;
    }
    void set(const std::string &scope,const std::string &word,int mode) {
        load(true);auto rules=cache().rules;rules[scope+"\t"+preferenceHex(word)]=mode;
        write(std::move(rules));
    }
    void merge(const Rules &other) {
        load(true);auto rules=cache().rules;
        rules.insert(other.begin(),other.end()); // Current choices win conflicts.
        write(std::move(rules));
    }
    void migrateScope(const std::string &from,const std::string &to) {
        if (!hasScope(from)) return;
        auto rules=cache().rules;
        const auto prefix=from+"\t";
        for (auto it=rules.lower_bound(prefix);it!=rules.end() && it->first.compare(0,prefix.size(),prefix)==0;) {
            rules.emplace(to+it->first.substr(from.size()),it->second);
            it=rules.erase(it);
        }
        write(std::move(rules));
    }
    void migrateWord(const std::string &from,const std::string &to,const std::string &word) {
        migrateWords(from,to,{word});
    }
    void migrateWords(const std::string &from,const std::string &to,const std::vector<std::string> &words) {
        const auto &current=cache().rules;
        if (std::none_of(words.begin(),words.end(),[&](const auto &word) {
            return current.find(from+"\t"+preferenceHex(word))!=current.end();
        })) return;
        auto rules=current;
        bool changed=false;
        for (const auto &word:words) {
            const auto suffix="\t"+preferenceHex(word);
            const auto found=rules.find(from+suffix);
            if (found==rules.end()) continue;
            rules.emplace(to+suffix,found->second);
            rules.erase(found);
            changed=true;
        }
        if (changed) write(std::move(rules));
    }

private:
    void write(Rules rules) {
        // Allocate the complete replacement before changing the on-disk file.
        Cache replacement;
        replacement.replaceRules(std::move(rules));
        const auto target=path_;std::filesystem::create_directories(target.parent_path());
        auto temp=target;temp+=".tmp";
        std::ofstream output(temp,std::ios::trunc);
        for(const auto &[key,mode]:replacement.rules) output<<key<<'\t'<<(mode==1?"hide":"lower")<<'\n';
        output.close();
        if(!output) throw std::runtime_error("Unable to save candidate preferences");
        std::filesystem::rename(temp,target);
        replacement.loaded=true;replacement.version=stamp();
        cache()=std::move(replacement);
    }
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
    struct Cache {
        bool loaded=false;
        Stamp version;
        Rules rules;
        std::unordered_map<std::string,std::unordered_map<std::string,int>> scopes;
        void replaceRules(Rules replacement) {
            decltype(scopes) index;
            // read() normalizes valid rows, and set() hex-encodes words. Build
            // the decoded lookup only at a file load/save boundary, never on
            // the first key querying a potentially very large scope.
            for (const auto &[key,mode]:replacement) {
                const auto split=key.rfind('\t');
                if (split==std::string::npos) continue;
                const auto hex=key.substr(split+1);
                // Arbitrary merge callers must not make an invalid key match
                // a valid word, nor cause an out-of-bounds decode.
                if (hex.size()%2 || hex.find_first_not_of("0123456789abcdef")!=std::string::npos) continue;
                std::string text;text.reserve(hex.size()/2);
                for (size_t i=0;i<hex.size();i+=2) {
                    auto nibble=[](char c) { return c<='9' ? c-'0' : c-'a'+10; };
                    text+=static_cast<char>((nibble(hex[i])<<4)|nibble(hex[i+1]));
                }
                index[key.substr(0,split)].emplace(std::move(text),mode);
            }
            rules=std::move(replacement);
            scopes=std::move(index);
        }
    };
    std::filesystem::path path_;
    mutable Cache *cached_=nullptr;
    Cache &cache() const {
        // std::map keeps element addresses stable as other contexts add paths.
        static std::map<std::filesystem::path,Cache> files;
        if (!cached_) cached_=&files[path_];
        return *cached_;
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
