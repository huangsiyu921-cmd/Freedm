"""Function-level coverage: every code function should be described in the docs.

Complements the constant check. Reports functions never mentioned anywhere in
the doc set, which are exactly the places a re-implementer must guess.
"""
import os
import re
import sys

SRC = sys.argv[1]
DOCS = sys.argv[2:]

func_re = re.compile(r"^\s{0,8}(?:public |private |internal |suspend |override |@\w+\s+)*fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(")

funcs = []
for root, _d, files in os.walk(SRC):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(root, fn)
        rel = os.path.relpath(path, SRC)
        for line in open(path, encoding="utf-8", errors="replace"):
            m = func_re.match(line)
            if m:
                funcs.append((m.group(1), rel))

blob = []
for d in DOCS:
    if os.path.isdir(d):
        for root, _dd, ff in os.walk(d):
            for f in ff:
                if f.endswith(".md"):
                    blob.append(open(os.path.join(root, f), encoding="utf-8").read())
    elif os.path.isfile(d):
        blob.append(open(d, encoding="utf-8").read())
text = "\n".join(blob)

# Functions that don't need their own spec entry (trivial/UI plumbing).
TRIVIAL = {
    "toString", "equals", "hashCode", "onCreate", "onDestroy", "onResume", "onPause",
    "onStartCommand", "onBind", "onNewIntent", "onConfigure", "onUpgrade",
    "onAvailable", "start", "stop", "setContent", "get", "set", "invoke",
    "consumeIntent", "guessMime", "providerUri", "kindOf", "normalize",
}

seen = set()
missing = []
for name, rel in funcs:
    if name in seen:
        continue
    seen.add(name)
    if name in TRIVIAL:
        continue
    if name in text:
        continue
    missing.append((name, rel))

print(f"函数总数（去重）: {len(seen)}")
print(f"文档未提及: {len(missing)}\n")
for name, rel in sorted(missing):
    print(f"  MISS  {name:28s} ({rel})")
