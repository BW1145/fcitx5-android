#!/usr/bin/env python3
"""Compile the actual injected method against a counting API, not a copied implementation."""
import ast
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
module = ast.parse((root / 'scripts/patches/fcitx5_rime.py').read_text())
method = next(node.value for node in ast.walk(module)
              if isinstance(node, ast.Constant) and isinstance(node.value, str)
              and 'std::vector<std::string> personalContext(' in node.value)
method = method[method.index('    std::vector<std::string> personalContext('):
                method.index('    FCITX_ADDON_EXPORT_FUNCTION(RimeEngine, personalContext);')]
source = r'''
#include <cassert>
#include <string>
#include <vector>
#include <iostream>
struct InputContext {};
struct RimeStatus { const char *schema_id=nullptr; };
#define RIME_STRUCT(type, name) type name{}
struct State {
 int id=1, writes=0;
 int session(bool create) { assert(!create); return id; }
 std::string currentSchema() { ++writes; return "wrong-mutating-path"; }
};
struct Api {
 bool valid=true, noLearning=false, ascii=false, nullSchema=false, nullInput=false;
 int reads=0, frees=0, inputs=0, writes=0, recomposes=0;
 std::string schema="rime_ice", input="shi";
 bool get_status(int id,RimeStatus *s) { assert(id); ++reads;
  if(!valid)return false;
  s->schema_id=nullSchema?nullptr:schema.c_str();return true; }
 void free_status(RimeStatus *s) { ++frees;s->schema_id=nullptr; }
 const char *get_input(int id) { assert(id);++inputs;return nullInput?nullptr:input.c_str(); }
 void set_option(int,const char*,bool v) { noLearning=v;++writes;++recomposes; }
};
struct Engine {
 State storage; State *active=&storage; Api api; Api *api_=&api;
 State *state(InputContext*) { return active; }
METHOD
};
int main() {
 Engine e;InputContext ic;
 for(bool privateMode:{false,true})for(bool asciiMode:{false,true}) {
  e.api.noLearning=privateMode;e.api.ascii=asciiMode;
  for(int i=0;i<100;++i) {
   auto v=e.personalContext(&ic);assert((v==std::vector<std::string>{"rime_ice","shi"}));
   assert(e.api.noLearning==privateMode && e.api.ascii==asciiMode);
  }
 }
 assert(e.api.reads==400 && e.api.frees==400 && e.api.inputs==400);
 assert(e.api.writes==0 && e.api.recomposes==0 && e.storage.writes==0);
 e.api.schema=std::string(100000,'s');assert(e.personalContext(&ic)[0]==e.api.schema);
 e.api.nullSchema=true;e.api.nullInput=true;
 assert((e.personalContext(&ic)==std::vector<std::string>{"",""}));
 int reads=e.api.reads,freed=e.api.frees,inputs=e.api.inputs;
 e.active=nullptr;assert(e.personalContext(&ic).empty());assert(e.api.reads==reads);
 e.active=&e.storage;e.storage.id=0;assert(e.personalContext(&ic).empty());assert(e.api.reads==reads);
 e.storage.id=1;e.api.valid=false;assert(e.personalContext(&ic).empty());
 assert(e.api.reads==reads+1 && e.api.frees==freed && e.api.inputs==inputs);
 std::cout << "PASS pure context read: 400 mode-preserving reads, no option writes/recompose calls, long schema, null values, absent/expired session\n";
}
'''.replace('METHOD', method)
with tempfile.TemporaryDirectory(prefix='rime-context-') as temp:
    path = Path(temp)
    (path/'test.cpp').write_text(source)
    subprocess.run([os.environ.get('CXX','g++'), '-std=c++20', '-Wall', '-Wextra',
                    str(path/'test.cpp'), '-o', str(path/'test')], check=True)
    subprocess.run([str(path/'test')], check=True)
