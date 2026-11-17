# Phase 1: registration of the Brick-structure-only control arm (2026-11-10)

> **Stand-alone copy of a registration that was first published inside the project's state report.**
> Source: section "Addendum 2026-11-10 — REGISTRATION (pre-dispatch): Brick-structure-only control arm (`phase1_v2_brick_only`) — isolating the Elementary layer" of `docs/_audit/THESIS_STATE_REPORT.md`, lines 3243 to 3391 at commit `6dd6a506` (tag `record-v2.5.0-pre-cleanup`).
> The section heading was first committed in `140906f2` (2026-11-10T14:29:23Z). No line of the section was changed by a later commit.
> Run registered here: 42834963612, dispatched 2026-11-10T14:31:36Z from head `140906f2` (the commit that introduced this section, committed 2026-11-10T14:29:23Z).
> The text below is copied word for word; nothing was edited. Section signs (§) and the word "Addendum" refer to the state report, which is available at the tag named above.

## Addendum 2026-11-10 — REGISTRATION (pre-dispatch): Brick-structure-only control arm (`phase1_v2_brick_only`) — isolating the Elementary layer

This addendum registers one control run **before its dispatch is queued**. Commit
order is the guarantee: this text, the run profile, the reasoner gate, the signature
test, and the Brick goldens are committed together, and the dispatch is queued only
after this commit is pushed (the run's server-recorded `head_sha` must contain this
addendum — registration ⊂ dispatched tree, as for every registered run since 19e).

**Timing disclosure (required):** this registration is written *after* the corrected
protocol-v2 Phase-1 campaign (`docs/phase1_results_v2.md`): arm C lab2 `auc_goal`
Δ = +0.003700 [+0.002700, +0.004917]; redundancy-only Δ = +0.002583; arm C − redundancy
= +0.001117 [+0.000767, +0.001467]; arm C lab3 `auc_goal` = −0.002300. All of those
numbers are known. What is registered blind is the Brick arm's own treatment effect and
the increment arm C − Brick, which no prior run has measured.

### 1. Question and design

**Question.** The thesis title attributes the prior's value to *physics-based*
knowledge encoded with the Elementary ontology. The redundancy control (19e/19g) showed
that ≈70 % of the lab2 anchor needs no ontology at all. The remaining ≈30 % is still a
*bundle*: Brick structure (what is located where, what feeds what — a pre-existing
vocabulary) and Elementary mechanism (Mediates preconditions, coupling class — the
vocabulary this thesis introduces). This arm splits that bundle. It asks: **how much of
arm C's effect survives when the reasoner may read Brick/SOSA/QUDT structure but no
Elementary (`elem:`) mechanism triple?**

**Knowledge ladder.** A0 tabula rasa (`ql_false`, every mode) → A1 redundancy-only
(`phase1_v2_redundancy_only`) → **A2 Brick-structure (`phase1_v2_brick_only`, new)** →
A3 full KG (`phase1_v2_kg_only`). Each arm's treatment effect is its within-run paired
`ql_true − ql_false` seed difference; a rung increment is the by-seed difference of two
arms' treatment effects (same construction as registered member 3 of the v2 family).

**Arm.** Run profile `phase1_v2_brick_only` (`config/run_config.json`), byte-identical
to `phase1_v2_kg_only` except `learning_overrides.stereo_knowledge_layer = "brick"`,
which sets `-Dstereo.knowledgeLayer=brick`
(`StereotypeReasoner.KnowledgeLayer.BRICK`; `analysis/tests/test_phase1_v2_config.py`
asserts the byte-identity). Under that flag the `ql_true` reasoner:

- enumerates the action space from the WoT contract exactly as every other arm
  (pass 1 is untouched);
- enriches actions with `BRICK_ENRICHMENT_QUERY` instead of the Elementary
  `ACTUATOR_DISCOVERY_QUERY`: an action is claimed to affect the zone its component is
  `brick:isLocatedIn` when that zone contains a sensor that `sosa:observes` a property
  of `qudt:hasQuantityKind` Illuminance. No `elem:hasBehavioralStereotype`,
  `elem:hasPhysicalMechanism`, `elem:has*Variable`, `ws:ivMinRank`, `ws:energyCost`, or
  `ws:powerGates` triple is consulted; the IV columns are not even projected;
- reads cross-zone arcs with `BRICK_CROSS_ZONE_FEEDS_QUERY` (same `brick:feeds`
  topology, no coupling-class stereotype): every arc is an untyped PRIMARY coupling;
- has Rule 2 (IV gate) and the IV-unsat soft prior hard-disabled (`knowledgeLayer ==
  FULL` guard), in addition to having no IV to gate on.

Everything else — Rule 1 redundancy, Rule 3 cross-zone overshoot, Rule 4 shared
actuator, Rule 5 constructive bonus, prior decay, ε schedule, reward, protocol v2 — is
identical to arm C. `ql_false` is the unchanged tabula-rasa arm. The default flag value
is `full`, so every other profile has a byte-identical data-generating process
(`verifyActionRegistry`: all 17 FULL goldens unchanged, `CONTRACT HOLDS`).

**What the arm does and does not isolate (stated before data).**

- Structural claims are *identical* between A2 and A3 on lab1–3: same action keys,
  labels, state bits, `affectedZones`, `kgSilent`
  (`BrickOnlyKnowledgeLayerTest.brickArmMakesTheSameStructuralClaimsAsFullArmOnCleanLabs`;
  goldens `config/golden_registry/brick_only/registry_lab{1,2,3}.csv` differ from the
  FULL goldens only in `hasIV=false, ivIdx=-1` on the two blind-ON rows of lab2/lab3).
  Any A3 − A2 difference is therefore **not** attributable to "which actuator influences
  which zone".
- In protocol v2 (adaptive trust off, cross-zone bonus off, Phase-1 agent) the coupling
  class is consumed only by `getActionPrediction`, which Phase 1 does not use. **The
  Phase-1 contrast A3 − A2 therefore isolates exactly one Elementary construct: the
  Mediates IV gate on the blinds** (Rule 2 well −50 and the learned IV-unsat prior −5 at
  sun rank 0, versus A2's Rule 5 bonus +22.5 for opening a blind in the dark). The
  coupling class becomes live only in Phase 2 (detector) and is not tested here.
- The direction convention of a prediction (`ON ⇒ +1`) is code-level in both arms and
  does not read `elem:increases`; it does not differentiate A2 from A3.
- The Brick arm over-approximates: any actuator co-located with an illuminance sensor
  is presumed to affect illuminance. On lab1–3 this is harmless (every actuator is a
  light source or blind); on labs with radiators or monitors it would make false
  claims. That is a property of structure-only knowledge and is a discussion point,
  not a defect of the control.
- lab1 has no blinds, so A2 ≡ A3 there by construction; lab1 is the saturated null
  control for the arm.

**Operational proof the Elementary layer was off** (to be archived under
`verification/` of the run, as in 19g): the seed-1 lab2 and lab3 `ql_true` initial
Q-tables must contain **no −50 cell on any blind-ON column at sun rank 0** and must
contain the +22.5/+15/+7.5 Rule-5 bonus on blind-ON cells at sun rank 0 where the zone
is below goal; the arm-C tables from run `29848584965` show −50 in exactly those cells.
The run log must print `knowledge layer = BRICK` for every `ql_true` training cell.

**Dispatch (verbatim):** `phase1.yml` `workflow_dispatch`, `run_mode =
phase1_v2_brick_only`, `profiles = lab1,lab2,lab3`, `seeds = 1,…,20`,
`publish_results = true`. Single dispatch; no re-run without a fresh registration
disclosing this outcome. Branch frozen until publish + tag verified on origin (18b
footgun protocol). Archive into `phase1_v2_corrected/run_<id>/`; results tag
`results-<ts>-phase1_v2_brick_only-<sha>`.

### 2. Registered analysis and decision rule (frozen before dispatch)

**Analysis script:** `analysis/phase1_v2_knowledge_ladder.py` (committed with this
addendum; dry-run verified against the two existing v2 archives, reproducing registered
member 3 = +0.001117 when the redundancy archive is used as the A2 stand-in).
`analysis/reproduce_phase1_v2.py` rebuilds and byte-compares its outputs when the
`phase1_v2_brick_only` archive is present and remains four-arm otherwise.

**Primary family (m = 2, BH within family), paired by seed, n = 20:**

1. lab2 `auc_goal`: (A3 `ql_true − ql_false`) − (A2 `ql_true − ql_false`)
   — the **Elementary increment** on the lab where arm C's effect is established.
2. lab3 `auc_goal`: the same increment on the lab with cross-zone spill and shared
   spotlight.

Reported per member: mean, median, 95 % paired bootstrap CI (deterministic RNG 0xC1,
10,000 iters), two-sided sign-flip p, exact sign p, BH q (m = 2), paired rank-biserial.

**Pre-committed interpretation per member** (applied to the 95 % CI of the increment):

- **`elementary_adds`** — CI entirely above 0. Licensed wording: "on this lab the
  Elementary Mediates gate adds training success beyond Brick structure."
- **`elementary_costs`** — CI entirely below 0. Licensed wording: "on this lab the
  Mediates gate reduces training success relative to Brick structure" (the gate is
  wrong or over-cautious here) — must be reported as such, not softened.
- **`null`** — CI includes 0. Licensed wording: "Brick structure reproduces arm C's
  effect; no Elementary-specific acceleration is detectable at n = 20." The thesis
  must then NOT claim that physics/mechanism knowledge accelerates Phase-1 learning;
  the Elementary case rests on Phase 2 (detection precision) and Phase 3 (dynamics
  write-back), as 19g already foreshadowed.

No ⅓/⅔ share rule is registered for this family: A2 may legitimately carry the whole
A3 effect. `brick_share = A2 / A3` is reported descriptively.

**Expectation (stated for honesty, not a test):** the IV gate matters only at sun
rank 0 (¼ of episodes); the expected increment on lab2 is small and positive (A3 avoids
useless blind openings in the dark), on lab3 unsigned (A3's lab3 `auc_goal` is already
negative). A `null` on both is a plausible and acceptable outcome.

**Registered descriptives (no thresholds):** A2's own effects on lab1/lab2/lab3
(`auc_goal`, `mean_first_goal_presentations`, benchmark `goal_rate`, `avg_steps`,
`avg_policy_energy`, `avg_wasted`, `avg_cycling`, `avg_redundant`); the A2 − A1
increment; the A3 − A2 benchmark increments; and the full ladder table A1 → A2 → A3.
In particular whether A2 reproduces arm C's lab3 policy-energy cost (+1.074) — if it
does not, the cost is attributable to the IV gate.

**One-shot rule:** nothing above may be adjusted after data contact; deviations must be
labelled deviations in the reporting addendum.

**Out of scope here (named for the record):** a Phase-2 dispatch of the same flag on a
dead-lamp lab, where the Brick arm cannot abstain on IV-gated components and is
predicted to blacklist healthy blinds at night. That is the sharper Elementary test and
would need its own registration.
