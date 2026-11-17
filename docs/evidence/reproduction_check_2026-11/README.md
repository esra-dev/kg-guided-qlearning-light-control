# Reproduction check, November 2026: do today's GitHub runs still produce the archived files?

## What was done, and why

The result archives under `results/` were produced by GitHub Actions runs made months
ago. Nobody can look at those runs again except through the files they left. This check
asks a simple question: if the same workflow is started again today, on today's GitHub
machines, with the same inputs and the code of the same commit, does it write the same
result files?

On 2026-11-16 seven small sample runs were started from the git tag
`record-v2.5.0-pre-cleanup` (commit `6dd6a506bc2d79a06fa6d6bb09295c3c6472c351`, the state of
the code before the repository was tidied for the hand-in), with seeds 1 and 2 only and
with `publish_results=false`, so that nothing was written to the `results` branch. Each run
repeats a part of one archived campaign. The artifacts of each run were downloaded and
every result data file (`.csv`, `.json`, `.jsonl`, `.ttl`) was compared with its
counterpart in the archive by [`analysis/compare_run_with_archive.py`](../../../analysis/compare_run_with_archive.py).
Log files are not compared.

Result, in one sentence: every result data file that has an archived counterpart is
identical to it, except for one timing-dependent column and for fields of the run receipts
(both explained below); no value in any result table differs. For Phase 3, whose files
depend on measured delays, counts and choices were compared instead of bytes.

The same runs were then repeated from the tidied code (tag `handin-rc1`); that comparison
is the section "After the clean-up" at the end of this page.

The per-file outputs are in `before_cleanup/run_<id>/`: `summary.md` (totals, per file kind,
per cell, one example per kind of difference), `compare.csv` (one row per file, with the
archive path relative to the repository root and the SHA-256 of the run file) and, for
Phase 3, `phase3_compare.csv`.

## The seven runs

All dispatched on 2026-11-16 from `record-v2.5.0-pre-cleanup`. "Files" counts the result
data files of the run. Statuses are defined in the next section.

| Run id | Workflow and inputs | Archive compared with | Files | Identical | Only allowed fields differ | Receipt with added keys | Not in archive | Real difference |
|---|---|---|---|---|---|---|---|---|
| 43829803537 | `phase1.yml`, `run_mode=phase1_v2_kg_only`, `profiles=lab2,lab3`, `seeds=1,2` | `results/phase1_v2_corrected/run_29848584965` | 480 | 416 | 24 | 8 | 32 | 0 |
| 43829817064 | `phase1.yml`, `run_mode=phase1_v2_brick_only`, `profiles=lab2,lab3`, `seeds=1,2` | `results/phase1_v2_corrected/run_42834963612` | 480 | 416 | 24 | 8 | 32 | 0 |
| 43829830608 | `phase2.yml`, `adapt_profiles=lab2_f1bdead,lab3_f1dead,lab3_f2dead_lowsun`, `seeds=1,2`, `knowledge_layer=full` | `results/phase2_v2_corrected/run_30001857104` (lab2_f1bdead), `run_30001878310` (lab3_f1dead), `results/phase2_v3_lowsun/run_43099277363` (lab3_f2dead_lowsun); the clean cells against the first two | 152 | 120 | 0 | 20 | 12 | 0 |
| 43829843668 | `phase2.yml`, `adapt_profiles=lab2_f1bdead`, `seeds=1,2`, `knowledge_layer=brick` | `results/phase2_v3_structonly/run_43590832041` | 68 | 64 | 4 | 0 | 0 | 0 |
| 43829856902 | `phase4.yml`, `profiles=lab4,lab5`, `seeds=1,2`, `run_mode=phase4_v2` | `results/phase4_v2_corrected/run_29926341581` | 480 | 416 | 24 | 8 | 32 | 0 |
| 43829870117 | `phase4.yml`, `profiles=lab4,lab5`, `seeds=1,2`, `run_mode=phase4_v2_brick_only` | `results/phase4_v2_ladder/run_43494914019` | 480 | 416 | 32 | 0 | 32 | 0 |
| 43829883553 | `phase3.yml`, `dynamics_profiles=lab2_slow,lab3_slow`, `replicas=1,2`, `probes=0` | `results/phase3_v2_corrected/run_29926328852` | counts, see below | | | | | 0 |

Every per-cell job of the seven runs succeeded. The aggregate job of the two Phase-1 runs
reported failure, as expected: its validator requires the 20 seeds of the full campaign and
the runs had two.

## The allowed differences, and why each is harmless

The script allows exactly the following differences; anything else counts as a real
difference and makes the script exit with a non-zero code.

1. **Column `LegacyWallClockTotalEnergyCost` in `benchmark_results_<mode>.csv`** (status
   *only allowed fields differ*; 24 files in each of the four Phase-1 and Phase-4 runs in
   which test cells are present). The repository documents this column as a timing-dependent
   simulator diagnostic that is not used in any analysis. All other columns of those files
   are identical, including `PolicyEnergyCost`, and the matching `bench_step_log_*.csv`
   files are identical. Example: `bench-lab2-ql_false-seed-1`, 48 of 80 rows differ in this
   column only. The cause of the differing values was not investigated; the column was
   reported as it is.
2. **Receipt key `timestamp`** in `TRAINING_OK.json` (status *only allowed fields differ*;
   4 files in run 43829843668 and 8 files in run 43829870117). The time at which the cell
   ran. In these two runs nothing else in the receipt differs.
3. **Receipts that gain keys** (status *receipt with added keys*; 8 + 8 + 20 + 8 files).
   Code written after the archives were made records additional settings in the receipts of
   the training and adaptation cells (for example `stereo_requested_props.*`,
   `stereo_effective_props.*`, `knowledge_layer`, `knowledge_layer_delivered`,
   `scenario_file_sha256`). The script compares receipts key by key: every key present in
   both the archived and the new receipt has the same value (`timestamp` excepted), and the
   archived receipt is a subset of the new one. A key that exists only in the archive, or a
   changed shared value, would be a real difference.
4. **Files without an archived counterpart** (status *not in archive*; 32 per Phase-1 or
   Phase-4 train run: the initial Q-tables `qtable_initial_*`; 12 in the Phase-2 full run:
   the adapted Q-tables `qtable_adapted_*`). The archives hold no such files, so nothing
   can be compared; the new files are not data of any reported result.
5. **Line endings.** Archives were written on Linux; a checkout or a download on Windows
   can end lines with CRLF instead of LF. Every comparison is made after turning CRLF into
   LF, and a file that differs only in that way gets its own status (it would not count as a
   difference). No such file occurred.
6. **The address in the `@prefix elem:` line of a learned Turtle file** (status *only
   allowed fields differ*; possible only in runs made after the vocabulary correction,
   that is in the runs of the section "After the clean-up"; none in the seven runs above).
   The files `learned_stereotypes_<arm>_<lab>.ttl` declare the prefix `elem:` and use no
   `elem:` term. The archives were written when the code declared the address
   `http://w3id.org/elementary#`; the code now declares `http://www.w3id.org/elementary#`,
   the address the published Elementary ontology gives (see
   [`docs/VOCABULARY.md`](../../VOCABULARY.md)). The script accepts a Turtle file whose
   only differing lines are `@prefix elem:` declarations and which uses the prefix nowhere
   else, so that every triple is the same; a file that uses the prefix would count as a
   real difference.

Not allowed and not found: a changed number in any result table, a different row count or
header, a file the archive holds but the run did not produce.

## Phase 3 (run 43829883553): counts and choices

Phase 3 measures real delays of simulated devices, so its files cannot be byte-identical. For
each of the 8 cells (2 labs x 2 modes x 2 replicas) and each of the 6 goals (48 goals) the
script compares the deadline flag and the chosen device with the archive replica of the same
number:

- the deadline flag is identical in 48 of 48 goals;
- the chosen device is identical in 45 of 48 goals;
- in the other three (goals g3, g4, g6 of `lab3_slow`, `ql_true`, replica 2) the new run chose
  `SetZ1Blinds=ON` where archive replica 2 chose `SetZ2Blinds=ON`; across all 10 archive
  replicas these goals are split 7 times `SetZ1Blinds=ON` and 3 times `SetZ2Blinds=ON`, so
  the new choice lies within the archive's own spread between replicas;
- the learned delay values are measured and equal in 14 of 84 rows; they are reported, not
  judged (the values stay in the same class: about 60 s for blinds, 5 to 7.5 s for lights).

## Repeating the check

1. Start a run from a tag or branch (no results are published with `publish_results=false`);
   the inputs of each run are in the table above, for example:
   ```bash
   gh workflow run phase1.yml --ref <tag or branch> -f run_mode=phase1_v2_kg_only \
     -f profiles=lab2,lab3 -f seeds=1,2 -f publish_results=false
   gh workflow run phase2.yml --ref <tag or branch> -f adapt_profiles=lab2_f1bdead \
     -f seeds=1,2 -f knowledge_layer=brick -f publish_results=false
   gh workflow run phase3.yml --ref <tag or branch> -f dynamics_profiles=lab2_slow,lab3_slow \
     -f replicas=1,2 -f probes=0 -f publish_results=false
   ```
   (`phase4.yml` takes `profiles`, `seeds`, `run_mode`.) Find the run id with
   `gh run list --workflow phase1.yml --limit 3`.
2. Download the per-cell artifacts of the finished run, one folder per artifact (the
   `build-classes` and `*-consolidated` artifacts are not needed):
   ```bash
   gh run download <run id> -n <artifact name> -D <run folder>/<artifact name>
   ```
   or let the script do it with `--download <run id> --repo <owner/name> --run-dir <run folder>`.
3. Compare, for example for the first run of the table, from the repository root (the
   archive bulk files must be present, see `results/README.md`):
   ```bash
   py -3 analysis/compare_run_with_archive.py --run-dir <run folder> --phase phase1 \
     --archive results/phase1_v2_corrected/run_29848584965 \
     --out docs/evidence/reproduction_check_2026-11/after_cleanup/run_<run id> --run-id <run id>
   ```
   Phase 2 with several fault labs pins each lab to its archive run
   (`--archive lab2_f1bdead=results/phase2_v2_corrected/run_30001857104 --archive lab3_f1dead=...`;
   plain `--archive` folders serve the clean cells). Phase 3 uses `--phase phase3`. When the
   archives are in another checkout, add `--archive-root <that checkout>`; printed paths
   stay repository-relative. The exit code is 0 if no file has a real difference.

The result of this check is `before_cleanup/`: the comparison of the saved artifacts with
the archives as they are checked in under `results/` (the archive bytes are those of
`record-v2.5.0-pre-cleanup`). The downloaded artifacts themselves are not part of the
repository.

## After the clean-up

The same seven runs were repeated on 2026-11-16 from the tidied code: git tag `handin-rc1`
(commit `0c5352ba`), again with seeds 1 and 2 and `publish_results=false` (the Phase-3
run twice, for the reason given below), and compared
with the same archives by the same script. Result of the six runs that are compared file by
file: no file with a real data difference. Phase 3 is compared by counts and is reported
below the table.

| Run id | Workflow and inputs | Archive compared with | Files | Identical | Only allowed fields differ | Receipt with added keys | Not in archive | Real difference |
|---|---|---|---|---|---|---|---|---|
| 43942438024 | `phase1.yml`, `run_mode=phase1_v2_kg_only`, `profiles=lab2,lab3`, `seeds=1,2` | `results/phase1_v2_corrected/run_29848584965` | 480 | 376 | 64 (of these 40 learned Turtle files, prefix line) | 8 | 32 | 0 |
| 43942500390 | `phase1.yml`, `run_mode=phase1_v2_brick_only`, `profiles=lab2,lab3`, `seeds=1,2` | `results/phase1_v2_corrected/run_42834963612` | 480 | 376 | 64 (of these 40 learned Turtle files, prefix line) | 8 | 32 | 0 |
| 43942561231 | `phase2.yml`, `adapt_profiles=lab2_f1bdead,lab3_f1dead,lab3_f2dead_lowsun`, `seeds=1,2`, `knowledge_layer=full` | `results/phase2_v2_corrected/run_30001857104` (lab2_f1bdead), `run_30001878310` (lab3_f1dead), `results/phase2_v3_lowsun/run_43099277363` (lab3_f2dead_lowsun) | 152 | 112 | 8 (of these 8 learned Turtle files, prefix line) | 20 | 12 | 0 |
| 43942632190 | `phase2.yml`, `adapt_profiles=lab2_f1bdead`, `seeds=1,2`, `knowledge_layer=brick` | `results/phase2_v3_structonly/run_43590832041` | 68 | 56 | 4 (of these 4 learned Turtle files, prefix line) | 8 | 0 | 0 |
| 43942696584 | `phase4.yml`, `profiles=lab4,lab5`, `seeds=1,2`, `run_mode=phase4_v2` | `results/phase4_v2_corrected/run_29926341581` | 480 | 376 | 64 (of these 40 learned Turtle files, prefix line) | 8 | 32 | 0 |
| 43942761735 | `phase4.yml`, `profiles=lab4,lab5`, `seeds=1,2`, `run_mode=phase4_v2_brick_only` | `results/phase4_v2_ladder/run_43494914019` | 480 | 376 | 64 (of these 40 learned Turtle files, prefix line) | 8 | 32 | 0 |
| 43942827015 | `phase3.yml`, `dynamics_profiles=lab2_slow,lab3_slow`, `replicas=1,2`, `probes=0` | `results/phase3_v2_corrected/run_29926328852` | counts, see below | | | | | see below |
| 43961243896 | `phase3.yml`, `dynamics_profiles=lab2_slow,lab3_slow`, `replicas=1,2`, `probes=0` | `results/phase3_v2_corrected/run_29926328852` | counts, see below | | | | | see below |

What differs from the comparison before the clean-up, and why:

- **Learned Turtle files** (172 files in all runs together) now fall under *only allowed
  fields differ*: their line `@prefix elem:` carries the corrected address (item 6 above). They
  use no `elem:` term, so their triples are identical to the archived ones.
- **Receipts** of every training and fault cell now carry further keys (the settings the
  program reports back, the settings profile, the exact parent files, the lab the simulator
  reported); every key of the archived receipt is present with the same value.
- The wall-clock column differs in 96 test result files, as before (item 1 above).

Phase 3 (counts and choices, as above):

- Run 43942827015 (verdict of the script: FAIL):
  - Phase 3 measures real device delays, so bytes differ; the deadline flag and the chosen device are compared per goal
  - goals compared: 48 in 8 cells
  - deadline flag (`met`) identical: 47 of 48
  - chosen device identical to the archive replica of the same number: 48 of 48
  - chosen device different from that replica but chosen by the archive in another replica of the same goal: 0
  - chosen device never chosen by the archive for that goal: 0
  - learned-delay values equal to the archive replica (goal rows plus delay-table rows): 32 of 84 (measured values, reported not judged)
- Run 43961243896 (verdict of the script: PASS):
  - Phase 3 measures real device delays, so bytes differ; the deadline flag and the chosen device are compared per goal
  - goals compared: 48 in 8 cells
  - deadline flag (`met`) identical: 48 of 48
  - chosen device identical to the archive replica of the same number: 45 of 48
  - chosen device different from that replica but chosen by the archive in another replica of the same goal: 3
  - chosen device never chosen by the archive for that goal: 0
  - learned-delay values equal to the archive replica (goal rows plus delay-table rows): 30 of 84 (measured values, reported not judged)

About the first Phase-3 repeat (run 43942827015): the chosen device is the same as in the archive in all 48 goals, and the deadline flag differs in one, goal `g5` of `lab3_slow`, `ql_true`, replica 1 (15 s deadline). The run chose the lamp `SetZ2Light=ON`, as the archive replica did, but observed the lamp's effect after 20 s (4 ticks) instead of 5 s, so the deadline was missed. This is a variation of the measured timing, not of the program's decisions. The archive contains the same event: in `results/phase3_v2_corrected/run_29926328852/dynamics_root/rep7/timebounded_results_true_lab2_slow.csv`, goal `g5`, the same lamp is chosen with a believed delay of 5.63 s, its effect is observed after 20.00 s and the deadline is missed; this is the one missed deadline among the 60 goals of `lab2_slow`, `ql_true` in the archive. In the comparison before the clean-up a lamp took 20 s once as well (run 43829883553, `lab2_slow`, `ql_true`, replica 2, goal `g2`; its deadline of 45 s was still met). Of the 60 goals of the archive in which a lamp was chosen, the effect was observed after 5 s in 47, after 10 s in 12 and after 20 s in 1. The script counts every deadline flag that differs from the archive replica of the same number as a failure, so it reports FAIL for this run; the rule was not changed. Because of this difference a second Phase-3 repeat was started from the same tag (run 43961243896); both are reported here.

The per-file outputs are in `after_cleanup/run_<id>/` (`summary.md`, `compare.csv`, for
Phase 3 `phase3_compare.csv`).
