"""
Dynamic function loader — loads Python modules from a directory,
discovers @function decorated handlers, and registers routes.

Supports hot-reload via watchdog file system monitoring.
"""

import importlib
import importlib.util
import logging
import os
import sys
import time
from pathlib import Path
from typing import Optional

from .decorators import FunctionMetadata, get_registered_functions, clear_registry
from .heap_exchange import HeapExchange

logger = logging.getLogger("kubefn.loader")


class FunctionLoader:
    """Loads function modules from a directory structure."""

    def __init__(self, functions_dir: str, heap: HeapExchange):
        self.functions_dir = Path(functions_dir)
        self.heap = heap
        self.loaded_groups: dict[str, list[FunctionMetadata]] = {}
        self.loaded_modules: dict[str, object] = {}

    def load_all(self) -> dict[str, list[FunctionMetadata]]:
        """Load all function groups from the functions directory."""
        if not self.functions_dir.exists():
            self.functions_dir.mkdir(parents=True, exist_ok=True)
            logger.info(f"Created functions directory: {self.functions_dir}")
            return {}

        for group_dir in sorted(self.functions_dir.iterdir()):
            if group_dir.is_dir() and not group_dir.name.startswith('.'):
                self.load_group(group_dir.name)

        total_routes = sum(len(fns) for fns in self.loaded_groups.values())
        logger.info(f"Loaded {len(self.loaded_groups)} groups with {total_routes} functions")
        return self.loaded_groups

    def load_group(self, group_name: str) -> list[FunctionMetadata]:
        """Load or reload a single function group."""
        group_dir = self.functions_dir / group_name

        if not group_dir.exists():
            logger.warning(f"Group directory not found: {group_dir}")
            return []

        logger.info(f"Loading function group: {group_name} from {group_dir}")

        # Unload existing group
        self.unload_group(group_name)

        # Clear registry before loading (to only get this group's functions)
        old_registry = get_registered_functions().copy()
        clear_registry()

        # Re-register old functions from other groups
        for fn in old_registry:
            if fn.group != group_name:
                get_registered_functions().append(fn)

        # Load all .py files in the group directory
        functions = []
        for py_file in sorted(group_dir.glob("*.py")):
            if py_file.name.startswith('_'):
                continue

            module_name = f"kubefn_fn_{group_name}_{py_file.stem}"

            try:
                # Remove old module if reloading
                if module_name in sys.modules:
                    del sys.modules[module_name]

                spec = importlib.util.spec_from_file_location(module_name, py_file)
                if spec and spec.loader:
                    module = importlib.util.module_from_spec(spec)
                    sys.modules[module_name] = module
                    spec.loader.exec_module(module)
                    self.loaded_modules[module_name] = module

                    logger.info(f"  Loaded module: {py_file.name}")
            except Exception as e:
                logger.error(f"  Failed to load {py_file.name}: {e}")

        # Collect newly registered functions for this group
        all_registered = get_registered_functions()
        for fn in all_registered:
            if fn.group == group_name:
                functions.append(fn)
                for method in fn.methods:
                    logger.info(f"  Registered route: {method} {fn.path} → "
                              f"{group_name}.{fn.name}")

        self.loaded_groups[group_name] = functions

        # Generate revision ID from file contents
        revision = self._generate_revision(group_dir)
        logger.info(f"Group '{group_name}' loaded: rev={revision}, "
                    f"functions={len(functions)}")

        return functions

    def unload_group(self, group_name: str):
        """Unload a function group."""
        if group_name in self.loaded_groups:
            # Remove registered functions for this group
            all_registered = get_registered_functions()
            all_registered[:] = [fn for fn in all_registered if fn.group != group_name]

            # Remove loaded modules
            modules_to_remove = [
                name for name in self.loaded_modules
                if name.startswith(f"kubefn_fn_{group_name}_")
            ]
            for module_name in modules_to_remove:
                if module_name in sys.modules:
                    del sys.modules[module_name]
                del self.loaded_modules[module_name]

            del self.loaded_groups[group_name]
            logger.info(f"Unloaded group: {group_name}")

    def _generate_revision(self, group_dir: Path) -> str:
        """Generate a revision ID from file contents."""
        import hashlib
        hasher = hashlib.sha256()
        for py_file in sorted(group_dir.glob("*.py")):
            hasher.update(py_file.read_bytes())
        return f"rev-{hasher.hexdigest()[:12]}"
