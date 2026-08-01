"""
Thin wrapper around the Anthropic SDK that drives the ReAct tool-use loop.
"""
from __future__ import annotations

import json
import logging
from typing import Any

import anthropic

from .action_schemas import MENEMEN_TOOLS, AnyAction
from .config import config

log = logging.getLogger(__name__)

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
        self._client = anthropic.Anthropic(api_key=config.anthropic_api_key)
        self._messages: list[dict] = []

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
        # On the very first call, prepend the task as the first user message.
        if not self._messages:
            self._messages.append(
                {"role": "user", "content": f"TASK: {task}"}
            )

        response = self._client.messages.create(
            model=config.claude_model,
            max_tokens=1024,
            system=SYSTEM_PROMPT,
            tools=MENEMEN_TOOLS,
            tool_choice={"type": "any"},  # force a tool call every turn
            messages=self._messages,
        )

        log.debug("LLM stop_reason=%s", response.stop_reason)

        # Extract the tool use block
        tool_block = next(
            (b for b in response.content if b.type == "tool_use"),
            None,
        )

        if tool_block is None:
            # Should not happen with tool_choice=any, but guard defensively
            text = " ".join(
                b.text for b in response.content if hasattr(b, "text")
            )
            raise RuntimeError(f"No tool call from LLM. Text: {text[:200]}")

        # Append the assistant turn to history
        self._messages.append({"role": "assistant", "content": response.content})

        action: AnyAction = {"type": tool_block.name, **tool_block.input}
        log.info("Action: %s", json.dumps(action, ensure_ascii=False))
        return tool_block.id, action
