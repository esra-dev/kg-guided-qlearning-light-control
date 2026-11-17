# Phase 1: report on the Brick-structure-only control arm (2026-11-13)

> **Stand-alone copy of a report that was first published inside the project's state report.**
> Source: section "Addendum 2026-11-13 — REPORT: Brick-structure-only control arm executed — lab2 `null` at a saturated endpoint, lab3 `elementary_adds`" of `docs/_audit/THESIS_STATE_REPORT.md`, lines 3393 to 3440 at commit `6dd6a506` (tag `record-v2.5.0-pre-cleanup`).
> The section heading was first committed in `ae497bdc` (2026-11-13T16:27:08Z). No line of the section was changed by a later commit.
> Run reported here: 42834963612.
> The text below is copied word for word; nothing was edited. Section signs (§) and the word "Addendum" refer to the state report, which is available at the tag named above.

## Addendum 2026-11-13 — REPORT: Brick-structure-only control arm executed — lab2 `null` at a saturated endpoint, lab3 `elementary_adds`

This addendum reports the control registered in Addendum 2026-11-10. The full tables, the
validity evidence, and the labelled deviations are in `docs/phase1_results_v2.md`, section
"Knowledge-ladder control arm"; that report is the canonical text and this addendum only
records the outcome and its consequence for the thesis.

**Execution.** Run `42834963612` was dispatched from `140906f2`, the registration commit,
two minutes after that commit was created, and all of its 302 jobs concluded successfully.
The archive of run `42834963612` was downloaded, digest-verified, validated, and committed
on 2026-11-13; it had not been archived or analysed before that date. The registered script
`analysis/phase1_v2_knowledge_ladder.py` ran once, unchanged, and the five-arm
`analysis/reproduce_phase1_v2.py` rebuilds every committed table byte-equivalently. Source:
`phase1_v2_corrected/run_42834963612/ARCHIVE_MANIFEST.md`.

**Outcome under the pre-committed rule.** Source:
`phase1_v2_corrected/analysis/registered/phase1_v2_knowledge_ladder_family.csv`.

- Ladder member 1 of `phase1_v2_corrected/` (lab2 `auc_goal`, A3 − A2): increment exactly
  zero in all 20 seeds; category `null`. Licensed wording: "Brick structure reproduces arm
  C's effect; no Elementary-specific acceleration is detectable at n = 20."
- Ladder member 2 of `phase1_v2_corrected/` (lab3 `auc_goal`, A3 − A2): `+0.003850`,
  interval `[+0.001533, +0.006683]`, BH q `0.008583`; category `elementary_adds`. Licensed
  wording: "on this lab the Elementary Mediates gate adds training success beyond Brick
  structure."

**Qualifications that must travel with the two wordings.**

- The lab2 endpoint is saturated: both knowledge arms of `phase1_v2_corrected/` reach
  training success `1.000000` in all 20 seeds. The `null` shows that the gate was not
  needed to reach the ceiling, not that the gate is inert.
- On lab3 both knowledge arms of `phase1_v2_corrected/` train worse than tabula rasa
  (Brick `−0.006150`, arm C `−0.002300`). The gate recovers part of a loss caused by the
  structural priors.
- The delivery signature was restated after data contact, and the registered log line was
  replaced by an equivalent one; both are disclosed as deviations in the canonical report
  and in `phase1_v2_corrected/run_42834963612/ARCHIVE_MANIFEST.md`.

**Consequence for the thesis.** The registration's instruction for a `null` applies to
lab2, the only lab where arm C improves on tabula rasa: the thesis must not claim that
physics or mechanism knowledge accelerates Phase-1 learning there. The clean-learning
advantage decomposes into a redundancy rule and Brick structure. The lab3 member licenses
one narrower sentence about the gate, with the qualification above. The case for the
Elementary layer continues to rest on Phase 2, Phase 3, and Phase 4, where gates, delays,
dependencies, and costs are consumed.

**Not tested.** The Phase-2 dispatch of the same flag, named out of scope in the
registration, was not run.
