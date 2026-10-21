// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <algorithm>
#include <fstream>
#include <filesystem>
#include <map>
#include <sstream>
#include <fcitx-utils/standardpaths.h>

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
    void load() {
        rules_.clear();
        std::ifstream input(file());std::string line;
        while(std::getline(input,line)) {
            auto split=line.rfind('\t');
            if(split!=std::string::npos) rules_[line.substr(0,split)]=line.substr(split+1)=="hide"?1:2;
        }
    }
    int mode(const std::string &scope,const std::string &word) const {
        auto found=rules_.find(scope+"\t"+preferenceHex(word));
        return found==rules_.end()?0:found->second;
    }
    bool hasScope(const std::string &scope) const {
        const auto prefix=scope+"\t";
        auto found=rules_.lower_bound(prefix);
        return found!=rules_.end() && found->first.compare(0,prefix.size(),prefix)==0;
    }
    void set(const std::string &scope,const std::string &word,int mode) {
        load();rules_[scope+"\t"+preferenceHex(word)]=mode;
        const auto target=file();std::filesystem::create_directories(target.parent_path());
        auto temp=target;temp+=".tmp";
        std::ofstream output(temp,std::ios::trunc);
        for(const auto &[key,value]:rules_) output<<key<<'\t'<<(value==1?"hide":"lower")<<'\n';
        output.close();
        if(!output) throw std::runtime_error("Unable to save candidate preferences");
        std::filesystem::rename(temp,target);
    }
private:
    std::map<std::string,int> rules_;
    static std::filesystem::path file() {
        return StandardPaths::global().userDirectory(StandardPathsType::PkgData)/"candidate-preferences.tsv";
    }
};
}
