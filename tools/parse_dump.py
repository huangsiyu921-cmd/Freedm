"""Find the tap point for a labelled, clickable node in a uiautomator dump.

Walks the tree; for a node whose text matches, the nearest clickable ancestor
(or itself) supplies the bounds to tap.
"""
import sys
import xml.etree.ElementTree as ET

target = sys.argv[1]
path = sys.argv[2]

tree = ET.parse(path)
root = tree.getroot()


def bounds_of(node):
    b = node.get("bounds", "")
    # "[x1,y1][x2,y2]"
    try:
        left, rest = b.split("][")
        x1, y1 = left.lstrip("[").split(",")
        x2, y2 = rest.rstrip("]").split(",")
        return int(x1), int(y1), int(x2), int(y2)
    except Exception:
        return None


def search(node, ancestors):
    text = node.get("text", "")
    desc = node.get("content-desc", "")
    if target in text or target in desc:
        chain = list(reversed(ancestors)) + [node]
        for cand in chain:
            if cand.get("clickable") == "true":
                bb = bounds_of(cand)
                if bb and bb[2] > bb[0] and bb[3] > bb[1]:
                    cx = (bb[0] + bb[2]) // 2
                    cy = (bb[1] + bb[3]) // 2
                    print(f"{cx} {cy}")
                    return True
        bb = bounds_of(node)
        if bb and bb[2] > bb[0]:
            print(f"{(bb[0] + bb[2]) // 2} {(bb[1] + bb[3]) // 2}")
            return True
    for child in node:
        if search(child, ancestors + [node]):
            return True
    return False


if not search(root, []):
    sys.exit(f"not found: {target}")
