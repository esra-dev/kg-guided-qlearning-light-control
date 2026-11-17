#!/usr/bin/env python3
"""Write stable workflow-dispatch provenance into the consolidated artifact.

The receipt names the workflow file that actually ran. It is taken from
``--workflow`` when given, otherwise from GitHub's ``GITHUB_WORKFLOW_REF``
environment variable (``owner/repo/.github/workflows/<file>@<ref>``), and is
``"unknown"`` outside GitHub Actions when no override is passed. Receipts
already archived are not rewritten (many of them name phase1.yml for every
workflow, which is what this script wrote before).
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

WORKFLOW_DIR = ".github/workflows/"


def workflow_from_ref(ref: str | None) -> str | None:
    """Repository-relative workflow path from a GITHUB_WORKFLOW_REF value."""
    if not ref:
        return None
    path = ref.split("@", 1)[0]
    index = path.find(WORKFLOW_DIR)
    if index < 0:
        return None
    return path[index:]


def resolve_workflow(override: str | None, environ=None) -> str:
    if override and override.strip():
        return override.strip().replace("\\", "/")
    environ = os.environ if environ is None else environ
    return workflow_from_ref(environ.get("GITHUB_WORKFLOW_REF")) or "unknown"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--run-mode", required=True)
    parser.add_argument("--profiles", required=True)
    parser.add_argument("--seeds", required=True)
    parser.add_argument("--publish-results", required=True)
    # Repository-relative workflow file (e.g. .github/workflows/phase2.yml).
    # Optional: by default it is read from GITHUB_WORKFLOW_REF.
    parser.add_argument("--workflow", default=None)
    # Phase-2 structure-only campaign: the adapt-step KG layer. Optional and
    # omitted from the payload when not passed, so the Phase-1 and Phase-4
    # workflows (which do not use it) keep their payload shape.
    parser.add_argument("--knowledge-layer", default=None)
    args = parser.parse_args()
    payload = {
        "workflow": resolve_workflow(args.workflow),
        "run_id": int(args.run_id),
        "head_sha": args.sha,
        "ref": args.ref,
        "run_mode": args.run_mode,
        "profiles": [value.strip() for value in args.profiles.split(",") if value.strip()],
        "seeds": [int(value) for value in args.seeds.split(",") if value.strip()],
        "publish_results": args.publish_results.strip().lower() == "true",
    }
    if args.knowledge_layer is not None:
        payload["knowledge_layer"] = args.knowledge_layer.strip().lower()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
