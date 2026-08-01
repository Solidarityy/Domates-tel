"""
Converts the raw AccessibilityNodeInfo JSON tree sent by DOMATES into a
compact representation that minimises LLM token usage while preserving
all actionable information.
"""
from __future__ import annotations

import json
from typing import Any


# Node classes that are almost never meaningful for interaction
_SKIP_CLASSES = {
    "android.widget.Space",
    "android.view.ViewGroup",           # pure container
    "androidx.coordinatorlayout.widget.CoordinatorLayout",
    "androidx.constraintlayout.widget.ConstraintLayout",
    "android.widget.FrameLayout",
    "android.widget.LinearLayout",
    "android.widget.RelativeLayout",
}

_INTERACTIVE_CLASSES = {
    "android.widget.Button",
    "android.widget.ImageButton",
    "android.widget.EditText",
    "android.widget.CheckBox",
    "android.widget.RadioButton",
    "android.widget.Switch",
    "android.widget.ToggleButton",
    "android.widget.Spinner",
    "android.widget.SeekBar",
    "android.widget.RatingBar",
    "android.widget.TextView",         # can be clickable
    "android.widget.ImageView",        # can be clickable
}


def _is_leaf(node: dict) -> bool:
    return not node.get("children")


def _node_label(node: dict) -> str | None:
    """Return the best human-readable label for a node, or None."""
    for key in ("text", "contentDescription", "hintText"):
        val = node.get(key)
        if val and val.strip():
            return val.strip()
    return None


def _compress_node(node: dict, depth: int = 0) -> dict | None:
    """
    Recursively compress a node tree.
    Returns None for nodes that carry no useful information.
    """
    class_name: str = node.get("className", "")
    node_id: str = node.get("viewIdResourceName") or node.get("nodeId", "")
    clickable: bool = node.get("clickable", False)
    long_clickable: bool = node.get("longClickable", False)
    editable: bool = node.get("editable", False)
    scrollable: bool = node.get("scrollable", False)
    checkable: bool = node.get("checkable", False)
    checked: bool = node.get("checked", False)
    enabled: bool = node.get("enabled", True)
    label: str | None = _node_label(node)

    children_raw: list = node.get("children", [])
    children_compressed = []
    for child in children_raw:
        c = _compress_node(child, depth + 1)
        if c is not None:
            children_compressed.append(c)

    # Skip invisible or disabled nodes with no useful children
    if not enabled and not children_compressed:
        return None

    # Skip pure container classes with no label and no interactivity
    short_class = class_name.split(".")[-1] if "." in class_name else class_name
    is_container = class_name in _SKIP_CLASSES
    has_content = bool(label or clickable or editable or scrollable or checkable)

    if is_container and not has_content:
        # Flatten: promote children up one level
        if not children_compressed:
            return None
        if len(children_compressed) == 1:
            return children_compressed[0]
        # Multiple children: return a minimal wrapper
        return {"class": short_class, "children": children_compressed}

    out: dict[str, Any] = {"class": short_class}

    if node_id:
        out["id"] = node_id
    if label:
        out["label"] = label
    if clickable:
        out["clickable"] = True
    if long_clickable:
        out["longClickable"] = True
    if editable:
        out["editable"] = True
    if scrollable:
        out["scrollable"] = True
    if checkable:
        out["checkable"] = True
        out["checked"] = checked
    if not enabled:
        out["disabled"] = True

    # Bounds as normalised fractions (device sends absolute pixels + screen size)
    bounds = node.get("bounds")
    screen = node.get("screenSize")
    if bounds and screen:
        sw, sh = screen.get("width", 1), screen.get("height", 1)
        out["bounds"] = {
            "x": round(bounds["left"] / sw, 3),
            "y": round(bounds["top"] / sh, 3),
            "w": round((bounds["right"] - bounds["left"]) / sw, 3),
            "h": round((bounds["bottom"] - bounds["top"]) / sh, 3),
        }

    if children_compressed:
        out["children"] = children_compressed

    return out


def compress_tree(raw_tree: dict) -> str:
    """
    Entry point. Takes the full JSON dict from DOMATES and returns a
    compact JSON string suitable for insertion into the LLM prompt.
    """
    root = _compress_node(raw_tree)
    return json.dumps(root, ensure_ascii=False, separators=(",", ":"))


def count_nodes(tree: dict | None) -> int:
    if tree is None:
        return 0
    count = 1
    for child in tree.get("children", []):
        count += count_nodes(child)
    return count
