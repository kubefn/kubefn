"""
Function execution context — the function's window into the organism.
"""

from dataclasses import dataclass
import logging
from typing import Any, Optional

from .heap_exchange import HeapExchange


@dataclass
class FnRequest:
    """Incoming HTTP request."""
    method: str
    path: str
    headers: dict[str, str]
    query_params: dict[str, str]
    body: bytes
    body_text: str = ""

    def query_param(self, name: str, default: str = None) -> Optional[str]:
        return self.query_params.get(name, default)


@dataclass
class FnContext:
    """
    The function's window into the living organism.
    Provides access to HeapExchange, cache, logging, config.
    """
    heap: HeapExchange
    group_name: str
    function_name: str
    revision_id: str
    config: dict[str, str]
    _cache: dict = None

    def __post_init__(self):
        if self._cache is None:
            self._cache = {}

    @property
    def logger(self) -> logging.Logger:
        return logging.getLogger(f"kubefn.{self.group_name}.{self.function_name}")

    def cache_get(self, key: str) -> Optional[Any]:
        return self._cache.get(key)

    def cache_put(self, key: str, value: Any):
        self._cache[key] = value
