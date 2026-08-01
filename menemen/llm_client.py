"""
Calls Groq's OpenAI-compatible API via httpx.
Free tier: console.groq.com — no credit card required.
"""
from __future__ import annotations

import json
import logging
from typing import Any

import httpx

from .action_schemas import MENEMEN_TOOLS, AnyAction
from .config import config

log = logging.getLogger(__name__)

_API_URL = "https://api.groq.com/openai/v1/chat/completions"

SYSTEM_PROMPT = """You are MENEMEN, the brain of a mobile automation agent running on Android.

Your job is to complete the user's task by calling one tool at a time.
After each action you will receive the updated screen state.
Think step by step but ACT immediately — do not explain your reasoning in text.

Rules:
- Prefer node IDs over coordinates when available.
- If the screen hasn't changed after an action, try a different approach.
- Never ask the user a clarifying question; make your best guess and proceed.
- If you cannot complete the task after several attempts, call done() with an honest failure summary.
- Always call done() when the task is finished.
"""

# Convert Anthropic tool format → OpenAI tool format
def _to_openai_tools(tools: list[dict]) -> list[dict]:
    result = []
    for t in tools:
        result.append({
            "type": "function",
            "function": {
                "name": t["name"],
                "description": t.get("description", ""),
                "parameters": t.get("input_schema", {}),
            }
        })
    return result

_OAI_TOOLS = _to_openai_tools(MENEMEN_TOOLS)


class MenemenLLM:
    def __init__(self) -> None:
        self._messages: list[dict] = []
        self._http = httpx.AsyncClient(
            timeout=60.0,
            headers={
                "Authorization": f"Bearer {config.groq_api_key}",
                "Content-Type": "application/json",
            },
        )

    def reset(self) -> None:
        self._messages = []

    def add_screen_state(self, state_json: str, screenshot_b64: str | None = None) -> None:
        self._messages.append({
            "role": "user",
            "content": f"<screen_state>\n{state_json}\n</screen_state>",
        })

    def add_tool_result(self, tool_use_id: str, result: str) -> None:
        self._messages.append({
            "role": "tool",
            "tool_call_id": tool_use_id,
            "content": result,
        })

    async def next_action(self, task: str) -> tuple[str, AnyAction]:
        if not self._messages:
            self._messages.append({"role": "user", "content": f"TASK: {task}"})

        payload = {
            "model": config.llm_model,
            "max_tokens": 1024,
            "messages": [{"role": "system", "content": SYSTEM_PROMPT}] + self._messages,
            "tools": _OAI_TOOLS,
            "tool_choice": "required",
        }

        resp = await self._http.post(_API_URL, content=json.dumps(payload))

        if resp.status_code != 200:
            raise RuntimeError(f"Groq API error {resp.status_code}: {resp.text[:400]}")

        data = resp.json()
        message = data["choices"][0]["message"]

        tool_calls = message.get("tool_calls")
        if not tool_calls:
            text = message.get("content", "")
            raise RuntimeError(f"No tool call from LLM. Text: {text[:200]}")

        self._messages.append(message)

        tc = tool_calls[0]
        action: AnyAction = {"type": tc["function"]["name"], **json.loads(tc["function"]["arguments"])}
        log.info("Action: %s", json.dumps(action, ensure_ascii=False))
        return tc["id"], action
