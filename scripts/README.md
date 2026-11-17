# Helper scripts

This folder holds the small programs that support the experiments but are not the
experiment runners themselves. The three runners that start a campaign cell are in the
repository root (`run_full_project.ps1`, `run_phase2_adapt.ps1`, `run_phase3_dynamics.ps1`);
how they are used is explained in the [README](../README.md) and in
[`../docs/EXPERIMENTS.md`](../docs/EXPERIMENTS.md). The Python programs that analyse and
check results are in [`../analysis/`](../analysis/README.md).

Every script has a comment block at the top (PowerShell: `Get-Help .\scripts\<name>.ps1 -Full`)
with its parameters and an example. This page gives the map.

| Script | What it does | Who calls it |
|---|---|---|
| `Read-RunConfig.ps1` | Reads [`../config/run_config.json`](../config/) and returns either one run mode (`-RunMode`, as a hashtable of its settings, validated for required keys and numeric ranges) or one fault-stage learning-settings profile (`-AdaptSettingsProfile`: the Gradle `-P` arguments and the values the adaptation JVM must report). Returns `$null` for a run mode if the file is missing. | `run_full_project.ps1` (run mode); `run_phase2_adapt.ps1` (settings profile) |
| `version_artifacts.ps1` | Copies the learned files of a run (Q-tables, learned graphs, metrics) and a `RUN_MANIFEST.json` into the orphan git branch `results` and tags the commit; pushes only with `-PublishResults`. | `run_full_project.ps1` (with `-VersionResults`); the publish step of `phase1.yml` and `phase4.yml` |
| `make_labs_doc.py` | Generates [`../docs/LABS.md`](../docs/LABS.md) from `config/run_config.json`, `src/agt/lab_profiles.asl`, the simulator flows and the scenario files. `--check` exits 1 when the document is out of date. Standard library only. | by hand, after a change to a flow, a profile or the configuration |
| `Run-Phase1bFlowSmoke.ps1` | Starts one temporary Node-RED instance per Phase-1b flow, runs [`../analysis/phase1b_flow_smoke.py`](../analysis/phase1b_flow_smoke.py) against them, writes its JSON evidence to `-OutputPath`, and stops the instances. Needs Node.js and a global Node-RED 4 install; refuses to start if one of its ports is in use. | CI job `smoke-training` in `ci.yml`; by hand |
| `curate_phase1b_confirmatory.ps1` | Downloads the eight Phase-1b confirmatory runs with `gh run download`, arranges them as `results/phase1b_corrected/run_<id>/`, writes the SHA-256 inventory and validates each archive. | by hand, after the runs finish |
| `curate_phase1b_extended_v3.ps1` | The same for the re-run of the extended arm, into `results/phase1b_extended_v3/`, with the two treatment-delivery gates. | by hand, after the runs finish |
| `curate_phase2_lowsun_v3.ps1` | The same for the Phase-2 low-sun re-run, into `results/phase2_v3_lowsun/`, with the low-sun delivery gate. | by hand, after the run finishes |
| `curate_phase2_structonly_v3.ps1` | The same for the Phase-2 structure-only campaign, into `results/phase2_v3_structonly/`; also downloads the per-cell logs into `adapt_logs/` (kept locally) and distils the knowledge-layer evidence, unless `-SkipAdaptLogs` is given. | by hand, after the run finishes |
| `download_phase1b_pilot.ps1` | Downloads the four Phase-1b pilot runs into `results/exploratory/phase1b_pilot/` and marks each `PILOT_ONLY.json`. | by hand |
| `download_phase1b_truncation_inputs.ps1` | Downloads the raw Phase-1b metric files that [`../analysis/phase1_truncated_learning.py`](../analysis/phase1_truncated_learning.py) needs into `.phase1b_truncation_inputs/`, after checking that each artifact exists, matches the recorded id and has not expired. | by hand |

The four `curate_*` scripts and the two `download_*` scripts need the GitHub CLI (`gh`),
signed in, and the artifacts of the runs they name; GitHub keeps artifacts for 90 days
(see [`../docs/REPRODUCING.md`](../docs/REPRODUCING.md)). A curate script skips a run that
is already curated and stops with an error if an archive fails its validation. Each prints the
artifact id and digest that the campaign manifest records. After curating, the registered
analysis of the campaign is run from [`../analysis/`](../analysis/README.md) (section 1 of its
README); the archive layout is described in [`../results/README.md`](../results/README.md).
