"""The test agent closes every scenario in the format it was begun in.

BenchmarkLogger rejects a legacy endScenario for a scenario begun with
beginScenarioV2 and refuses to write a result file that mixes formats. The
test agent's failure path must therefore use endScenarioV2 in protocol-v2
runs, like its two normal end-of-scenario paths.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_failure_path_closes_v2_scenarios_with_the_v2_call():
    asl = (ROOT / "src/agt/illuminance_controller_agent_bench.asl").read_text(encoding="utf-8")
    start = asl.index("-!run_scenario_step(ScenId, RunId, Step) <-")
    body = asl[start:asl.index("-!dispatch", start)]
    assert re.search(r'if \(protocol_version\("phase1-v2"\)\) \{\s*endScenarioV2\(false, Step, EnergyCost\)',
                     body)
    # every legacy endScenario call in the agent sits in the else branch of a
    # protocol check
    for match in re.finditer(r"\bendScenario\(", asl):
        before = asl[max(0, match.start() - 200):match.start()]
        assert "} else {" in before, asl[match.start() - 200:match.start() + 40]
