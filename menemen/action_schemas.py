"""
Action command schemas for DOMATES (execution layer).

Every action Menemen can send to Domates is defined here as a TypedDict
so both sides share a single source of truth.
"""
from __future__ import annotations
from typing import Literal, Union
from typing_extensions import TypedDict


class ClickAction(TypedDict):
    type: Literal["click"]
    # Prefer node_id; fall back to x/y when no node covers the target.
    node_id: str | None
    x: float | None
    y: float | None


class TypeAction(TypedDict):
    type: Literal["type"]
    text: str
    # If True, clears the focused field before typing
    clear_first: bool


class ScrollAction(TypedDict):
    type: Literal["scroll"]
    direction: Literal["up", "down", "left", "right"]
    # 0.0–1.0 fraction of screen; default 0.5
    amount: float
    node_id: str | None


class SwipeAction(TypedDict):
    type: Literal["swipe"]
    from_x: float
    from_y: float
    to_x: float
    to_y: float
    # Duration in ms
    duration_ms: int


class PressAction(TypedDict):
    type: Literal["press"]
    key: Literal["back", "home", "recents", "enter", "volume_up", "volume_down", "mute"]


class OpenAppAction(TypedDict):
    type: Literal["open_app"]
    package: str


class WaitAction(TypedDict):
    type: Literal["wait"]
    ms: int


class DoneAction(TypedDict):
    type: Literal["done"]
    result: str


class ErrorAction(TypedDict):
    type: Literal["error"]
    message: str


AnyAction = Union[
    ClickAction,
    TypeAction,
    ScrollAction,
    SwipeAction,
    PressAction,
    OpenAppAction,
    WaitAction,
    DoneAction,
    ErrorAction,
]


# Claude tool definitions (passed to the API)
MENEMEN_TOOLS = [
    {
        "name": "click",
        "description": (
            "Tap a UI element. Prefer node_id when available. "
            "Use x/y (normalised 0–1 fractions of screen) as fallback."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "node_id": {"type": "string", "description": "Accessibility node id"},
                "x": {"type": "number", "description": "Horizontal position 0–1"},
                "y": {"type": "number", "description": "Vertical position 0–1"},
            },
        },
    },
    {
        "name": "type",
        "description": "Type text into the currently focused field.",
        "input_schema": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
                "clear_first": {
                    "type": "boolean",
                    "description": "Clear existing text before typing",
                    "default": False,
                },
            },
            "required": ["text"],
        },
    },
    {
        "name": "scroll",
        "description": "Scroll the screen or a specific node.",
        "input_schema": {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
                "amount": {
                    "type": "number",
                    "description": "Fraction of screen height/width to scroll, 0–1",
                    "default": 0.5,
                },
                "node_id": {
                    "type": "string",
                    "description": "Scroll inside this node; omit for full-screen scroll",
                },
            },
            "required": ["direction"],
        },
    },
    {
        "name": "swipe",
        "description": "Perform a freeform swipe gesture.",
        "input_schema": {
            "type": "object",
            "properties": {
                "from_x": {"type": "number"},
                "from_y": {"type": "number"},
                "to_x": {"type": "number"},
                "to_y": {"type": "number"},
                "duration_ms": {"type": "integer", "default": 300},
            },
            "required": ["from_x", "from_y", "to_x", "to_y"],
        },
    },
    {
        "name": "press",
        "description": "Press a hardware or soft key.",
        "input_schema": {
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "enum": ["back", "home", "recents", "enter", "volume_up", "volume_down", "mute"],
                }
            },
            "required": ["key"],
        },
    },
    {
        "name": "open_app",
        "description": "Launch an app by its package name.",
        "input_schema": {
            "type": "object",
            "properties": {"package": {"type": "string"}},
            "required": ["package"],
        },
    },
    {
        "name": "wait",
        "description": "Pause execution for a given number of milliseconds.",
        "input_schema": {
            "type": "object",
            "properties": {"ms": {"type": "integer"}},
            "required": ["ms"],
        },
    },
    {
        "name": "done",
        "description": (
            "Signal that the task is complete. "
            "Include a concise result summary for the user."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"result": {"type": "string"}},
            "required": ["result"],
        },
    },
]
