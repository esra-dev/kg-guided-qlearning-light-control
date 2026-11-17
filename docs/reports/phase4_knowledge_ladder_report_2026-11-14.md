# Phase 4: report on the knowledge-ladder control arms (2026-11-14)

> **Stand-alone copy of a report that was first published inside the project's state report.**
> Source: section "Addendum 2026-11-14 — REPORT: Phase-4 knowledge-ladder control arms executed — Brick structure reproduces most of the reduction, the redundancy rule a partial share, the gate annotations add a small increment on two of three labs" of `docs/_audit/THESIS_STATE_REPORT.md`, lines 3653 to 3758 at commit `6dd6a506` (tag `record-v2.5.0-pre-cleanup`).
> The section heading was first committed in `ea86e39b` (2026-11-13T23:20:47Z). No line of the section was changed by a later commit.
> Runs reported here: 43494914019, 43494933613, 43494955158, 43494975441.
> The text below is copied word for word; nothing was edited. Section signs (§) and the word "Addendum" refer to the state report, which is available at the tag named above.

## Addendum 2026-11-14 — REPORT: Phase-4 knowledge-ladder control arms executed — Brick structure reproduces most of the reduction, the redundancy rule a partial share, the gate annotations add a small increment on two of three labs

This addendum reports the control arms registered in Addendum 2026-11-13b. The full tables,
the validity evidence, and the labelled notes are in `phase4_v2_ladder/CAMPAIGN_MANIFEST.md`;
that manifest is the canonical text and this addendum records the outcome and its
consequence for the thesis.

**Execution.** Four dispatches of `phase4.yml` from `ee656178`, the registration commit:
runs `43494914019` and `43494933613` (`phase4_v2_brick_only`, four labs, seeds 1–10 and
11–20) and runs `43494955158` and `43494975441` (`phase4_v2_redundancy_only`, three labs,
the same seed halves). All 708 jobs concluded successfully on the first attempt. The four
archives of `phase4_v2_ladder/` were downloaded, digest-verified, validated, and committed,
and the four results tags are present on the remote. The registered script
`analysis/phase4_v2_knowledge_ladder.py` ran once, unchanged, and
`analysis/reproduce_phase4_v2_ladder.py` rebuilds the committed tables of
`phase4_v2_ladder/`. The full arm A3 is the archived campaign of 2026-07-22 and was not
run again.

**Delivery and comparability** (`phase4_v2_ladder/CAMPAIGN_MANIFEST.md`, section "Validity
evidence"). All 280 training gate manifests of `phase4_v2_ladder/` record the dispatched
run mode, and all 280 training jobs log the intended layer and no other. In both control
arms of `phase4_v2_ladder/` the ON column of every power-gated action is invariant to the
state of its upstream switch; in the archived full-KG tables half of the state pairs
differ. The tabula-rasa training cells of `phase4_v2_ladder/` are byte-identical to the
archived ones in every lab and seed, and the tabula-rasa value of each registered estimand
of `phase4_v2_ladder/` is identical in 20 of 20 seeds.

**Outcome under the pre-committed rule.** Source:
`phase4_v2_ladder/analysis/registered/phase4_v2_knowledge_ladder_family.csv`. The estimand
on the dependency labs is `avg_redundant`, lower is better; an increment is the by-seed
difference of two arm effects, n = 20.

- Family R of `phase4_v2_ladder/`, beyond the redundancy rule (A3 − A1):
  - lab4 of `phase4_v2_ladder/`: −0.305000, interval [−0.449375, −0.186875], BH q 5.722e-06, category `adds`;
  - lab4dual of `phase4_v2_ladder/`: −0.452500, interval [−0.552500, −0.352500], BH q 5.722e-06, category `adds`;
  - lab4chain of `phase4_v2_ladder/`: −0.295625, interval [−0.410625, −0.182500], BH q 1.030e-04, category `adds`.
  - Licensed wording: "the full knowledge graph removes more redundant actions than the
    ontology-free redundancy rule alone." Share of the full arm's mean effect that the
    redundancy rule reproduces in `phase4_v2_ladder/`: 0.5612, 0.3926, 0.5974 (lab4, lab4dual,
    lab4chain); each is "partial" under the frozen one-third / two-thirds rule.
- Family S of `phase4_v2_ladder/`, beyond Brick structure (A3 − A2):
  - lab4 of `phase4_v2_ladder/`: −0.040625, interval [−0.065625, −0.012500], BH q 0.0256, category `adds`;
  - lab4dual of `phase4_v2_ladder/`: −0.053750, interval [−0.090000, −0.017500], BH q 0.0256, category `adds`;
  - lab4chain of `phase4_v2_ladder/`: −0.015625, interval [−0.034375, +0.003125], BH q 0.2500, category `null`.
  - Licensed wording for lab4 and lab4dual: "on this lab the gate annotations remove
    redundant actions that Brick structure alone does not." Licensed wording for
    lab4chain: "Brick structure reproduces the full arm's reduction on this lab."
- Secondary member of `phase4_v2_ladder/` (lab5 `energy_compliance`, A3 − A2):
  +0.075000, interval [+0.043750, +0.106250], sign-flip p 6.714e-04, category
  `adds`. Licensed wording: "the compliance gain requires the cost annotation; the
  structure-only agent does not show it."

**Registered descriptives that must travel with those wordings.** Source:
`phase4_v2_ladder/analysis/registered/phase4_v2_knowledge_ladder_descriptives.csv`.

- Arm effects on `avg_redundant` in `phase4_v2_ladder/` (lab4, lab4dual, lab4chain): A1
  −0.390000, −0.292500, −0.438750; A2 −0.654375, −0.691250, −0.718750; A3
  −0.695000, −0.745000, −0.734375. The Brick arm of `phase4_v2_ladder/` obtains
  94.2 %, 92.8 %, 97.9 % of the full arm's mean effect. That ratio is derived from the
  registered descriptives and carries no test.
- Components in `phase4_v2_ladder/`: the mean effect on wasted steps is the same in A2 and
  A3 on every lab. The cycling effect of A1 in `phase4_v2_ladder/` is positive on lab4,
  +0.145000 [+0.016250, +0.292500], and on lab4dual,
  +0.186875 [+0.060625, +0.317500]: the rule-only agent cycles actuators more
  than tabula rasa there. The mean increment of family S lies entirely in the cycling
  component.
- lab5 of `phase4_v2_ladder/`: the A2 effect on `energy_compliance` is
  −0.031875 [−0.069375, +0.003750]. The same arm of `phase4_v2_ladder/` cycles actuators more
  (+0.695625) and spends more policy energy (+1.796250) than tabula rasa.
- Training success is at or above 0.99 in every arm and lab of `phase4_v2_ladder/`; the
  arms differ in the quality of the learned policy.

**Consequence for the thesis.**

- The reduction in redundant actions on the dependency labs must not be attributed to
  dependency knowledge as a whole. Most of it is reproduced by an agent that knows only
  Brick structure, and a partial share by the ontology-free redundancy rule.
- The gate annotations may be credited with a small further reduction on lab4 and
  lab4dual, in the licensed wording. They may not be credited on lab4chain. The wording
  must say gate annotations: the power gate and the blind sunshine gate were removed
  together.
- The lab5 compliance gain may be attributed to the cost annotation.
- `ws:powerGates`, `ws:energyCost`, and `ws:ivMinRank` are project annotations and not terms
  of the published Elementary ontology. The outcome is evidence for those annotations, not
  for an Elementary mechanism triple.
- The question of Addendum 2026-11-13b is answered: the simple rule explains a partial
  share, structure most, and the gate annotations a small supported remainder on two labs.

**Labelled notes.** No deviation from the registered analysis, the families, or the
decision rule. The three helper scripts (proof of delivery, comparability, reproducer) were
written after the registration commit and before the runs finished; two changes to them
after data contact are described in the manifest and change no registered number. The
`results` branch holds only the seed-1 legacy files of each dispatch; the complete data
exist in the workflow artifacts, which GitHub retains until 2027-02-11, and in extracted
local copies.

**Evidence file for the reward-clip correction of Addendum 2026-11-13b.** The start-up
lines behind that correction are tracked in
`docs/audit/phase2_adaptation_settings_evidence.csv`: eight adaptation logs of the low-sun
re-run (both arms, both fault labs) report the code-default clip, and two clean-training
logs report the configured clip. Each row carries the artifact identifier and the SHA-256
of the log file.

**Not tested.** A separation of the power gate from the blind sunshine gate; the
redundancy arm on lab5; a structure-only fault detector (out of scope in the
registration).
