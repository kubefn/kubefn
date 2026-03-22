"""
KubeFn Python Runtime — entry point.

Usage:
    python -m kubefn
    python -m kubefn --port 8080 --functions-dir /var/kubefn/functions
"""

import argparse
import os

from .server import run_server


def main():
    parser = argparse.ArgumentParser(description="KubeFn Python Runtime")
    parser.add_argument("--port", type=int,
                        default=int(os.environ.get("KUBEFN_PORT", "8080")))
    parser.add_argument("--functions-dir",
                        default=os.environ.get("KUBEFN_FUNCTIONS_DIR",
                                               "/var/kubefn/functions"))
    args = parser.parse_args()

    run_server(port=args.port, functions_dir=args.functions_dir)


if __name__ == "__main__":
    main()
