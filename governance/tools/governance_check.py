#!/usr/bin/env python3
"""治理完整性检查（governance integrity check）.

Run from anywhere:
    python governance/tools/governance_check.py
    python governance/tools/governance_check.py --strict-gate
    python governance/tools/governance_check.py --verify-hashes

Exit codes:
    0  integrity OK (release gate may still be BLOCKED)
    1  integrity violation
    2  --strict-gate and the release gate is blocked
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIRTY = Path(
    os.environ.get(
        "GOVERNANCE_DIRTY",
        str(Path.home() / "AppData" / "Roaming" / "reasonix" / "global-workspace" / "1DM+"),
    )
)

ID_RE = re.compile(
    r"\b(EV-[A-Z0-9]+-\d{3}|(?:REQ|INV|ERR|PRV|ACC|CONST|GAP|INC|CAND|LEG|REV|DEC|CTRL|OBS)-(?:[A-Z]+-)?\d{3})\b"
)
VALID_GAP = {"OPEN", "TRIAGED", "RESOLVED", "ACCEPTED", "DEFERRED", "BLOCKED"}
SEVERITIES = {"Critical", "High", "Medium", "Low"}

errors: list[str] = []
warns: list[str] = []
gate_blockers: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warns.append(msg)


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ""


def check_dirty() -> None:
    specs = DIRTY / "specs"
    if not specs.is_dir():
        fail(f"dirty specs directory missing: {specs}")
        return
    if (specs / "ui-spec.md").exists():
        fail("dirty workspace still contains implementation document specs/ui-spec.md")

    candidate = specs / "behavior-spec.md"
    text = read(candidate)
    if "CANDIDATE-ID: CAND-DL-001" not in text:
        fail("behavior-spec.md is missing CANDIDATE-ID: CAND-DL-001")
    if "LEGAL-STATUS: NOT_REVIEWED" not in text:
        fail("behavior-spec.md must state LEGAL-STATUS: NOT_REVIEWED")

    raw_files = [
        "adblock-engine.md",
        "file-types-and-folders.md",
        "ui-download-confirm.md",
        "ui-progressview.md",
    ]
    for name in raw_files:
        p = specs / name
        body = read(p)
        if not body:
            fail(f"missing dirty raw document: {name}")
        elif "CLASSIFICATION: DIRTY-RAW" not in body:
            fail(f"{name} is missing CLASSIFICATION: DIRTY-RAW")

    forbidden = [
        r"D:\1DM+",
        r"D:/1DM+",
        "app.freedm",
        "FreeDM",
        "logic-spec",
        "implementation-guide",
        "acceptance.md",
        "known-pitfalls",
    ]
    for p in sorted(specs.glob("*.md")):
        if p.name == "README.md":
            continue
        body = read(p)
        for needle in forbidden:
            if needle in body:
                fail(f"dirty spec {p.name} references clean-side artifact: {needle!r}")


def check_clean_artifacts() -> None:
    excluded = {".git", "build", ".gradle", ".kotlin", ".idea", ".gitignore"}
    for p in ROOT.rglob("*"):
        if any(part in excluded for part in p.parts):
            continue
        if not p.is_file():
            continue
        suffix = p.suffix.lower()
        if suffix in {".dex", ".smali"}:
            fail(f"target artifact inside clean workspace: {p.relative_to(ROOT)}")
        elif suffix == ".apk":
            # Local build outputs are allowed; target APKs are not.
            if any(part in {"release", "build", "outputs"} for part in p.parts):
                continue
            if re.search(r"(1dm|idm|internet[._-]?download)", p.name, re.I):
                fail(f"target APK inside clean workspace: {p.relative_to(ROOT)}")
            else:
                warn(f"untracked APK build output in workspace: {p.relative_to(ROOT)}")

    patterns = [
        r"idm\.internet\.download\.manager",
        r"UZ\.m\d+",
        r"R\.id\.",
        r"download_option\.xml",
        r"download_row_new\.xml",
        r"download_link_row\.xml",
        r"i/KI\.java",
        r"ProgressView",
    ]
    roots = [ROOT / "app" / "src", ROOT / "docs" / "spec", ROOT / "docs" / "acceptance.md"]
    for base in roots:
        if not base.exists():
            continue
        files = [base] if base.is_file() else list(base.rglob("*"))
        for p in files:
            if not p.is_file() or p.suffix.lower() not in {".kt", ".java", ".xml", ".md"}:
                continue
            body = read(p)
            for pat in patterns:
                if re.search(pat, body):
                    fail(f"protected target expression {pat!r} in {p.relative_to(ROOT)}")

    # Candidate packages are DIRTY material and must not live in the Clean repo.
    candidate = ROOT / "governance" / "handoff" / "candidate"
    if candidate.exists():
        packages = [
            q for q in candidate.iterdir()
            if (q.is_dir() and q.name.startswith("CAND-")) or (q.is_file() and q.name.startswith("CAND-"))
        ]
        for q in packages:
            fail(f"candidate spec package inside clean workspace: {q.relative_to(ROOT)}")


def load_registry() -> set[str]:
    path = ROOT / "governance" / "registry" / "ids.csv"
    if not path.exists():
        fail("missing governance/registry/ids.csv")
        return set()
    ids: set[str] = set()
    with path.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            ident = row.get("id", "")
            if not ident:
                continue
            if ident in ids:
                fail(f"duplicate ID in registry: {ident}")
            ids.add(ident)
    return ids


def check_ids(registry: set[str]) -> None:
    sources = [
        ROOT / "docs" / "spec",
        ROOT / "docs" / "acceptance.md",
        ROOT / "governance" / "gaps" / "GAP-REGISTER.md",
        ROOT / "governance" / "audit" / "AUDIT-2026-10-23.md",
        ROOT / "governance" / "audit" / "findings.csv",
        ROOT / "governance" / "registry" / "evidence.csv",
    ]
    found: dict[str, str] = {}
    for src in sources:
        if not src.exists():
            continue
        files = [src] if src.is_file() else list(src.rglob("*.md"))
        for p in files:
            body = read(p)
            for m in ID_RE.finditer(body):
                ident = m.group(1)
                found.setdefault(ident, str(p.relative_to(ROOT)))
    unknown = sorted(set(found) - registry)
    for ident in unknown:
        fail(f"ID not registered: {ident} (first seen in {found[ident]})")

    acc = read(ROOT / "docs" / "acceptance.md")
    if not re.search(r"^### ACC-[A-Z]+-\d{3} · ", acc, re.M):
        fail("docs/acceptance.md has no ACC-* headings")
    if re.search(r"^### [AB]\d+ ", acc, re.M):
        fail("docs/acceptance.md still has un-ID'd A*/B* headings")
    ids = re.findall(r"^### (ACC-[A-Z]+-\d{3}) · ", acc, re.M)
    if len(ids) < 24:
        fail(f"acceptance has only {len(ids)} ACC headings; expected at least 24")
    for ident in ids:
        if ident not in registry:
            fail(f"acceptance ID not in registry: {ident}")
    if len(set(ids)) != len(ids):
        fail("duplicate ACC heading in acceptance")


def check_gaps() -> None:
    path = ROOT / "governance" / "gaps" / "GAP-REGISTER.md"
    if not path.exists():
        fail("missing gap register")
        return
    body = read(path)
    rows = re.findall(r"^\|\s*(GAP-\d{3})\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|", body, re.M)
    if not rows:
        fail("gap register has no GAP rows")
        return
    counts: dict[str, int] = {}
    seen: set[str] = set()
    for ident, sev, _title, _evidence, status in rows:
        sev = sev.strip()
        status = status.strip().strip("*").strip()
        if ident in seen:
            fail(f"duplicate gap ID: {ident}")
        seen.add(ident)
        if status not in VALID_GAP:
            fail(f"invalid gap status {status!r} for {ident}")
        if sev not in SEVERITIES:
            fail(f"invalid severity {sev!r} for {ident}")
        counts[status] = counts.get(status, 0) + 1
        # Single-developer mode: a disclosed Critical/High risk is not closed.
        # Keep DEFERRED high-risk items as release blockers so the gate cannot turn green.
        if status in {"BLOCKED", "DEFERRED"} and sev in {"Critical", "High"}:
            gate_blockers.append(f"{ident} ({sev}) {status}")
    print("GAP status counts:", ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))


def check_doc_classes() -> None:
    for name in ["requirements.md", "invariants.md", "errors.md", "privacy.md", "constants.md"]:
        body = read(ROOT / "docs" / "spec" / name)
        if "DOCUMENT-CLASS: NORMATIVE" not in body:
            fail(f"docs/spec/{name} is missing DOCUMENT-CLASS: NORMATIVE")
    acc = read(ROOT / "docs" / "acceptance.md")
    if "NORMATIVE ACCEPTANCE" not in acc:
        fail("acceptance.md is missing NORMATIVE ACCEPTANCE class")
    for name in ["logic-spec.md", "implementation-guide.md", "known-pitfalls.md", "ui-design.md"]:
        body = read(ROOT / "docs" / name)
        if "INFORMATIVE" not in body:
            fail(f"docs/{name} is missing INFORMATIVE class")
    for name in ["README.md", "HANDOFF.md"]:
        if "NOT_REVIEWED" not in read(ROOT / name):
            fail(f"{name} does not state the current legal status NOT_REVIEWED")


def check_legal_and_reviews() -> None:
    reviews = ROOT / "governance" / "reviews"
    legs = [p for p in reviews.glob("LEG-*.md")]
    revs = [p for p in reviews.glob("REV-*.md")]
    approved = ROOT / "governance" / "handoff" / "approved"
    approved_docs = [p for p in approved.glob("*") if p.suffix in {".md", ".json"} and p.name != "README.md"]
    if not legs:
        gate_blockers.append("no signed LEG-* legal review")
    if not revs:
        gate_blockers.append("no signed REV-* independent verification")
    if not approved_docs:
        gate_blockers.append("handoff/approved/ contains no approved spec")
    for p in revs:
        body = read(p)
        for field in ["not_author:", "read_source:", "read_design_docs:", "discussed_implementation:"]:
            if field not in body:
                fail(f"verification record {p.name} missing independence field {field}")


def check_evidence(verify_hashes: bool) -> None:
    path = ROOT / "governance" / "registry" / "evidence.csv"
    if not path.exists():
        warn("governance/registry/evidence.csv is local-only (ignored by git); evidence checks skipped")
        return
    with path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        fail("evidence registry is empty")
    seen: set[str] = set()
    for row in rows:
        ident = row["evidence_id"]
        if ident in seen:
            fail(f"duplicate evidence ID: {ident}")
        seen.add(ident)
        p = Path(row["path"])
        if not p.exists():
            fail(f"evidence path missing for {ident}: {p}")
            continue
        if not verify_hashes or row.get("type") == "tree":
            continue
        h = hashlib.sha256()
        with p.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                h.update(chunk)
        if h.hexdigest() != row["sha256"]:
            fail(f"hash mismatch for {ident}: {p}")


def check_gate_claims() -> None:
    text = "\n".join(
        read(ROOT / name) for name in ["README.md", "HANDOFF.md", "docs/README.md"]
    ) + "\n" + "\n".join(read(p) for p in (ROOT / "governance").rglob("*.md"))
    if "LEGAL-STATUS: NOT_REVIEWED" in text and re.search(r"LEGAL-STATUS:\s*APPROVED", text):
        fail("documents claim LEGAL-STATUS: APPROVED while another document says NOT_REVIEWED")
    if re.search(r"法律审查(结论|状态)[:：]\s*APPROVED", text) and "LEGAL-STATUS: NOT_REVIEWED" in text:
        fail("documents claim an approved legal review while status is NOT_REVIEWED")
    if "approved/" in text and not list((ROOT / "governance" / "handoff" / "approved").glob("*.md")):
        warn("documents mention approved/ but the approval package is empty")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict-gate", action="store_true", help="exit 2 when release gate is blocked")
    ap.add_argument("--verify-hashes", action="store_true", help="recompute file evidence hashes")
    args = ap.parse_args()

    check_dirty()
    check_clean_artifacts()
    registry = load_registry()
    check_ids(registry)
    check_gaps()
    check_doc_classes()
    check_legal_and_reviews()
    check_evidence(args.verify_hashes)
    check_gate_claims()

    for msg in warns:
        print(f"[WARN] {msg}")
    for msg in errors:
        print(f"[FAIL] {msg}")
    for msg in gate_blockers:
        print(f"[GATE] {msg}")

    print()
    print(f"INTEGRITY: {'PASS' if not errors else 'FAIL'} ({len(errors)} error(s), {len(warns)} warning(s))")
    print(f"GATE: {'BLOCKED' if gate_blockers else 'READY'} ({len(gate_blockers)} blocker(s))")

    if errors:
        return 1
    if args.strict_gate and gate_blockers:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
