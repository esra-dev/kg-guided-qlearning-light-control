# Phase demo dashboard

A small web page (React and Vite) that walks through the four experiment phases of the
thesis: what each simulated lab is, how its lighting behaves, and what the stored results
say about a Q-learning agent that is primed with a knowledge graph (the knowledge arm)
compared with the same agent without it (the plain arm).

It needs no simulator, no Gradle and no training run. Everything it shows is read from files
that are already in the repository. It is a local demonstration tool: the development
server listens only on your own computer, and the page is not published anywhere. It
needs Node.js (20 or newer, see the [README](../README.md#requirements-and-installation)).

## Start it

```powershell
cd dashboard
npm install        # first time only (npm ci gives the exact locked versions)
npm run dev        # open the printed address, usually http://localhost:5173
npm run build      # optional: checks that the production build works (writes dashboard/dist)
```

`npm run dev` keeps running until you stop it with Ctrl+C. `npm install` downloads the
packages into `dashboard/node_modules/` (ignored by git) and can end with an audit summary
such as `8 vulnerabilities (1 low, 2 moderate, 5 high)`. These notices concern the locked
package versions in `package-lock.json`; they have not been acted on, because the
dashboard is only run locally as a demonstration. `npm audit fix` would change the locked
versions with which CI builds the page, so it is not part of the instructions.

## Two kinds of panels

Every panel carries a small label.

- **From the stored results.** The numbers were generated from the result tables under
  `results/` by `dashboard/scripts/prepare_data.py`. No result number is typed into the
  page source.
- **Illustration.** A toy room, a replay of one run of an early prototype, the fault
  walk-through, a diagram, or the deadline planner (which uses its own round numbers). These
  help you see how something works. They show no result.

The same two sentences are shown at the bottom of every page.

## Pages

| Page | From the stored results | Illustration |
|---|---|---|
| Overview | One card per phase, with its numbers read from `public/data/overview.json` | The three agents, the lab ladder, how a decision flows |
| Phase 1 | Per-lab tiles, the registered family of five, decomposition and controls | Toy room for lab1, lab2 and lab3 |
| Phase 2 | The eight registered fault cells of `phase2_v2_corrected`, the detection table, the false-alarm count | Fault walk-through (its own simplified detection rule) |
| Phase 3 | Learned delays against the true delay, deadline compliance | Toy room with slow blinds, deadline planner |
| Phase 4 | The registered family of four, the depth contrasts, the lab5 energy levels | Smart-plug trap, toy rooms for lab4 and lab5, replays of one early run |

## Where the data comes from

| File in `public/data/` | Source | Rebuilt by the data script? |
|---|---|---|
| `phase1.json` | `results/phase1_v2_corrected/` | yes |
| `phase2.json` | `results/phase2_v2_corrected/` | yes |
| `phase3.json` | `results/phase3_v2_corrected/` | yes |
| `phase4.json` | `results/phase4_v2_corrected/` for the paired statistics, `results/_corrected_tables/phase4_v2_corrected/lab5_compliance_levels.csv` for the lab5 levels | yes |
| `overview.json` | derived from the four files above | yes |
| `replay_lab1.json`, `replay_lab2.json`, `replay_lab3.json`, `replay_lab4.json`, `replay_lab5.json`, `training.json`, `img/` | single runs of an early prototype (recorded 2026-06-10) | **no, frozen** |

Regenerate the five result files (CI does the same and fails on any difference):

```powershell
py -3 dashboard/scripts/prepare_data.py              # all five, in order
py -3 dashboard/scripts/prepare_data.py --only phase4
```

The overview file is built from the four phase files, so build those first (the default does).

### The frozen files

The replay and training files come from benchmark logs that are no longer in the repository.
They are kept as illustration data and are labelled as such. The old code that built them
(`build_replays`, `build_training`) has been removed from `prepare_data.py` on purpose, so no
command can overwrite these files with empty entries. If they are ever lost, they can only be
restored from version control. Only the Phase 4 replay panels use `replay_lab4.json` and
`replay_lab5.json`; `replay_lab1.json`, `replay_lab2.json`, `replay_lab3.json`,
`training.json` and `img/` are not used by any page at the moment.

### Why the lab5 energy levels differ from the archived table

In lab5 about half of the test runs need no step, so the step log has no row for them. The
archived registered table scored those runs as "all devices off", which makes the levels too
high. The corrected table scores them from the start state the scenario file declares. The
dashboard shows the corrected levels; the paired difference between the two arms is the same
under both scorings. See `results/_corrected_tables/phase4_v2_corrected/README.md`.

## How the toy rooms relate to the simulator

`src/lib/physics.js` re-implements the light formulas of each lab. The test
`analysis/tests/test_dashboard_contracts.py` runs the simulator function of every lab
(`simulator/simulator_flow_lab1.json`, `simulator_flow_lab2.json`,
`simulator_flow_lab3.json`, `simulator_flow_lab4.json` and `simulator_flow_lab5.json`, and
the fault labs the walk-through offers) and the dashboard code over every device combination and every sun level and requires
identical lux values (and identical power for lab4 and lab5). What the toy rooms simplify, and
say so on the page: you start with all devices off, devices switch at once (except the slow
blinds in Phase 3), a dead or inverted device draws no power here although it does in the
simulator, and the fault walk-through and the deadline planner use their own rules and numbers.

## Tests

```powershell
py -3 -m pytest -q -p no:cacheprovider analysis/tests/test_dashboard_contracts.py analysis/tests/test_dashboard_phase1_builder.py
```

They check, among other things: no withdrawn number or unsupported claim in `src/`, every
`docs/...` path named in `src/` exists, the committed JSON files equal a fresh regeneration,
and the toy-room formulas equal the simulator.

## Project structure

```
dashboard/
├── scripts/prepare_data.py     # result tables -> public/data/phase1..4.json and overview.json
├── public/data/                # generated result files and the frozen illustration files
└── src/
    ├── App.jsx                 # sidebar, hash routing, the "About the two labels" box
    ├── lib/physics.js          # toy-room light formulas for every lab
    ├── lib/useData.js          # fetch-with-cache hook
    ├── components/
    │   ├── SourceTag.jsx       # the two panel labels
    │   ├── LabView.jsx         # zones, rank meters, devices, sun strip, power meter
    │   ├── Sandbox.jsx         # clickable toy room (with the slow-blind countdown)
    │   ├── Replay.jsx          # replay of one early-prototype run (Phase 4)
    │   ├── FaultLab.jsx        # Phase 2 fault walk-through
    │   ├── charts.jsx          # bar chart, tiles, legend
    │   └── icons.jsx
    └── pages/                  # Overview, Phase1 .. Phase4
```
