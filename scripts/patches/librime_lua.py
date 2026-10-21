def apply(source):
    # Android uses the existing static Lua build.
    lua = source / "CMakeLists.txt"
    text = lua.read_text(encoding="utf-8")
    start = text.index("  find_package(PkgConfig)")
    end = text.index("\nelse()", start)
    lua.write_text(text[:start] + "  find_package(Lua REQUIRED CONFIG)\n  set(LUA_TARGET ${LUA_LIBRARY})\n  include_directories(${LUA_INCLUDE_DIR})" + text[end:], encoding="utf-8")
