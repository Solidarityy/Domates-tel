"""
MENEMEN ReAct Agent Loop.

Flow per task:
  1. Receive task string + initial screen state from DOMATES.
  2. Ask Claude for the next action (tool call).
  3. Send action to DOMATES; wait for execution confirmation + new screen state.
  4. Feed new state back into LLM as observation.
  5. Repeat until Claude calls done() or max_steps is reached.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Callable, Awaitable

from .action_schemas import AnyAction
from .config import config
from .llm_client import MenemenLLM
from .screen_parser import compress_tree, count_nodes

log = logging.getLogger(__name__)

# Callback types
SendActionCallback = Callable[[AnyAction], Awaitable[None]]
"""Send an action dict to DOMATES over WebSocket."""

GetScreenCallback = Callable[[], Awaitable[tuple[dict, str | None]]]
"""
Ask DOMATES for the current screen state.
Returns (raw_accessibility_tree_dict, optional_screenshot_base64).
"""


class MenemenAgent:
    def __init__(
        self,
        send_action: SendActionCallback,
        get_screen: GetScreenCallback,
    ) -> None:
        self._send_action = send_action
        self._get_screen = get_screen
        self._llm = MenemenLLM()

    async def run(self, task: str) -> str:
        """
        Execute a task. Returns the final result string.
        Raises asyncio.TimeoutError or RuntimeError on failure.
        """
        log.info("MENEMEN starting task: %s", task)
        self._llm.reset()

        for step in range(config.max_steps):
            log.info("--- Step %d/%d ---", step + 1, config.max_steps)

            # 1. Capture current screen state
            raw_tree, screenshot_b64 = await asyncio.wait_for(
                self._get_screen(), timeout=config.step_timeout_sec
            )
            compressed = compress_tree(raw_tree)
            node_count = count_nodes(json.loads(compressed) if compressed else None)
            log.debug("Tree nodes: %d", node_count)

            # Use vision fallback if tree is sparse
            use_vision = (
                config.vision_fallback
                and screenshot_b64
                and node_count < config.min_tree_nodes
            )

            self._llm.add_screen_state(
                compressed,
                screenshot_b64 if use_vision else None,
            )

            # 2. Ask Claude for next action
            tool_use_id, action = self._llm.next_action(task)

            # 3. Handle terminal actions
            if action["type"] == "done":
                result = action.get("result", "Task completed.")
                log.info("Task done: %s", result)
                self._llm.add_tool_result(tool_use_id, "acknowledged")
                return result

            if action["type"] == "error":
                msg = action.get("message", "Unknown error from agent.")
                raise RuntimeError(msg)

            # 4. Send action to DOMATES and wait for ACK
            await asyncio.wait_for(
                self._send_action(action), timeout=config.step_timeout_sec
            )

            # 5. Feed execution result back to LLM
            self._llm.add_tool_result(tool_use_id, "executed")

        raise RuntimeError(f"Task exceeded max_steps ({config.max_steps})")
