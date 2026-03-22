"""
Function decorators for KubeFn Python functions.

Usage:
    from kubefn.decorators import function

    @function("/predict", methods=["POST"], group="ml-pipeline")
    def predict(request, ctx):
        return {"prediction": 0.95}
"""

import functools
from dataclasses import dataclass, field


@dataclass
class FunctionMetadata:
    """Metadata extracted from the @function decorator."""
    path: str
    methods: list[str]
    group: str
    name: str
    handler: callable


# Global registry of decorated functions
_registry: list[FunctionMetadata] = []


def function(path: str, methods: list[str] = None, group: str = "default"):
    """
    Decorator that registers a Python function as a KubeFn handler.

    @function("/predict", methods=["POST"], group="ml-pipeline")
    def predict(request, ctx):
        features = ctx.heap.get("features")
        return {"prediction": model.predict(features)}
    """
    if methods is None:
        methods = ["GET", "POST"]

    def decorator(func):
        metadata = FunctionMetadata(
            path=path,
            methods=methods,
            group=group,
            name=func.__name__,
            handler=func,
        )
        _registry.append(metadata)

        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            return func(*args, **kwargs)

        wrapper._kubefn_metadata = metadata
        return wrapper

    return decorator


def get_registered_functions() -> list[FunctionMetadata]:
    """Get all registered function handlers."""
    return list(_registry)


def clear_registry():
    """Clear the function registry (used during hot-reload)."""
    _registry.clear()
