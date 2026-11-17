"""Text checks on the Phase-1/3/4 workflows (no YAML parser needed in CI).

- Phase 3 queues every dispatch in its own concurrency group, and its
  environment fallback for replicas equals the input default (1..10).
- Phase 4 validates the protocol-v2 archive and writes a SHA-256 inventory in
  its aggregate job, as Phase 1 does.
- Every workflow that publishes with scripts/version_artifacts.ps1 (which
  pushes a results tag) checks afterwards that the tag is on the remote.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def _text(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


def test_phase3_concurrency_is_per_run_and_replica_defaults_agree():
    text = _text("phase3.yml")
    group = re.search(r"^concurrency:\n\s+group:\s*(.+)$", text, re.M).group(1)
    assert "github.run_id" in group
    input_default = re.search(r"replicas:\n(?:\s+\w+:.*\n)*?\s+default:\s*\"([^\"]+)\"", text).group(1)
    env_fallback = re.search(r"REPLICAS:\s*\$\{\{.*\|\|\s*'([^']+)'", text).group(1)
    assert input_default == env_fallback == ",".join(str(i) for i in range(1, 11))


def test_phase4_aggregate_validates_and_inventories_the_archive():
    text = _text("phase4.yml")
    aggregate = text[text.index("\n  aggregate:"):]
    assert "analysis/validate_phase4_v2_archive.py" in aggregate
    assert "analysis/phase1_archive_inventory.py" in aggregate
    assert "--out analysis/out/SHA256SUMS.csv" in aggregate
    # Validation runs before the analyses, the inventory before the upload.
    assert aggregate.index("validate_phase4_v2_archive") < aggregate.index("sweep_report.py")
    assert aggregate.index("phase1_archive_inventory") < aggregate.index("upload-artifact")


def test_tagged_publishers_verify_the_tag_on_the_remote():
    for name in ("phase1.yml", "phase4.yml"):
        text = _text(name)
        assert "./scripts/version_artifacts.ps1" in text
        publish = text.index("name: Publish to 'results' branch")
        assert "id: publish" in text[publish:publish + 200]
        verify = text.index("name: Verify the results tag is on the remote")
        assert verify > publish
        block = text[verify:verify + 900]
        assert "steps.publish.outputs.results_tag" in block
        assert "git ls-remote --exit-code --tags origin" in block
        assert "exit 1" in block


def test_version_artifacts_reports_and_checks_the_tag_push():
    script = (ROOT / "scripts" / "version_artifacts.ps1").read_text(encoding="utf-8")
    assert "results_tag=$tagName" in script
    push = script.index("& git push origin $tagName")
    assert "$LASTEXITCODE -ne 0" in script[push:push + 200]
