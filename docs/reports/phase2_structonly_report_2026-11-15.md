# Phase 2: report on the structure-only fault-detection campaign (2026-11-15)

> **Stand-alone copy of a report that was first published inside the project's state report.**
> Source: section "Addendum 2026-11-15 — REPORT: Phase-2 structure-only fault-detection campaign executed — the physics layer earns a measured protective role, and the predicted low-sun exposure is behaviourally inert" of `docs/_audit/THESIS_STATE_REPORT.md`, lines 3796 to 3928 at commit `6dd6a506` (tag `record-v2.5.0-pre-cleanup`).
> The section heading was first committed in `3753f16b` (2026-11-15T09:32:57Z). No line of the section was changed by a later commit.
> Run reported here: 43590832041. Its registration is the stand-alone file `phase2_structonly_registration_2026-11-14.md`.
> The text below is copied word for word; nothing was edited. Section signs (§) and the word "Addendum" refer to the state report, which is available at the tag named above.

## Addendum 2026-11-15 — REPORT: Phase-2 structure-only fault-detection campaign executed — the physics layer earns a measured protective role, and the predicted low-sun exposure is behaviourally inert

This addendum reports the campaign registered in Addendum 2026-11-14b. The full
tables, the delivery evidence, and the mechanism notes are in
`phase2_v3_structonly/CAMPAIGN_MANIFEST.md`; that manifest is the canonical
text and this addendum records the outcome and its consequence for the thesis.

**Execution.** One dispatch of `phase2.yml` from head `b5bf2146`, which
contains the registration commit: run `43590832041` (4 cells × 2 arms × seeds
1–20 on `knowledge_layer=brick`, full-layer parents trained in-run). All 282
jobs concluded successfully on the first attempt. The consolidated artifact
(id `14141229360`, digest-verified) and the 160 per-cell adapt logs were
downloaded and archived under `phase2_v3_structonly/run_43590832041/`; the
archive passes `analysis/validate_phase2_v2_archive.py` with BOTH strict gates
(`--require-treatment-delivery --require-knowledge-layer brick`), and the
results-branch snapshot `phase2/43590832041-20261115-020615` is verified on
the remote (the Phase-2 workflow creates no results tag, the disclosed
long-standing mechanism). The registered script
`analysis/phase2_structonly_v3_family.py` ran once, unchanged, and
`analysis/reproduce_phase2_structonly_v3.py` rebuilds the four committed
tables byte-equivalently.

**Delivery and comparability.** All 160 ADAPT_OK manifests record
`knowledge_layer = brick` AND the log-verified `knowledge_layer_delivered =
brick`; the per-cell banner evidence is
`phase2_v3_structonly/run_43590832041/verification/adapt_log_knowledge_layer_evidence.csv`,
and its independently counted per-member blacklist log lines match the
archive-derived event counts on all 8 members. The two low-sun cells carry the
round-3 pinned schedules (80/80 manifests `lowsun_pin_delivered=true`,
file hashes `119dc755…`/`8e9f5467…`). Parent `parent_qtable_sha256` is
identical to the record cell's in 160 of 160 manifests, so both sides of every
pair warm-start from byte-identical full-layer parents; this bounds the
disclosed cross-head pairing of registration §5.

**Outcome under the pre-committed rule.** Source:
`phase2_v3_structonly/analysis/registered/` (three m=8 families, BH within
family; elementary_adds iff q ≤ 0.05 and Δ = structonly − record > 0). Every
family counts 2 adds, 0 costs, 6 null.

- WrongBlameEvents family (PRIMARY, P1) of `phase2_v3_structonly/`:
  - lab2_f1bdead/ql_true: +0.40 wrong blames per seed, interval
    [+0.20, +0.60], BH q 0.03125, category `elementary_adds`;
  - lab2_f1bdead/ql_false: +0.85, interval [+0.70, +1.00], BH q 1.221e-04,
    category `elementary_adds`;
  - lab3_f1bdead/ql_false: +0.25, interval [+0.10, +0.45], BH q 0.1667,
    category `null`; the remaining five members are exactly 0 on every seed.
  - P1 is confirmed on 2 of 8 members. Licensed wording: "without the physics
    layer the detector blacklists healthy components on lab2_f1bdead, in both
    arms, against zero false-positive events in the runs of record."
- DetectEpisode family (registered two-sided) of `phase2_v3_structonly/`:
  - lab3_f1bdead/ql_true: +3996.00, interval [+3996.00, +3996.00], BH q
    1.526e-05, category `elementary_adds` — the structure-only arm NEVER
    detects the dead blind (20/20 censored, 0 blacklist events) where the
    record detects in 20/20 seeds at mean episode 5.0 (probe-driven);
  - lab3_f1bdead/ql_false: +2197.40, interval [+1397.65, +2997.05], BH q
    3.342e-03, category `elementary_adds` (9/20 detect vs 20/20);
  - lab2_f1bdead/ql_false: −1.40, interval [−2.55, −0.20], BH q 0.1225,
    category `null` (the only negative-direction member: ungated unsound
    adjudication finds the dead blind slightly earlier);
  - the four low-sun members are exactly 0 on every seed.
- RecoveryEpisodes family of `phase2_v3_structonly/`:
  - lab3_f1bdead/ql_true: +3884.95, interval [+3851.75, +3914.45], BH q
    1.526e-05, category `elementary_adds`;
  - lab3_f1bdead/ql_false: +2142.65, interval [+1354.05, +2929.65], BH q
    8.621e-04, category `elementary_adds` — both mechanically coupled to the
    detection members (never detected ⇒ never recovered, shared censoring);
  - lab2_f1bdead/ql_true: +202.80, interval [+4.95, +492.60], BH q 0.0869,
    category `null`.

**Registered descriptives that must travel with those wordings.** Source:
`phase2_v3_structonly/CAMPAIGN_MANIFEST.md`, section "Descriptives".

- Component histogram (P1b): all 30 wrong-blame events across the campaign
  name the healthy co-blind `SetZ2Blinds` (lab2/ql_true 8, lab2/ql_false 17,
  lab3/ql_false 5); the records carry 0 such events in all four cells and
  both arms. The registered P1b expectation placed the wrong blames in the
  low-sun members; they are vacuous there (zero wrong blames on either side)
  and the exposure materialised in the mixed-sun dead-blind cells instead.
- Low-sun bit-invariance: all 80 structure-only low-sun recovery files are
  byte-identical to the record's (20/20 per member). Mechanism, from the
  frozen code: at permanent sun rank 1 the full layer's blind machinery never
  acts — both the IV-gate adjudication and the active probe require sun rank
  ≥ 2 (`QLearner.IV_DETECT_MIN_SUN_RANK`) — and dead-LAMP adjudication is
  layer-independent, so the Brick switch removes only inactive machinery and
  the deterministic seeded trajectories coincide. The adverse low-sun record
  classification of Addendum 2026-11-12 is unchanged and extends verbatim to
  the structure-only layer.
- Detection counts (structonly vs record): lab2 18/20 vs 20/20 (ql_true) and
  20/20 vs 20/20 (ql_false); lab3 0/20 vs 20/20 (ql_true) and 9/20 vs 20/20
  (ql_false); low-sun members identical to the record.
- Degradation (A1 rule): every detected structure-only low-sun record enters
  degraded mode at best-effort rank 2, shortfall 1 (11/11, 14/14, 20/20).

**Consequence for the thesis.**

- The Elementary physics layer now has a measured protective role in fault
  detection, the role the Phase-1 and Phase-4 reward-shaping ladders could
  not find: without it the detector wrongly blacklists healthy blinds
  (lab2_f1bdead, both arms), and under the warm-start policy the active
  self-test is the ONLY channel that finds the dead blind on lab3_f1bdead
  (0/20 detections without it). Wordings must name the mechanisms: the
  IV-gated abstention and the IV-driven active probe, both derived from the
  mechanism's independent-variable binding.
- The registered expectation that the pinned low-sun cells are the cleanest
  exposure was wrong, and is reported with the same prominence: at sun rank 1
  the layer's blind machinery is inactive by construction, so the
  structure-only switch is behaviourally inert there (bit-identical
  trajectories). A protective mechanism that never engages cannot earn or
  lose credit; the low-sun members are null by inertness, not by evidence of
  equivalence under exposure.
- The lab3_f1bdead/ql_true wrong-blame null must not be read as "no wrong
  blames under exposure": that arm's detector released NO verdicts at all
  (0 events, 20/20 detection censors) — a censoring null, consistent with the
  detector-censoring reading of the corrected record.
- The 2026-07 corrected and 2026-11 low-sun records keep their
  classifications; the structure-only condition is a new column of the
  record. No numerical comparison with the Phase-1/Phase-4 ladders is
  confirmatory.

**Labelled notes.** No deviation from the registered analysis, families,
decision rule, or comparator mapping. The §7 descriptives were computed after
data contact, as registered descriptives carrying no test. The first CI run on
the branch failed the stale-ledger gate pre-data and is disclosed in the
dispatch record. The committed archive subset carries the manifests, metrics,
tables and inventories; the raw recovery and Q-table CSVs are gitignored as in
every protocol-v2 campaign and exist in the local extraction and in the
workflow artifacts, which GitHub retains until 2027-02-13.

**Not tested.** The `redundancy` layer position for Phase 2; an isolated
detector ablation (the switch is whole-process, registration §10 — the
ql_false members isolate the detector pipeline, the ql_true members measure
detector and prior changes jointly); a structure-only arm on the KG-silent
monitor variants.
