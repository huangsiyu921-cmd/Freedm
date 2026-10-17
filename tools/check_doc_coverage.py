"""Mechanical check: every tunable constant in the code must appear in the specs.

A stranger rebuilding from documents alone needs the exact numbers (timeouts,
limits, granularities, regexes). This script lists code constants, then checks
whether each is mentioned anywhere in the spec/doc set.
"""
import os
import re
import sys

SRC = sys.argv[1]
DOCS = sys.argv[2:]

# --- collect constants from Kotlin sources -----------------------------------
consts = []       # (name, value, file)
numbers = []      # (value, context, file)

const_re = re.compile(r"const val (\w+)\s*=\s*(.+)")
interesting_num = re.compile(
    r"(connectTimeout|readTimeout|Timeout)\s*=\s*([\d_]+)"
    r"|ByteArray\((\w+)\)"
    r"|delay\((\d+)\)"
    r"|Thread\.sleep\((.+)\)"
    r"|setLength"
    r"|bytes=([^\"\s]+)"
)

for root, _d, files in os.walk(SRC):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(root, fn)
        rel = os.path.relpath(path, SRC)
        for line in open(path, encoding="utf-8", errors="replace"):
            s = line.strip()
            m = const_re.search(s)
            if m:
                consts.append((m.group(1), m.group(2).strip().rstrip(";"), rel))
            for m in interesting_num.finditer(s):
                g = [x for x in m.groups() if x]
                if g:
                    numbers.append((m.group(0)[:70], s[:100], rel))

# --- load docs ---------------------------------------------------------------
doc_text = []
for d in DOCS:
    if os.path.isdir(d):
        for root, _dd, ff in os.walk(d):
            for f in ff:
                if f.endswith(".md") or f.endswith(".log"):
                    doc_text.append(open(os.path.join(root, f), encoding="utf-8").read())
    elif os.path.isfile(d):
        doc_text.append(open(d, encoding="utf-8").read())
blob = "\n".join(doc_text)

# --- report ------------------------------------------------------------------
print(f"docs loaded: {len(blob)} chars\n")
print("=== 常量：文档中是否出现 ===")
miss_const = 0
for name, value, rel in consts:
    ok_name = name in blob
    digits = re.findall(r"\d[\d_]*", value)
    ok_val = any(x.replace("_", "") in blob.replace("_", "").replace(",", "") for x in digits) if digits else True
    mark = "OK " if (ok_name or ok_val) else "MISS"
    if mark == "MISS":
        miss_const += 1
    print(f"  [{mark}] {name} = {value}   ({rel})")

print(f"\n常量遗漏: {miss_const}/{len(consts)}")

print("\n=== 超时/缓冲/延迟等数值上下文（抽样核对）===")
seen = set()
for ctx, line, rel in numbers:
    key = ctx
    if key in seen:
        continue
    seen.add(key)
    print(f"  {rel}: {line}")

print(f"\n共 {len(seen)} 条数值上下文，请人工确认上面每一条是否在 specs 里有对应描述。")
