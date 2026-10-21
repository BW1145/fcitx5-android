def replace(path, old, new):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise ValueError(f"Source changed: {path.name}")
    path.write_text(text.replace(old, new), encoding="utf-8", newline="\n")
