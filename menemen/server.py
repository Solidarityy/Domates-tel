"""
MENEMEN WebSocket Server.

Protocol (JSON messages over WebSocket):

Device → Server:
  { "type": "screen_state",
    "tree": <accessibility tree dict>,
    "screenshot": "<base64 png>" | null,
    "request_id": "<uuid>" }          ← response to a pending screen request

  { "type": "action_ack",
    "request_id": "<uuid>",
    "ok": true | false,
    "error": "<msg if not ok>" }

  { "type": "task",
    "task": "<natural language command>" }

Server → Device:
  { "type": "action",
    "request_id": "<uuid>",
    <action fields> }

  { "type": "screen_request",
    "request_id": "<uuid>" }

  { "type": "task_result",
    "ok": true | false,
    "result": "<summary>" | "<error>" }
"""
from __future__ import annotations

import asyncio
import json
import logging
import uuid
from typing import Any

import websockets

from .agent import MenemenAgent
from .config import config

log = logging.getLogger(__name__)


class DeviceSession:
    """Manages one connected DOMATES device."""

    def __init__(self, ws: Any) -> None:
        self.ws = ws
        # Pending futures keyed by request_id
        self._pending_screens: dict[str, asyncio.Future] = {}
        self._pending_acks: dict[str, asyncio.Future] = {}
        self._recv_task: asyncio.Task | None = None

    # ------------------------------------------------------------------ #
    # Internal send helpers                                                #
    # ------------------------------------------------------------------ #

    async def _send(self, msg: dict) -> None:
        await self.ws.send(json.dumps(msg, ensure_ascii=False))

    # ------------------------------------------------------------------ #
    # Agent callbacks                                                      #
    # ------------------------------------------------------------------ #

    async def request_screen(self) -> tuple[dict, str | None]:
        req_id = str(uuid.uuid4())
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending_screens[req_id] = fut
        await self._send({"type": "screen_request", "request_id": req_id})
        payload = await asyncio.wait_for(fut, timeout=config.step_timeout_sec)
        return payload["tree"], payload.get("screenshot")

    async def send_action(self, action: dict) -> None:
        req_id = str(uuid.uuid4())
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending_acks[req_id] = fut
        await self._send({"type": "action", "request_id": req_id, **action})
        ack = await asyncio.wait_for(fut, timeout=config.step_timeout_sec)
        if not ack.get("ok", False):
            raise RuntimeError(f"Action failed on device: {ack.get('error', '')}")

    # ------------------------------------------------------------------ #
    # Incoming message dispatcher                                          #
    # ------------------------------------------------------------------ #

    def _dispatch(self, msg: dict) -> None:
        mtype = msg.get("type")
        req_id = msg.get("request_id")

        if mtype == "screen_state" and req_id in self._pending_screens:
            fut = self._pending_screens.pop(req_id)
            if not fut.done():
                fut.set_result(msg)

        elif mtype == "action_ack" and req_id in self._pending_acks:
            fut = self._pending_acks.pop(req_id)
            if not fut.done():
                fut.set_result(msg)

        else:
            log.warning("Unhandled message: %s", mtype)

    async def listen(self) -> None:
        async for raw in self.ws:
            try:
                msg = json.loads(raw)
                self._dispatch(msg)
            except Exception as exc:
                log.error("Error parsing message: %s", exc)


async def _handle_connection(ws: Any) -> None:
    addr = ws.remote_address
    log.info("DOMATES connected from %s", addr)
    session = DeviceSession(ws)

    # Start background listener
    listen_task = asyncio.create_task(session.listen())

    try:
        # Wait for the first message which must be a task
        raw = await asyncio.wait_for(ws.recv(), timeout=60.0)
        msg = json.loads(raw)

        if msg.get("type") != "task":
            await ws.send(json.dumps({
                "type": "task_result",
                "ok": False,
                "result": "First message must be type=task",
            }))
            return

        task_str: str = msg["task"]
        log.info("Received task: %s", task_str)

        agent = MenemenAgent(
            send_action=session.send_action,
            get_screen=session.request_screen,
        )

        result = await agent.run(task_str)
        await ws.send(json.dumps({
            "type": "task_result",
            "ok": True,
            "result": result,
        }))

    except asyncio.TimeoutError:
        log.error("Timeout waiting for task")
        await ws.send(json.dumps({
            "type": "task_result",
            "ok": False,
            "result": "Timeout",
        }))
    except Exception as exc:
        log.exception("Task failed")
        try:
            await ws.send(json.dumps({
                "type": "task_result",
                "ok": False,
                "result": str(exc),
            }))
        except Exception:
            pass
    finally:
        listen_task.cancel()
        log.info("Session closed: %s", addr)


async def serve() -> None:
    log.info("MENEMEN server starting on %s:%d", config.host, config.port)
    async with websockets.serve(_handle_connection, config.host, config.port):
        await asyncio.Future()  # run forever


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    asyncio.run(serve())


if __name__ == "__main__":
    main()
