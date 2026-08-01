import os
from dataclasses import dataclass, field


@dataclass
class Config:
    # LLM API (Groq — ücretsiz, console.groq.com)
    groq_api_key: str = field(default_factory=lambda: os.environ.get("GROQ_API_KEY", ""))
    llm_model: str = "llama-3.3-70b-versatile"

    # Server
    host: str = "0.0.0.0"
    port: int = 8765

    # Agent
    max_steps: int = 30
    step_timeout_sec: float = 10.0
    max_tree_tokens: int = 4096

    vision_fallback: bool = False
    min_tree_nodes: int = 5


config = Config()
