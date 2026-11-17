# Phase 4: registration of the knowledge-ladder control arms (2026-11-13)

> **Stand-alone copy of a registration that was first published inside the project's state report.**
> Source: section "Addendum 2026-11-13b — REGISTRATION (pre-dispatch): Phase-4 knowledge-ladder control arms (`phase4_v2_redundancy_only`, `phase4_v2_brick_only`) — redundancy rule, Brick structure, or gate and cost annotations?" of `docs/_audit/THESIS_STATE_REPORT.md`, lines 3442 to 3651 at commit `6dd6a506` (tag `record-v2.5.0-pre-cleanup`).
> The section heading was first committed in `ee656178` (2026-11-13T19:39:46Z). No line of the section was changed by a later commit.
> Runs registered here: 43494914019, 43494933613, 43494955158, 43494975441, dispatched 2026-11-13T19:43:45Z to 19:44:09Z from head `ee656178` (the commit that introduced this section, committed 2026-11-13T19:39:46Z).
> The text below is copied word for word; nothing was edited. Section signs (§) and the word "Addendum" refer to the state report, which is available at the tag named above.

## Addendum 2026-11-13b — REGISTRATION (pre-dispatch): Phase-4 knowledge-ladder control arms (`phase4_v2_redundancy_only`, `phase4_v2_brick_only`) — redundancy rule, Brick structure, or gate and cost annotations?

This addendum registers two control arms **before their dispatches are queued**. Commit
order is the guarantee: this text, the two run profiles, the frozen analysis script, its
tests, and the Brick reference registries are committed together, and the dispatches are
queued only after this commit is pushed (each run's server-recorded `head_sha` must
contain this addendum).

**Timing disclosure (required):** this registration is written *after* the corrected
Phase-4 campaign (`phase4_v2_corrected`, runs `29926341581` and `29926354783`): `ql_true −
ql_false` on `avg_redundant` is −0.695 (lab4), −0.745 (lab4dual), −0.734 (lab4chain), each
q = 5.09e-6; lab5 `energy_compliance` +0.043125, q = .0094. It is also written after the
Phase-1 ladder: the redundancy rule reproduces 69.8 % of the lab2 anchor, and the
Elementary increment over Brick structure is exactly 0 on lab2 and +0.003850 on lab3. All
of those numbers are known and they motivate the question. What is registered blind is
the effect of the redundancy arm and of the Brick arm on the Phase-4 labs, and both rung
increments, which no prior run has measured.

### 1. Question and design

**Question.** Phase 4 attributes the reduction of redundant actions on the dependency
labs to "use of gate and cost annotations" without an arm that removes them. Phase 1
showed that an ontology-free redundancy rule can carry most of a knowledge effect. This
registration asks, for lab4, lab4dual, and lab4chain: **how much of the full arm's
reduction in `avg_redundant` survives when the reasoner keeps only the redundancy rule
(A1), and when it keeps Brick structure but no gate annotation (A2)?**

**Knowledge ladder.** A0 tabula rasa (`ql_false`, every run) → A1 redundancy rule
(`phase4_v2_redundancy_only`, new) → A2 Brick structure (`phase4_v2_brick_only`, new) →
A3 full KG (`phase4_v2`, archived). Each arm's treatment effect is its within-run paired
`ql_true − ql_false` seed difference; a rung increment is the by-seed difference of two
arms' treatment effects.

**Arms.** Both new profiles are byte-identical to `phase4_v2` except one additional
learning override (`analysis/tests/test_phase4_v2_ladder_config.py`):
`stereo_redundancy_only = true` for A1 and `stereo_knowledge_layer = "brick"` for A2.
No agent or reasoner code changes; the only Java change is the list of reference
registries in the dump tool (`ActionRegistryDump.BRICK_ONTOLOGY_SETS`). All 24 FULL
reference registries are unchanged (`verifyActionRegistry`: 32 sets, `CONTRACT HOLDS`).

**What each layer delivers on these labs** (pinned by
`src/test/java/tools/Phase4KnowledgeLadderLayerTest.java` and by the registries under
`config/golden_registry/brick_only/`):

| Item | A1 redundancy | A2 Brick | A3 full |
|---|---|---|---|
| Action keys, labels, state bits, affected zones, KG-silent flags | same | same | same |
| Rule 1 redundancy well and redundant-action soft prior | on | on | on |
| Power gate (`ws:powerGates`: lamp on plug; in lab4chain also plug on master switch) | off | off | on |
| Blind sunshine gate (`elem:hasIndependentVariable`, `ws:ivMinRank`) and IV-unsatisfied soft prior | off | off | on |
| Rules 3–5 (cross-zone overshoot, shared actuator at goal, constructive bonus) | off | on (bonus not conditioned on any gate) | on (bonus only when the gate is satisfied) |
| Cost annotation (`ws:energyCost`, lab5 only) | read | not read | read |

Initial Q-value of "gated lamp ON while its plug is OFF", zone below goal by three ranks:
−50 under A3 (Rule 2), +22.5 under A2 (Rule 5), 0 under A1. With the plug ON, A2 and A3
agree (+22.5). The Rule-1 well (−50) is identical in all three.

**What the contrasts isolate, and what they do not (stated before data).**

- The structural claims are identical in all three layers on these four labs, including
  the claim that a plug or breaker affects the zone it is located in (the full layer makes
  the same claim through the plug stereotype's dependent variable). A rung increment is
  therefore not attributable to "which actuator influences which zone".
- **A3 − A1** isolates everything the knowledge graph adds beyond the registry-derived
  redundancy rule: both gates and Rules 3–5.
- **A3 − A2** isolates the gate annotations: the power gate *and* the blind sunshine gate,
  with the IV-unsatisfied soft prior. The two gate types are bundled; this design does not
  separate them. As in the 2026-11-10 registration, the coupling class is not consumed by
  the Phase-1 agent pipeline that Phase 4 reuses.
- `ws:powerGates`, `ws:energyCost`, and `ws:ivMinRank` are project annotations. They reuse
  the Elementary independent-variable machinery but are not terms of the published
  Elementary ontology. An `adds` outcome in family S is evidence for those annotations,
  not for an Elementary mechanism triple.
- A1 is not run on lab5: the redundancy layer still reads `ws:energyCost` through the full
  enrichment query, so the non-fading energy prior would remain active and the arm would
  not be a redundancy-only control there.
- On lab5 the A2 reasoner reads no cost, so the energy prior weight is inert by
  construction. The lab5 member below measures whether the compliance gain is then absent.

**Operational proof of delivery** (to be archived under `verification/` of each run):

1. `TRAINING_OK.json` of every training cell records the dispatched `run_mode`.
2. The training log of every training cell, both stereo arms, prints
   `knowledgeLayer=BRICK` (A2) or `knowledgeLayer=REDUNDANCY` (A1) and no other value.
3. Plug-invariance: in the seed-1 `ql_true` initial Q-tables of lab4, lab4dual, and
   lab4chain, the ON column of every power-gated action has the same value in any two
   states that differ only in the state bit of its upstream switch. This holds under A1
   and A2 and cannot hold under A3. The A3 side is evidenced by the unit test above, and
   by the archived A3 initial tables if the July archives contain them.

**Comparability gate (reported, not a test).** The `ql_false` cells of each control run
are compared seed by seed with the archived A3 campaign's `ql_false` cells: on the
registered estimand (table written by the analysis script) and byte-wise on the training
metric files and final Q-tables. Non-identity does not change the registered increments,
which are differences of within-run paired effects; it is reported as a limitation.

**Dispatch (verbatim).** `phase4.yml` `workflow_dispatch`, `publish_results = true`, in
seed halves that mirror the archived A3 campaign:

- `run_mode = phase4_v2_brick_only`, `profiles = lab4,lab4dual,lab4chain,lab5`,
  `seeds = 1,…,10` and `seeds = 11,…,20`;
- `run_mode = phase4_v2_redundancy_only`, `profiles = lab4,lab4dual,lab4chain`,
  `seeds = 1,…,10` and `seeds = 11,…,20`.

One dispatch per half. Re-running failed jobs of the same run after an infrastructure
failure (dependency or artifact download, results-branch push collision) is permitted and
is disclosed; no cell is re-run after its data have been inspected. Branch frozen until
publish and results tags are verified on origin. Archive into `phase4_v2_ladder/run_<id>/`
(a separate root, because `reproduce_phase4_v2.py` reads every run folder under
`phase4_v2_corrected/`).

### 2. Registered analysis and decision rule (frozen before dispatch)

**Analysis script:** `analysis/phase4_v2_knowledge_ladder.py` (committed with this
addendum; unit-tested in `analysis/tests/test_phase4_v2_knowledge_ladder.py`). Dry run:
with the archived A3 campaign supplied as a stand-in for all three arms it reproduces the
registered A3 effects (−0.695, −0.745, −0.734375, +0.043125) with identical sign-flip
p-values and returns every increment as exactly 0. Bootstrap intervals use the fixed
RNG base 20261114, so A3's own intervals differ from the July table in the third decimal.

**Estimands** are the frozen Phase-4 ones: `avg_redundant` = mean over a seed's benchmark
rows of (WastedSteps + ActuatorCyclingCount), lower is better; `energy_compliance` on
lab5, higher is better. Paired by seed, n = 20.

**Family R, m = 3 (BH within the family) — beyond the redundancy rule.**
lab4, lab4dual, lab4chain: (A3 effect) − (A1 effect) on `avg_redundant`.

**Family S, m = 3 (BH within the family) — beyond Brick structure.**
lab4, lab4dual, lab4chain: (A3 effect) − (A2 effect) on `avg_redundant`.

**Secondary, single member, outside both families.**
lab5: (A3 effect) − (A2 effect) on `energy_compliance`.

Reported per member: mean, median, 95 % paired bootstrap CI (10,000 draws), two-sided
exact sign-flip p, exact sign p, BH q, matched-pairs rank-biserial, and the counts of
positive, tied, and negative seeds.

**Pre-committed category per member** (applied to the 95 % CI of the increment, in the
metric's own direction):

- **`adds`** — CI entirely on the favourable side of zero (below 0 for `avg_redundant`,
  above 0 for `energy_compliance`).
  - Family R: "on this lab the full knowledge graph removes more redundant actions than
    the ontology-free redundancy rule alone."
  - Family S: "on this lab the gate annotations remove redundant actions that Brick
    structure alone does not." The wording must say gate annotations, not power gate.
  - lab5: "the compliance gain requires the cost annotation; the structure-only agent
    does not show it."
- **`costs`** — CI entirely on the unfavourable side. Must be reported as such, not
  softened.
- **`null`** — CI includes 0.
  - Family R: "the redundancy rule reproduces the full arm's reduction on this lab; no
    additional benefit of graph knowledge is detectable at n = 20." The thesis must then
    NOT attribute that lab's reduction in redundant actions to dependency knowledge.
  - Family S: "Brick structure reproduces the full arm's reduction on this lab." The
    thesis must then NOT attribute it to the gate annotations.
  - lab5: "no difference between the full and the structure-only arm is detectable at
    n = 20."

**Share rule (family R only).** The frozen one-third / two-thirds rule of the Phase-1
redundancy control is applied to share = (A1 mean effect) / (A3 mean effect): below ⅓
"little", ⅓ to ⅔ "partial", above ⅔ "most" of the full arm's reduction is reproduced by
the redundancy rule. The share is reported next to the category and does not override it.

**Expectation (stated for honesty, not a test).** `avg_redundant` counts wasted steps and
actuator cycling. Rule 1 removes repeated commands; the power gate removes commands to a
lamp whose plug is off. A1 is expected to reproduce a substantial part of the reduction
and A3 − A2 is expected to be negative, with unknown size. A `null` in either family is a
plausible and acceptable outcome. On lab5 the A2 effect is expected to be near zero.

**Registered descriptives (no thresholds):** each arm's own effect per lab on
`avg_redundant`, its two components, goal rate, steps, policy energy, and deviation; the
A2 − A1 increment; on lab5 the A2 and A3 effects on `energy_compliance` and on the same
benchmark columns; training success rates from each run's `learning_speed_table.csv`.

**One-shot rule:** nothing above may be adjusted after data contact; deviations must be
labelled deviations in the reporting addendum.

### 3. Out of scope (named for the record)

- A Phase-2 run with the structure-only layer in the fault detector (the test named in
  Addendum 2026-11-10). It needs the layer to be passed to the adaptation step, which the
  current runner does not do, and its own registration. It is not run.
- A separation of the power gate from the blind sunshine gate.
- A fresh-seed replication of the exploratory early-learning result.

### 4. Corrections to earlier text (not part of the registration)

- **Scope of the Elementary layer in Phases 2–4.** The closing sentence of Addendum
  2026-11-13 says the case for the Elementary layer rests on Phase 2, Phase 3, and Phase 4,
  "where gates, delays, dependencies, and costs are consumed". That is too broad.
  - Phase 3 consumes no knowledge layer. Delays are measured by probing and averaged
    (`DynamicsLearner`); `ws:hasResponseDynamic` has no consumer; the dynamics runner passes
    no layer; both compared arms use the same reasoner. A structure-only arm is identical
    by construction.
  - Phase 4's gates and costs are the project annotations `ws:powerGates` and
    `ws:energyCost`, read by the full layer only (the cost also by the redundancy layer).
  - Phase 2's fault detector consumes the Elementary independent-variable claim (blind
    gating and the active blind probe) and the coupling class, in both arms. No
    structure-only comparison exists for Phase 2.
- **Reward clip in the Phase-2 adaptation step.** §3.5 states "effective 200.0 in all CI
  runs". That holds for every training and benchmark cell. It does not hold for the
  Phase-2 adaptation step: `run_phase2_adapt.ps1` passes only the profile, the mode, the
  episode budget, and the seed to `taskAdapt`, so that JVM runs with the code default
  `reward.clip = 50.0` and an automatic prior-decay horizon. Observed in the archived
  adaptation log of run `43099277363` (`Reward: clip=50.0 … priorDecayEps=2500 (auto)`)
  against `Reward: clip=200.0` in the clean-training logs of the same campaign; the July
  runner (`b773a183`) passes the same arguments. Both arms are treated identically, so no
  registered contrast and no number changes. Descriptions of Phase 2 must state the two
  clip values.
