# Reproducing the results

There are three levels of re-checking, from minutes on any computer to a full re-run on
GitHub Actions:

1. re-check the stored tables from the stored data;
2. re-run single cells locally and compare them with the archived files;
3. re-run a whole campaign on GitHub Actions.

The archives themselves are described in [../results/README.md](../results/README.md),
the cells and receipts in [EXPERIMENTS.md](EXPERIMENTS.md), and the installation in the
[README](../README.md#requirements-and-installation). Commands are written as in the
README: `py -3` and `.\gradlew.bat` on Windows; on Linux and macOS write `python3` and
`./gradlew`.

The times in this document were measured on the development computer (Windows 11). A
second measurement on the same computer while other jobs were running took two to three
times as long (for example almost 4 minutes instead of about 70 seconds for
`check_pins.py`), so allow two to three times the stated times.

## What is deterministic and what is not

- **Training, test and fault-stage cells are deterministic.** The rooms contain no random
  numbers, the start situations are taken from files in a fixed order, and the agent's
  random choices come from a seeded generator (from `run.seed` in training and in the
  fault stage, from the fixed seed 42 in the test; see
  [EXPERIMENTS.md](EXPERIMENTS.md), section 4). Run twice with the same
  code, lab, arm and seed, a cell writes the same Q-tables, metrics, test results and
  recovery files.
- **Exceptions within those cells.** The column `LegacyWallClockTotalEnergyCost` of
  `benchmark_results_*.csv` (and the column `avg_legacy_wallclock_energy` that
  `analysis/sweep_report.py` derives from it) adds up energy on a wall-clock timer and
  depends on timing; `BenchmarkLogger` documents it as a diagnostic only. The receipts
  carry a `timestamp`. Log files contain times and paths. None of these enters a
  registered result.
- **Line endings.** The checked-out input files are identical on every system:
  `.gitattributes` starts with `* -text`, so git checks out every file exactly as it was
  committed, whatever the `core.autocrlf` setting, and the scenario checksums in the
  receipts match on Windows as on Linux. The output files differ: Java on Windows writes
  CRLF line endings, the GitHub runners (Linux) write LF. Compare output file contents,
  not raw bytes, across the two systems.
- **Phase 3 depends on timing.** A delay is measured by polling the simulator every
  50 milliseconds over HTTP while the simulator advances its own 50-millisecond tick, so
  repeated runs can measure slightly different delays, especially for the fast lamps.
  Phase-3 results are therefore summarised over replicas and not expected to repeat byte
  for byte.

## Level 1: re-check the stored tables from the stored data

```powershell
py -3 analysis/check_pins.py
py -3 analysis/check_pins.py --run-reproducers
py -3 analysis/check_pins.py --run-reproducers --include-local   # also the archives with local raw data
py -3 analysis/check_pins.py --only phase2_v2_corrected --run-reproducers
```

`check_pins.py` (standard library only) checks every archive under `results/` against its
pins file in `results/pins/`: the dispatch receipt of every run
(`run_<id>/analysis/out/workflow_inputs.json`), the key values of the registered tables,
selected JSON values, and the SHA-256 of every tracked file listed in the archive's
`SHA256SUMS.csv` inventories. On the development computer it took about 70 seconds. It
needs no git history, so it also works in a shallow clone or a ZIP download, and it ends
with `pins check OK (12 archive(s))`.

`--run-reproducers` then runs the reproduction script of each archive. A reproducer
recomputes the registered tables from the per-cell files of the archive with the
registered analysis code and compares them with the stored tables, byte for byte after
canonical formatting of the numbers. The reproducers need `numpy`, `scipy` and `matplotlib`.

| Archive | Reproducer | Data it needs | Time on the development computer |
|---|---|---|---|
| `phase1_v2_corrected` | `analysis/reproduce_phase1_v2.py` | tracked | about 3 minutes |
| `phase2_v2_corrected` | `analysis/reproduce_phase2_v2.py` | tracked | about 10 seconds |
| `phase2_v3_lowsun` | `analysis/reproduce_phase2_lowsun_v3.py` | tracked | a few seconds |
| `phase2_v3_structonly` | `analysis/reproduce_phase2_structonly_v3.py` | tracked (and `phase2_v2_corrected`) | a few seconds |
| `phase3_v2_corrected` | `analysis/reproduce_phase3_v2.py` | tracked | a few seconds |
| `phase1b_corrected` | `analysis/reproduce_phase1b.py` | local raw data, about 650 MB | – |
| `phase1b_extended_v3` | `analysis/reproduce_phase1b_extended_v3.py` | local raw data of this archive and of `phase1b_corrected` | – |
| `phase4_v2_corrected` | `analysis/reproduce_phase4_v2.py` | local raw data, about 0.7 GB | – |
| `phase4_v2_ladder` | `analysis/reproduce_phase4_v2_ladder.py` | local raw data of this archive and of `phase4_v2_corrected`, about 1.9 GB together | – |
| `exploratory/*` | none | see [../results/README.md](../results/README.md) | – |

All five tracked-data reproducers together took about 4.5 minutes. The CI workflow runs
both commands on every push and pull request (`ci.yml`, jobs `validate-resources` and
`reproducers`).

`--run-reproducers` prints one line per archive:

- `[reproducer] <campaign>: OK in ... s (...)` for each of the five tracked-data archives;
- `[reproducer] <campaign>: needs local raw data; not run (use --include-local)` for the
  four archives of the next section;
- `[reproducer] <campaign>: none (...)` for the three exploratory archives
  (`phase1b_pilot`, `phase2b_qgap`, `phase1_truncated_learning_exploratory`). Their pins
  files have no reproducer, and the text in brackets is the pins file's
  `reproducer_note`, which says why: the raw pilot artifacts (about 4.2 GB) and the
  extracted source artifacts of the Q-gap study (about 2.1 GB) are not in the repository,
  and the truncated learning curves need inputs downloaded with
  `scripts/download_phase1b_truncation_inputs.ps1`. "none" is not a failure; the stored
  files of these archives are still checked against their pins.

The run ends with `reproducers OK` when no reproducer failed.

### The raw data of four archives

The reproducers of `phase1b_corrected`, `phase1b_extended_v3`, `phase4_v2_corrected` and
`phase4_v2_ladder` read the per-cell Q-tables and test files (`training_stereo_*/qtable_*.csv`,
`benchmark_results_*.csv` under `run_*/benchmark/results_seed*/`). These files are too
large for the repository and are ignored by git. Without them `--run-reproducers` prints
`needs local raw data; not run` and continues; the stored tables are still checked
against their pins.

The raw files are the contents of the runs' consolidated workflow artifacts on GitHub
Actions. GitHub keeps these artifacts for 90 days (`retention-days` in the workflows); the
dates below are the expiry dates that GitHub reports for each artifact.

| Archive | Runs (artifact) | Artifacts kept until | Raw files for a reader |
|---|---|---|---|
| `phase1b_corrected` | eight runs `30199243656` to `30199266427` of 2026-07-26 (`phase1-consolidated`) | expired (about 2026-10-24) | cannot be downloaded any more |
| `phase1b_extended_v3` | `43099281565`, `43099286057` of 2026-11-11 (`phase1-consolidated`) | 2027-02-10 | downloadable until then; the reproducer also needs the raw files of `phase1b_corrected`, so it cannot be run without them |
| `phase4_v2_corrected` | `29926341581`, `29926354783` of 2026-07-22 (`phase4-consolidated`) | expired (about 2026-10-20) | cannot be downloaded any more |
| `phase4_v2_ladder` | `43494914019`, `43494933613`, `43494955158`, `43494975441` of 2026-11-13 (`phase4-consolidated`) | 2027-02-11 | downloadable until then; the reproducer also needs the raw files of `phase4_v2_corrected`, so it cannot be run without them |

All four reproducers therefore need raw files of a July campaign, which a reader can no
longer download. For these four archives, what a reader can check is what the repository
holds: the registered tables and the summary files of each run against the pins
(`check_pins.py` without `--include-local`), and the SHA-256 inventories
(`SHA256SUMS.csv`), which list every raw file with its SHA-256 checksum, so that a copy
of the raw files obtained in another way can be verified file by file. The raw files of
the exploratory archives come from July runs as well (the pilot runs `30171111962` to
`30171114580`, the Phase-2 runs read by the Q-gap study, the Phase-1b runs of the
truncated learning curves); their artifacts have expired too.

To use artifacts that are still available, download the consolidated artifact of each run
(`gh run download <run id> --name phase1-consolidated --dir <staging folder>`), copy its
`benchmark/` tree into `results/<campaign>/run_<run id>/benchmark/`, and run
`py -3 analysis/check_pins.py --only <campaign> --run-reproducers --include-local`.
With `--include-local` the checker also hashes the present untracked files against the
archive's `SHA256SUMS.csv`, so a wrong or incomplete download is reported.

### Corrected tables

Two archived tables were produced by code that the project later corrected, without
changing the archives: the Phase-4 compliance levels of lab5 (test runs that need no step
are now scored from their declared start state), and the Phase-3 paired table (which
contained test statistics that the Phase-3 registration does not allow). The reproducers
still rebuild the archived tables exactly, using the old behaviour through an explicit
option. The corrected tables are in `results/_corrected_tables/<campaign>/`; each folder's
`README.md` says what was corrected and how the table is rebuilt.

## Level 2: re-run single cells locally

The [README](../README.md#quick-start) shows how to run one cell of each kind. Because
training and test are deterministic, the output of a local cell can be compared with the
file that the same cell produced on GitHub Actions. The archived per-cell files of the
Phase-1 run `29848584965` (run mode `phase1_v2_kg_only`) are in the repository under
`results/phase1_v2_corrected/run_29848584965/benchmark/results_seed<N>/<lab>/`.

This PowerShell function compares two CSV files by content, ignoring the line endings and
the wall-clock column:

```powershell
function Compare-ResultCsv([string]$Mine, [string]$Archived) {
    $a = Import-Csv $Mine; $b = Import-Csv $Archived
    $cols = $b[0].PSObject.Properties.Name | Where-Object { $_ -notmatch 'legacy_?wall_?clock' }
    $diff = Compare-Object ($a | Select-Object $cols | ConvertTo-Csv) ($b | Select-Object $cols | ConvertTo-Csv) -SyncWindow 0
    if ($diff) { "DIFFERENT: $Mine" } else { "same: $Mine" }
}
```

**A training cell.** After step 3 of the quick start (lab1, knowledge arm, seed 1):

```powershell
$arch = "results/phase1_v2_corrected/run_29848584965/benchmark/results_seed1/lab1/training_stereo_true"
foreach ($f in "metrics_stereotypes_true_lab1.csv", "first_goal_stereotypes_true_lab1.csv",
               "coverage_stereotypes_true_lab1.csv", "qtable_final_stereotypes_true_lab1.csv") {
    Compare-ResultCsv "benchmark/results/lab1/training_stereo_true/$f" "$arch/$f"
}
```

**A test cell.** After step 4 of the quick start (lab2, knowledge arm, seed 1):

```powershell
$arch = "results/phase1_v2_corrected/run_29848584965/benchmark/results_seed1/lab2/ql_true"
Compare-ResultCsv benchmark_results_ql_true.csv "$arch/benchmark_results_ql_true.csv"
Compare-ResultCsv bench_step_log_ql_true.csv   "$arch/bench_step_log_ql_true.csv"
```

The same works for `-OnlyModes ql_false` and `rule_based` against the folders `ql_false/`
and `rule_based/`, and for other seeds with `results_seed<N>`. On the development
computer, the training cell above and the test cells of lab2 seed 1 gave files with the
same content as the archived ones (apart from the wall-clock column and the receipt), and
so did a lab5 test cell compared with the Phase-4 run `29926341581`; for lab5 the trained
tables are not in the repository (they belong to the local raw data above).

**A fault-stage cell.** The archived fault-stage files are under
`results/phase2_v2_corrected/run_<id>/recovery_root/seed<N>/` (and likewise for
`phase2_v3_lowsun` and `phase2_v3_structonly`). A full fault cell (without `-Smoke`) needs
the parent table that the same arm learned in the healthy lab with the same seed and run
mode. The archived parent tables are not stored (only their checksum, `parent_qtable_sha256`
in each `ADAPT_OK_*.json`), so they have to be trained again with
`run_full_project.ps1 -RunMode phase1_v2_kg_only -OnlyProfiles <parent> -OnlyStereo <arm> -RunSeed <seed> -SkipBenchmark -SkipPreflight`.
With only that lab's tables in the root, the checksum of the new parent can be compared
with the archived `parent_qtable_sha256` before the fault cell is run.
The archived campaigns used the default fault-stage settings (settings profile `record`). Two runs of
the same fault smoke cell on the development computer gave identical files; a full fault
cell has not been compared with the archive locally.

**A Phase-3 cell.** The archived Phase-3 files are under
`results/phase3_v2_corrected/run_29926328852/dynamics_root/rep<N>/`. Because Phase 3
depends on timing (see above), compare the measured delays and the met deadlines, not the
bytes.

## Level 3: re-run a campaign on GitHub Actions

1. Use a copy of the repository on GitHub with Actions enabled (a fork, or the original
   repository with write access).
2. Look up the registered inputs in the campaign's dispatch record in
   `docs/registrations/` (the table in [README.md](README.md) names it) and start the
   same runs, for example:
   ```powershell
   gh workflow run phase1.yml --ref <branch> -f run_mode=phase1_v2_kg_only -f profiles=lab1,lab2,lab3 -f seeds=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20 -f publish_results=false
   ```
   `publish_results=false` keeps the run from pushing to the `results` branch. Respect the
   256-jobs-per-matrix limit by splitting seeds as the record did (see
   [EXPERIMENTS.md](EXPERIMENTS.md), section 5).
3. The archived runs were made at the commit recorded as `head_sha` in their
   `workflow_inputs.json`. Later changes to the runners were required to leave the result
   files of training, test and fault cells unchanged. This was checked on the development
   computer with short cells (a training cell, test cells of two labs, fault cells with two
   knowledge layers): the present code writes the same result files as the code at the tag
   `record-v2.5.0-pre-cleanup`, with two exceptions: receipts carry additional keys, and
   the learned Turtle files declare the corrected address of the `elem:` prefix (items 3
   and 6 of the allowed differences in
   [evidence/reproduction_check_2026-11/](evidence/reproduction_check_2026-11/README.md)).
   To exclude any doubt, dispatch from a branch created at the archived commit.
4. Download the consolidated artifact (`gh run download <run id> --name <artifact> --dir <folder>`)
   and compare its per-cell files with the archive with
   `analysis/compare_run_with_archive.py`, for example
   ```powershell
   py -3 analysis/compare_run_with_archive.py --run-dir <folder with one sub-folder per artifact> --phase phase1 --archive results/phase1_v2_corrected/run_29848584965 --out <output folder>
   ```
   (exit code 0 if no result data file differs; the script ignores line endings and allows
   only the differences listed in its docstring), or arrange the download like the archive
   and run the campaign's reproducer on it.

A whole-campaign re-run has not been carried out for this documentation; the equality of
individual cells is what level 2 shows. Seven small sample runs (seeds 1 and 2) of the
same workflows were started on 2026-11-16, once from the tag `record-v2.5.0-pre-cleanup`
(the code before the repository was tidied) and once from the tag `handin-rc1` (the tidied
code), and compared with the archives in this way. In both cases no result data file with
an archived counterpart has a real difference; what differs is one timing-dependent column,
receipt fields and, after the clean-up, the corrected address in the prefix line of the
learned Turtle files. Phase 3, which depends on measured timing, is compared by counts: the
program's choices agree with the archive, and one of 48 deadline flags of one of the
repeats differs because a lamp's effect was observed late, an event the archive also
contains. The evidence with all numbers and the commands to repeat it are in
[evidence/reproduction_check_2026-11/](evidence/reproduction_check_2026-11/README.md).
