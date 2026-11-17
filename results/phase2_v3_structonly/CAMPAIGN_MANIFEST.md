# Phase-2 structure-only fault-detection campaign (v3, 2026-11)

Registration: `docs/phase2_structonly_registration_2026-11-14.md` (BINDING,
commit `d43dd41b`; implementation freeze `c4334b2a`). Dispatch record:
`docs/phase2_structonly_dispatch_record_2026-11-14.md`. One registered
dispatch, `run_43590832041/` (4 cells × 2 arms × seeds 1–20, adaptation step
on `knowledge_layer = brick` in both arms, full-layer parents), 282/282 jobs
green on head `b5bf2146`. Registered outputs: `analysis/registered/
phase2_structonly_v3_wrongblame_family.csv`,
`phase2_structonly_v3_detection_family.csv`,
`phase2_structonly_v3_recovery_family.csv`, `phase2_structonly_v3_per_seed.csv`.

Reproduction: `python analysis/reproduce_phase2_structonly_v3.py
phase2_v3_structonly` validates the archive with the knowledge-layer AND
treatment-delivery gates enabled and rebuilds the four tables
byte-equivalently (canonical numeric form) from the committed archive alone.

## Why this campaign exists

The Phase-1 Brick-structure-only arm and the Phase-4 knowledge ladder showed
Brick structure alone reproduces most of the reward-shaping effect, leaving
the Elementary physics layer without a measured positive role there. Addendum
2026-11-10 named the sharper test in advance: on a dead-component lab the
Brick arm cannot abstain on IV-gated components and is predicted to blacklist
healthy blinds in the dark. This campaign is that test: the same four
archived fault cells, the adaptation JVM switched to the Brick layer, paired
seed-by-seed against the full-layer runs of record.

## Registered outcome (n = 20 per member, exact sign-flip, BH m = 8 per family)

**WrongBlameEvents family (PRIMARY, P1): 2 elementary_adds, 0 costs, 6 null.**

| Cell | Arm | Mean SO | Mean rec | Δ (SO − rec) | 95% CI | Exact p | BH q (m=8) | Verdict |
|---|---|---:|---:|---:|---|---:|---:|---|
| lab2_f1bdead | ql_true | 0.40 | 0.00 | +0.40 | [0.20, 0.60] | 7.81×10⁻³ | 0.0313 | **elementary_adds** |
| lab2_f1bdead | ql_false | 0.85 | 0.00 | +0.85 | [0.70, 1.00] | 1.53×10⁻⁵ | 1.22×10⁻⁴ | **elementary_adds** |
| lab3_f1bdead | ql_true | 0.00 | 0.00 | 0.00 | [0.00, 0.00] | 1.0 | 1.0 | null |
| lab3_f1bdead | ql_false | 0.25 | 0.00 | +0.25 | [0.10, 0.45] | 0.0625 | 0.167 | null |
| lab3_f2dead_lowsun | both | 0.00 | 0.00 | 0.00 | [0.00, 0.00] | 1.0 | 1.0 | null |
| labmon2_f2dead_lowsun | both | 0.00 | 0.00 | 0.00 | [0.00, 0.00] | 1.0 | 1.0 | null |

**DetectEpisode family (no directional registration): 2 elementary_adds, 0
costs, 6 null** — `lab3_f1bdead/ql_true` Δ = +3996.00 (q = 1.53×10⁻⁵; the
structure-only arm NEVER detects the dead blind, 20/20 censored, vs 20/20
probe-driven detections in the record), `lab3_f1bdead/ql_false` Δ = +2197.40
(q = 3.34×10⁻³; 11/20 never detect vs 0/20). `lab2_f1bdead/ql_false` is the
only negative-direction member (Δ = −1.40, q = 0.123, null: unsound ungated
adjudication detects the dead blind slightly earlier). Low-sun members all
Δ = 0.

**RecoveryEpisodes family: 2 elementary_adds, 0 costs, 6 null** — the same
two lab3_f1bdead members (Δ = +3884.95, q = 1.53×10⁻⁵; Δ = +2142.65,
q = 8.62×10⁻⁴), mechanically coupled to the detection result (never detected
⇒ never recovered; shared censoring). `lab2_f1bdead/ql_true` Δ = +202.80
(q = 0.0869, null).

Reading (registration §§1, 10): **P1 is confirmed on 2/8 members** — both
arms of `lab2_f1bdead`, where removing the physics layer produces wrong
blacklists of a healthy component against zero false positives in the runs of
record. The registered expectation that the two pinned low-sun members would
be the cleanest exposure was **not borne out**: those members show zero wrong
blames and bit-identical behaviour (below); the exposure materialised in the
mixed-sun dead-blind cells instead. Detection and recovery (registered
two-sided) add the sharper result: under the warm-start policy the physics
layer's active self-test is the ONLY channel that finds the dead blind —
without it, 0/20 seeds ever detect, and the re-learn never recovers the
best-effort policy.

## Descriptives (registered, no q)

- **Component histogram (P1b):** every one of the 30 wrong-blame events names
  the healthy co-blind `SetZ2Blinds` (lab2/ql_true 8, lab2/ql_false 17,
  lab3/ql_false 5); the records have 0 across all four cells and both arms.
  The injected `SetZ1Blinds` accounts for every other f1bdead event. In the
  low-sun members P1b is vacuous (zero wrong blames on either side). The
  registered prediction placed the wrong blames in the low-sun members; they
  appeared in the mixed-sun members, whose schedules contain the dark and
  mid-sun episodes where an ungated healthy-blind adjudication can fail.
- **Detection counts (structonly vs record):** lab2 ql_true 18/20 vs 20/20,
  ql_false 20/20 vs 20/20; lab3 ql_true 0/20 vs 20/20, ql_false 9/20 vs
  20/20; low-sun members identical to record (lab3 0/20 and 11/20; labmon2
  14/20 and 20/20).
- **Low-sun bit-invariance:** all 80 structure-only low-sun recovery CSVs are
  byte-identical to the record's files (20/20 per member), and the per-cell
  adapt-log blacklist line counts match the archive histograms on all 8
  members. Mechanism, from the frozen code: at permanent sun rank 1 the full
  layer's blind machinery never acts (both the IV-gate adjudication and the
  active probe require sun rank ≥ 2, `QLearner.IV_DETECT_MIN_SUN_RANK`), and
  dead-LAMP adjudication is layer-independent — so the Brick switch removes
  only machinery that was already inactive, and the deterministic seeded
  trajectories coincide bit-for-bit. The adverse low-sun record result is
  therefore unchanged under the structure-only layer.
- **Parent identity:** `parent_qtable_sha256` identical to the record cell's
  in 160/160 manifests (20/20 on every member) — both sides of every pair
  warm-start from byte-identical full-layer parents, bounding the disclosed
  cross-head comparison (§5).
- **Treatment delivery:** 160/160 manifests `knowledge_layer =
  knowledge_layer_delivered = brick` (run-time log-verified);
  `verification/adapt_log_knowledge_layer_evidence.csv` carries the per-cell
  banner evidence. Low-sun pin delivered 80/80 with the round-3 pinned-file
  hashes (`119dc755…` / `8e9f5467…`).
- **Degradation (A1 rule, conditional on detection):** every detected
  structure-only low-sun record enters degraded mode at best-effort rank 2,
  shortfall 1 (11/11, 14/14, 20/20; lab3/ql_true has no detected records) —
  identical to the record by bit-invariance.

## Not covered

No cell of the 2026-07 corrected record or the 2026-11 low-sun record is
re-analysed or reclassified; the structure-only condition is a new column of
the record. The `redundancy` layer position of the ladder was not dispatched
for Phase 2 (out of scope of the registration). No numerical comparison with
the Phase-1/Phase-4 ladder campaigns is confirmatory (registration §10).
