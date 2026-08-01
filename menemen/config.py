import os
from dataclasses import dataclass, field


@dataclass
class Config:
    # Claude API
    anthropic_api_key: str = field(default_factory=lambda: os.environ.get("ANTHROPIC_API_KEY", ""))
    claude_model: str = "claude-sonnet-4-6"

    # Server
    host: str = "0.0.0.0"
    port: int = 8765

    # Agent
    max_steps: int = 30
    step_timeout_sec: float = 10.0
    # Token budget for compressed accessibility tree
    max_tree_tokens: int = 4096

    # Vision fallback — set True to attach screenshot when tree parsing yields < min_tree_nodes
    vision_fallback: bool = True
    min_tree_nodes: int = 5


config = Config()
