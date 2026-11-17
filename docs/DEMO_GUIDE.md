# Demo guide

How to show the project in a live demonstration with the dashboard in
[`dashboard/`](../dashboard/README.md). It runs on files that are already in the repository: no
simulator, no Gradle and no training run is needed.

## Start the dashboard

```powershell
cd dashboard
npm install      # first time only
npm run dev      # open the printed address, usually http://localhost:5173
```

`npm run build` checks that the production build works. The result files the pages read are
generated from the stored result tables with `py -3 dashboard/scripts/prepare_data.py`; the
dashboard README explains which files these are.

## Read the two labels first

Every panel on every page carries one of two labels, and the bottom of each page explains them.

- **From the stored results**: generated from the result tables under `results/`. Only these
  panels contain results.
- **Illustration**: a toy room, a replay of one run of an early prototype, the fault
  walk-through, a diagram, or the deadline planner (which uses its own round numbers). They show
  how something works, not what was found.

Say this before you start, so nobody mistakes a toy room for a result.

## A ten-minute route

1. **Overview** (2 minutes). The question: does knowledge about normal physics help a learner,
   and what happens when a device breaks, reacts slowly or hides a dependency? Read the four
   cards aloud; their numbers come from the stored results. Point out that all results are from
   simulated labs.
2. **Phase 1, lab2** (2 minutes). In the toy room, switch a blind on at different sun levels: a
   blind gives light only when the sun is up. Then the registered family: the knowledge arm
   shows a small gain in lab2, and the attribution panel shows how much of it a simple rule
   reproduces.
3. **Phase 2** (2 minutes). Click through the fault walk-through (an illustration with its own
   simplified detection rule). Then the eight registered cells of the campaign
   `phase2_v2_corrected` (its recovery family,
   `results/phase2_v2_corrected/analysis/registered/phase2_v2_registered_family.csv`): two
   favour the knowledge arm, three show no clear difference, three go against it; the
   false-alarm panel explains why. Two of these cells, the low-sun ones, did not receive
   the low sun in that campaign and were run again in `phase2_v3_lowsun`; the dashboard
   does not show the re-run or the structure-only campaign `phase2_v3_structonly` (see
   [`results/README.md`](../results/README.md)).
4. **Phase 3** (1 minute). Toggle a blind in the toy room and watch the countdown. Then the
   learned delays against the true delay and the deadline counts. No significance tests are
   reported for this phase.
5. **Phase 4** (3 minutes). The smart-plug trap in the toy room (lamp on, plug off). Then the
   registered family of four and the lab5 energy levels, with the note on why they differ from
   the archived table.

## What not to say

- The toy rooms and replays are not evidence. Only panels labelled "From the stored results"
  are.
- All results are from simulated labs. They do not show that the approach works in a real
  building.
- The replays are single runs of an early prototype; they show what one agent did once.

## Where the numbers are documented

| Topic | Document |
|---|---|
| Result data and their archives | [`results/README.md`](../results/README.md) |
| Registrations, notices and reports | [`docs/README.md`](README.md) |
| The simulated labs and their physics | [`LABS.md`](LABS.md) |
| Corrected lab5 energy levels | [`results/_corrected_tables/phase4_v2_corrected/README.md`](../results/_corrected_tables/phase4_v2_corrected/README.md) |
