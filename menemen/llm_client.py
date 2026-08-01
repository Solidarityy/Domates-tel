"""
Calls the Claude Messages API directly via httpx — no Rust-compiled dependencies.
Pure-Python replacement for the anthropic SDK, compatible with Termux on Android.
"""
from __future__ import annotations

import json
import logging
from typing import Any

import httpx

from .action_schemas import MENEMEN_TOOLS, AnyAction
from .config import config

log = logging.getLogger(__name__)

_API_URL = "https://api.anthropic.com/v1/messages"
_API_VERSION = "2023-06-01"

SYSTEM_PROMPT = """You are MENEMEN, the brain of a mobile automation agent running on Android.

Your job is to complete the user's task by issuing one tool call at a time.
After each action you will receive the updated screen state.
Think step by step but ACT immediately — do not explain your reasoning in text.

Rules:
- Prefer node IDs over coordinates when available.
- If the screen hasn't changed after an action, try a different approach.
- Never ask the user a clarifying question; make your best guess and proceed.
- If you cannot complete the task after several attempts, call done() with an honest failure summary.
- Always call done() when the task is finished, never leave the loop open-ended.
"""


class MenemenLLM:
    def __init__(self) -> None:
        self._messages: list[dict] = []
        self._http = httpx.Client(
            timeout=60.0,
            headers={
                "x-api-key": config.anthropic_api_key,
                "anthropic-version": _API_VERSION,
                "content-type": "application/json",
            },
        )

    def reset(self) -> None:
        self._messages = []

    def add_screen_state(self, state_json: str, screenshot_b64: str | None = None) -> None:
        """Append an observation (screen state) to the conversation."""
        content: list[Any] = [
            {
                "type": "text",
                "text": f"<screen_state>\n{state_json}\n</screen_state>",
            }
        ]
        if screenshot_b64:
            content.append(
                {
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": "image/png",
                        "data": screenshot_b64,
                    },
                }
            )
        self._messages.append({"role": "user", "content": content})

    def add_tool_result(self, tool_use_id: str, result: str) -> None:
        """Append the result of a previous tool call."""
        self._messages.append(
            {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": tool_use_id,
                        "content": result,
                    }
                ],
            }
        )

    def next_action(self, task: str) -> tuple[str, AnyAction]:
        """
        Run one inference step.
        Returns (tool_use_id, action_dict).
        Raises RuntimeError if Claude doesn't call a tool.
        """
        if not self._messages:
            self._messages.append(
                {"role": "user", "content": f"TASK: {task}"}
            )

        payload = {
            "model": config.claude_model,
            "max_tokens": 1024,
            "system": SYSTEM_PROMPT,
            "tools": MENEMEN_TOOLS,
            "tool_choice": {"type": "any"},
            "messages": self._messages,
        }

        resp = self._http.post(_API_URL, content=json.dumps(payload))

        if resp.status_code != 200:
            raise RuntimeError(
                f"Claude API error {resp.status_code}: {resp.text[:400]}"
            )

        data = resp.json()
        log.debug("LLM stop_reason=%s", data.get("stop_reason"))

        # Find the tool_use block in the response content list
        content_blocks: list[dict] = data.get("content", [])
        tool_block = next(
            (b for b in content_blocks if b.get("type") == "tool_use"),
            None,
        )

        if tool_block is None:
            text = " ".join(
                b.get("text", "") for b in content_blocks if b.get("type") == "text"
            )
            raise RuntimeError(f"No tool call from LLM. Text: {text[:200]}")

        # Append assistant turn to history (raw dicts — no SDK objects)
        self._messages.append({"role": "assistant", "content": content_blocks})

        action: AnyAction = {"type": tool_block["name"], **tool_block["input"]}
        log.info("Action: %s", json.dumps(action, ensure_ascii=False))
        return tool_block["id"], action
