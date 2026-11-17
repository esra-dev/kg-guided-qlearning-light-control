# Development history

This is a short account of how the project developed, for a reader who meets older
documents, branch names or result folders and wants to place them. Dates come from the git
history, the git tags and the documents in `docs/registrations/`, `docs/notices/` and
`docs/reports/`. Every document named here that is no longer in the tree is kept at the git
tag `record-v2.5.0-pre-cleanup`. Results from before 21 July 2026 are withdrawn and are not
repeated here; the accepted results are described in [../results/README.md](../results/README.md).

## 1. First prototype and the custom labs (March to early June 2026)

- **March.** The repository starts with a rule-based agent on the JaCaMo platform that
  controls the light of a two-zone simulated room and reads a knowledge graph in Brick and
  Elementary terms with SPARQL (first commit 2026-03-17).
- **April.** A custom simulated lab is built in Node-RED (2026-04-07), and a tabular
  Q-learning agent is added with a switch between learning with and without the stereotype
  knowledge (2026-04-08).
- **May.** Four-zone "weakness labs" are built (first flows committed on 2026-05-12):
  `custom2` to `custom7`, later `custom8`, `custom9` and `custom9s`. Each disagrees with
  its knowledge graph in one hidden way, to test
  how a learner copes when the knowledge is wrong. A first pre-registration of hypotheses
  is committed on 2026-05-24 (`docs/registrations/pre_registration.md`), and sweeps of all
  labs run on GitHub Actions (the `results-202605*` and `results-202606*` tags).
- **Change of direction (June).** Testing knowledge about a healthy room in rooms that
  contradict it was given up. The project was reorganised into separate questions, which
  became the phases: in healthy rooms, does knowledge speed up learning (Phase 1); when a
  device breaks, does the agent detect it, take it out of use and learn again (Phase 2);
  can the agent learn something the graph does not contain, a response delay (Phase 3).
  No accepted campaign uses the custom labs. They, the lab `lab2noise`, the run modes of
  that time and the rule-based demonstration agent of March were removed for the hand-in
  (section 6); they are kept at the git tag `record-v2.5.0-pre-cleanup`.

## 2. The registered phases (June to mid-July 2026)

- **Small healthy rooms.** lab1, lab2 and lab3 add one difficulty each (one lamp; blinds
  that need sun; light spilling between zones and a shared spotlight). Phase 1 is committed
  on 2026-06-10, Phase 2 on 2026-06-13, Phase 3 is merged on 2026-06-16 (pull request #2)
  and Phase 4 (plugs that gate lamps, and energy costs) on 2026-06-21 (pull requests #3 and
  #4). Their hypotheses were added to `pre_registration.md` as addenda.
- **The fault detector** of Phase 2 went through several designs in June and July
  (recovery defined by a stable policy, a closing goal-rate check, detection on the first
  clear observation, an active test of blinds, labs with a monitor as an emergency light
  source); the commit messages of 2026-06-14 to 2026-07-07 record the steps.
- **Action list from the device contract (2026-07-10).** Until then the actions were
  derived from the stereotypes in the knowledge graph. From this commit they come from the
  WoT mapping of every device, in both arms, and the stereotypes only add information
  (`gradlew verifyActionRegistry` guards this). Earlier result files were retired.

## 3. The defective first protocol and the withdrawal (19 to 22 July 2026)

An audit of Phase 1 that started on 2026-07-19 found that the way training had been run,
later called *protocol v1*, was defective. The notices in `docs/notices/` (Phase 1 on
2026-07-21; Phases 2, 3 and 4 on 2026-07-22) state the defects:

- the training and fault-stage loops asked for start situations by consecutive numbers,
  although the scenario files do not number them without gaps; a missing number silently
  produced an unseeded random start (according to the Phase-1 notice in 16.7 % of the lab1,
  27.3 % of the lab2 and 30.0 % of the lab3 training episodes), so the two arms of a seed
  did not see the same episodes;
- the start state was read before the simulator had applied it;
- the first-success measure averaged over different sets of states in the two arms;
- energy was added up on a wall-clock timer;
- a bootstrap was used as a test and could print p = 0;
- in Phase 2, the detector could blame healthy devices whose zone was held by another
  active light source.

All results of all phases up to that date were withdrawn, independent of what corrected
runs would show. A corrected *protocol v2* was written: start situations taken from the
file in order, a settle time before reading the start state, a fixed number of 3,000
training episodes, the same random stream in both arms, energy counted per decision, and a
paired sign-flip test. New registrations were committed before any corrected data existed
(`phase1_correction_registration_2026-07-21.md`,
`phase2_correction_registration_2026-07-22.md`, `phase3_correction_registration_2026-07-22.md`,
`phase4_correction_registration_2026-07-22.md`).

## 4. The corrected campaigns (late July to August 2026)

- **Phases 1 to 4 under protocol v2.** The corrected Phase 1 ran on 2026-07-21 and was
  merged on 2026-07-22 (pull request #5); the corrected Phases 2, 3 and 4 were dispatched
  on 2026-07-22 (Phases 3 and 4) and 2026-07-23 (Phase 2, after two failures before any data,
  recorded in `phase2_v2_dispatch_record_2026-07-22.md`) and merged on 2026-07-24 (pull
  request #6). These are the
  archives `phase1_v2_corrected`, `phase2_v2_corrected`, `phase3_v2_corrected` and
  `phase4_v2_corrected`. The corrected Phase 1 already had four run modes: full knowledge,
  the redundancy rule alone (a control arm that uses no knowledge graph), and two checks
  (no knowledge in either arm; reward shaping in both arms).
- **Phase 1b (25 to 27 July).** Purpose-built rooms (`labrel*`, `labband`, `lab4chain3`)
  ask which kind of knowledge matters. A pilot on separate seeds fixed the power analysis
  (`PHASE1B_POWER_PROTOCOL.md`), then the confirmatory campaign ran (`phase1b_corrected`,
  merged 2026-07-27, pull request #7).
- **Two exploratory analyses.** A retrospective study of the trained Phase-2 tables
  (protocol frozen on 2026-07-27, `PHASE2_QGAP_RETROSPECTIVE.md`) and a study of truncated
  learning curves (registered on 2026-08-12), both on existing data and both marked as
  exploratory (`results/exploratory/`).

## 5. Delivery checks and control arms (November 2026)

Audits of the finished record (the registrations of 2026-11-11 call them round 1 and
round 2) found that two registered conditions had never reached the running program: the three settings of the Phase-1b "extended" arm were not on the list of
settings that `build.gradle` forwards, so that arm ran as the ordinary knowledge arm; and
the "low sun" condition of two Phase-2 cells lived only in the simulator's random reset,
which protocol v2 never calls. Both were registered again and re-run with checks that read
the delivered values back from the program's log (registrations of 2026-11-11; archives
`phase1b_extended_v3` and `phase2_v3_lowsun`; merged 2026-11-12, pull request #8).

Three campaigns then separated the parts of the knowledge with control arms:

- the Brick-structure-only arm for Phase 1 (registration 2026-11-10, run in
  `phase1_v2_corrected`, merged 2026-11-13, pull request #9);
- the knowledge ladder for Phase 4, with a redundancy-rule arm and a structure-only arm
  (registration 2026-11-13, `phase4_v2_ladder`, merged 2026-11-14, pull request #10);
- the structure-only fault stage for Phase 2 (registration 2026-11-14,
  `phase2_v3_structonly`, merged 2026-11-15, pull request #11).

## 6. Clean-up for the hand-in (16 November 2026)

The complete state at that point was tagged `record-v2.5.0-pre-cleanup`. Afterwards the
result archives were moved under `results/`, a data-driven checker (`analysis/check_pins.py`
with `results/pins/`) replaced the earlier provenance checker, the withdrawn result folders,
working notes and the sweep workflows were removed, and the remaining documents were sorted
into `docs/registrations/`, `docs/notices/`, `docs/reports/` and `docs/evidence/`. The labs
that no experiment of record uses (`custom`, `custom_full_train`, `custom2` to `custom9`,
`custom9s`, `lab2noise`) with their flows, scenario files and Thing Descriptions, the run
modes of the time before the corrected protocol (`paper`, `paper_h40`, `paper_h60`,
`phase1`, `phase1_baseline`, `phase1_kg_only` and its variants, `phase1_pbrs_only`,
`phase1_full`, `phase1_kg_xzone`, `phase1_redundancy_only`, `phase4`), the demonstration
agent (`illuminance_controller_agent.asl`, `task.jcm`, Gradle task `task`), the reference
action lists from before the action-list change (`config/golden_registry/pre_inversion/`)
and the four-zone ontology set `custom2` (`lab-ontology-custom2.ttl`,
`wot-mappings-custom2.ttl`, `registry_custom2.csv`) were removed as well. The project's own
terms that had used the `elem:` prefix were moved to the project's `ws:` prefix, and
`elem:` was bound to the address of the published Elementary ontology (see
[VOCABULARY.md](VOCABULARY.md)).

## 7. How the work was organised

- **Registration before data.** From 21 July on, every campaign was registered in a
  committed document before it was dispatched: question, labs, arms, seeds, exact workflow
  inputs, outcome measures, analysis script and decision rules. Each registration states
  when it was written relative to its data; registrations written after data had been seen
  say so, and exploratory work is marked as such.
- **Dispatch records.** Each dispatch is recorded with run ids, head commit and inputs;
  failures before any data and re-runs are added as amendments, and a cell that produced
  data is never replaced.
- **Archives with checksums.** Each run's consolidated artifact is downloaded into an
  archive folder with a SHA-256 inventory (`SHA256SUMS.csv`), manifests, and an archive
  validator that rejects missing cells or receipts.
- **Reproduction scripts and pins.** For every accepted campaign a reproduction script
  recomputes the registered tables from the archived per-cell data, and a pins file fixes
  the key facts; CI runs both.
- **Pull requests and CI.** From Phase 3 on, each phase and each later campaign was
  developed on its own branch and merged into `main` through a pull request after the CI workflow (`ci.yml`: build, unit tests, Python
  tests, resource checks, smoke training) had passed.
- **Corrections in the open.** When an error was found, the affected results were withdrawn
  or marked in notices and registrations, and corrected runs replaced them, whether the
  corrected result was favourable or not.
