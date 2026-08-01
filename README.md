# DOMATES & MENEMEN — Mobile Agent Architecture

> "Sessiz Mantık, Doğrudan İcraat."

A two-layer agentic AI system that sees your Android screen and acts on it — no hardcoded scripts, no "I can't do that" limitations.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Android Device                       │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │              DOMATES  (Execution Layer)           │  │
│  │                                                   │  │
│  │  DomatesAccessibilityService                      │  │
│  │    └─ Reads UI tree (AccessibilityNodeInfo)       │  │
│  │    └─ Executes gestures (GestureDescription)      │  │
│  │    └─ Performs AccessibilityActions               │  │
│  │                                                   │  │
│  │  ScreenStateCollector   ActionExecutor            │  │
│  │       ↕ JSON (WebSocket)                          │  │
│  │  MenemenClient ──────────────────────────────────►│  │
│  └──────────────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────────────┘
                       │ WebSocket  ws://host:8765
                       │
┌──────────────────────▼──────────────────────────────────┐
│                MENEMEN  (Brain / Agentic AI)             │
│                                                         │
│  server.py  ──► agent.py  ──► llm_client.py            │
│                   │               │                     │
│                   │           Claude API                │
│                   │     (ReAct tool-use loop)           │
│                   │                                     │
│              screen_parser.py                           │
│           (compresses AccessibilityTree → tokens)       │
└─────────────────────────────────────────────────────────┘
```

## Message Protocol

All messages are JSON over WebSocket.

| Direction        | Type             | Payload                                         |
|-----------------|------------------|-------------------------------------------------|
| Device → Server | `task`           | `{ task: "..." }`                               |
| Server → Device | `screen_request` | `{ request_id }`                                |
| Device → Server | `screen_state`   | `{ request_id, tree, screenshot? }`             |
| Server → Device | `action`         | `{ request_id, type, ...action fields }`        |
| Device → Server | `action_ack`     | `{ request_id, ok, error? }`                    |
| Server → Device | `task_result`    | `{ ok, result }`                                |

## Actions (MENEMEN → DOMATES)

| Action      | Key params                           | Notes                           |
|-------------|--------------------------------------|---------------------------------|
| `click`     | `node_id` or `x,y` (0–1 fractions)  | Prefers node; falls back to tap |
| `type`      | `text`, `clear_first?`               | Uses `ACTION_SET_TEXT`          |
| `scroll`    | `direction`, `amount`, `node_id?`    | Accessibility action + gesture  |
| `swipe`     | `from_x,y`, `to_x,y`, `duration_ms` | Free gesture via GestureDesc.   |
| `press`     | `key` (back/home/recents/…)          | Global actions + AudioManager   |
| `open_app`  | `package`                            | Launches via PackageManager     |
| `wait`      | `ms`                                 | Blocking sleep                  |
| `done`      | `result`                             | Ends the agent loop             |

## Quick Start

### 1. MENEMEN (Python Server)

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...
python -m menemen.server
```

Server listens on `0.0.0.0:8765` by default.

### 2. DOMATES (Android App)

1. Build and install the APK:
   ```bash
   cd domates
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. Open **DOMATES** on the device.

3. Go to **Settings → Accessibility → DOMATES Agent** and enable it.

4. Enter the MENEMEN server IP and your task, then tap **Run Task**.

### 3. Example Tasks

```
"WhatsApp'ta Ali'nin geçen haftaki konumlarını bul ve Notlar'a kaydet"
"Instagram'da siyasi içerikleri otomatik olarak geç"
"Görüldü vermeden son 3 mesajımı oku ve özetle"
"Ekrandaki reklamı kapat"
```

## Configuration

| Env Var                  | Default            | Description                               |
|-------------------------|--------------------|-------------------------------------------|
| `ANTHROPIC_API_KEY`     | —                  | Required                                  |
| `MENEMEN_HOST`          | `0.0.0.0`          | Bind address                              |
| `MENEMEN_PORT`          | `8765`             | WebSocket port                            |
| `MENEMEN_MAX_STEPS`     | `30`               | Max ReAct iterations per task             |
| `MENEMEN_VISION`        | `true`             | Attach screenshot when tree is sparse     |

## Project Structure

```
Domates-tel/
├── menemen/                   # Python backend (Brain)
│   ├── __init__.py
│   ├── config.py              # Runtime configuration
│   ├── action_schemas.py      # Action types + Claude tool definitions
│   ├── screen_parser.py       # Accessibility tree → compact JSON
│   ├── llm_client.py          # Claude API ReAct wrapper
│   ├── agent.py               # Main agent loop
│   └── server.py              # WebSocket server
├── domates/                   # Android app (Execution)
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/domates/
│   │       ├── Protocol.kt                   # Shared message constants
│   │       ├── DomatesAccessibilityService.kt # Core service
│   │       ├── ScreenStateCollector.kt        # UI tree serialiser
│   │       ├── ActionExecutor.kt              # Gesture & action runner
│   │       ├── MenemenClient.kt               # WebSocket client
│   │       └── MainActivity.kt               # Setup UI
│   └── app/build.gradle
├── requirements.txt
├── config.example.yaml
└── README.md
```

## Security Notes

- MENEMEN listens on all interfaces by default. Restrict to your local network or add token authentication before exposing publicly.
- MediaProjection (screenshot) requires user consent every session.
- The app requests `BIND_ACCESSIBILITY_SERVICE` — grant only on trusted devices.

## Architectural Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| AI Brain | Claude with tool-use | Structured action output, best reasoning |
| Transport | WebSocket | Low-latency bidirectional, no polling |
| UI reading | AccessibilityNodeInfo | No root required, privacy-safe |
| Vision fallback | MediaProjection | For visually-only UIs (games, canvases) |
| Tree compression | screen_parser.py | Cuts token usage 60–80% |
| Gestures | GestureDescription API | Works without root on API 24+ |
