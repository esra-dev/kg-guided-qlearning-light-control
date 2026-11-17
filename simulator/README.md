# Simulator

The simulated rooms are [Node-RED](https://nodered.org/) flows. This folder holds one flow
file per room and the script that derives the broken-device rooms from the healthy ones. The
physics of each room (its devices, the light that each device adds, the targets) is described
in [`../docs/LABS.md`](../docs/LABS.md), which is generated from these files; this page does
not repeat those numbers. The design of the whole system is in
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).

## 1. What a flow file is

A flow file (`simulator_flow_<name>.json`) is the export of one Node-RED flow. It is a
complete simulator of one room:

- It keeps the state of the room in Node-RED flow variables: the light level of each zone,
  the position of each switch, the sun level, and energy counters.
- An `inject` node fires every 0.05 s (the *tick*). Its function node recomputes the zone
  levels from the switches and the sun. The physics is deterministic; random numbers are used
  only when a new episode starts.
- Five `http in` nodes make the HTTP interface (section 4).

`<name>` is the name of the room. The same name is what the flow reports on `/health`
(section 4) and what the runners compare against the file they started.

## 2. The flow files

There are 37 files: 18 healthy rooms and 19 rooms with broken devices.

| Group | Flow files | Ports |
|---|---|---|
| Phase 1, healthy rooms | `lab1`, `lab2`, `lab3` | 1892, 1893, 1894 |
| Phase 1b, mechanism rooms | `labrel0`, `labrel4`, `labrel8`, `labrel16`, `labrel8s`, `labband`, `lab4chain3` | 1904 to 1908, 1912, 1913 |
| Phase 2, monitor rooms (parents of the monitor fault rooms; their `_infoonly` and `_nostereo` variants use the same flows) | `labmon`, `labmon2` | 1899, 1900 |
| Phase 2, rooms with broken devices (19 files, generated, section 5) | `lab1_f1dead`; `lab2_f1dead`, `lab2_f1inv`, `lab2_f2dead`, `lab2_f2inv`, `lab2_f1bdead`, `lab2_f1binv`; `lab3_f1dead`, `lab3_f1inv`, `lab3_f2dead`, `lab3_f2inv`, `lab3_f1dead_z2`, `lab3_f1inv_z2`, `lab3_f1bdead`, `lab3_f1binv`, `lab3_f2bdead`, `lab3_f2dead_lowsun`; `labmon_f1dead`; `labmon2_f2dead_lowsun` | the port of the healthy parent |
| Phase 3, slow devices | `lab2_slow`, `lab3_slow` | 1895, 1896 |
| Phase 4, dependencies and energy | `lab4`, `lab4dual`, `lab4chain`, `lab5` | 1897, 1901, 1902, 1898 |

The ports are those of `simulator_port_map` in [`../config/run_config.json`](../config/README.md)
(for the fault and slow rooms, of the `phase2` and `phase3` blocks). The name of a fault room
reads `<parent>_<fault>`: `f1` or `f2` (one or two devices broken), `dead` (the device adds no
light) or `inv` (it takes light away), a `b` for blinds, `_z2` for the second zone, `lowsun`
for a sun fixed at 100 lux. All flow files answer on the
five endpoints below.

## 3. How the runners start and stop a flow

The runners (`run_full_project.ps1`, `run_phase2_adapt.ps1`, `run_phase3_dynamics.ps1`) start
and stop every simulator themselves; nothing needs to be started by hand. For each room they:

1. Refuse to go on if something already listens on the room's port. A runner never reuses a
   simulator it did not start, because it could be another room in an unknown state
   (`Assert-SimulatorPortFree` in [`../scripts/SimulatorControl.ps1`](../scripts/SimulatorControl.ps1)).
2. Start `node-red --userDir <repository>/.node-red-<profile> --port <port> <flow file>` as a
   hidden background process.
3. Wait up to 90 seconds for the port to answer.
4. Read `/health` and stop with an error unless its `profile` field equals the name of the
   flow file they started (`Assert-SimulatorIdentity`). A simulator of another room on the
   same port can therefore never be trained or tested against by mistake.
5. At the end, stop the process and all processes it started, and every process whose command
   line carries the run's own `--userDir` (`Stop-SimulatorTree`). Node-RED starts a child
   process on Windows that would otherwise keep the port.

The three runners differ in when they start a room. `run_full_project.ps1` starts one
simulator per lab of the run, all at once, before any agent starts; labs that share a flow
(the monitor variants) share one process. Before it starts them it writes the `tick` of the
run mode into the `repeat` field of every flow file it uses (0.05 s in every run mode of
`config/run_config.json`, so the committed files do not change). `run_phase2_adapt.ps1` and
`run_phase3_dynamics.ps1` start one flow at a time, on the port of the parent room, and stop
it before the next room. The phase-3 runner also reads the blind delay from the flow it
starts (`var DELAY = <ticks>;`) and stops if it differs from `phase3.blind_delay_ticks`. The
agent's pause after an action (`action_delay_ms`, 65 ms) must be longer than the tick, so that
an action has taken effect before the state is read.

To run one simulator by hand (Node-RED installed globally, `npm install -g node-red`):

```bash
node-red --userDir .node-red-lab2 --port 1893 simulator/simulator_flow_lab2.json
curl http://127.0.0.1:1893/health
curl http://127.0.0.1:1893/was/rl/status
curl -X POST http://127.0.0.1:1893/was/rl/action -H "Content-Type: application/json" -d "{\"Z1Light\": true}"
```

Stop it with Ctrl+C. Do not start a hand-run simulator on a port that a runner or a workflow
uses at the same time.

## 4. The HTTP interface

Every flow answers these five requests. The bodies are JSON.
The device names in the bodies are those of the room (`Z1Light`, `Z2Blinds`, `Spotlight`,
`PlugZ1`, ...); the agent learns the names and the two URLs it uses most from the room's
Thing Description in [`../src/resources/`](../src/README.md), and derives the other two.

| Request | What it does | Answer |
|---|---|---|
| `GET /was/rl/status` | Returns the state of the room: the level of each zone, the position of each switch, `Sunshine`, `EnergyCost` (this tick), `TotalEnergyCost` (since the last reset or `setState`) and `Hour`. The slow rooms add a `Tick` counter. The fields depend on the room. | the state |
| `POST /was/rl/action` | Sets the switches named in the body (booleans). It only changes the switches; the levels follow at the next tick. | `{"status":"ok"}` |
| `POST /was/rl/reset` | Starts a new episode: random start levels and switches, and a sun level drawn from 0, 100, 400 and 900 that stays fixed for the episode; energy counters to 0. | `{"reset":true}` |
| `POST /was/rl/setState` | Sets the state named in the body (any of the levels, switches and `Sunshine`), fixes the sun, and resets the energy counters. The scenario files are applied this way ([`../benchmark/README.md`](../benchmark/README.md)). | `{"status":"ok"}` |
| `GET /health` | Names the flow (below). | the answer below |

The answer of `/health`:

```json
{"status":"ok","tab":"lab2_tab","profile":"lab2","uptime_ms":12345,
 "endpoints":{"status":true,"action":true,"reset":true,"setState":true}}
```

`profile` is the name of the flow file without `simulator_flow_` and `.json` (a broken-device
flow reports `lab3_f1inv_z2`, not `lab3`); the runners compare it with the file they started.
`tab` is the id of the Node-RED tab; a fault flow keeps the tab of its parent
(`lab3_tab`). `uptime_ms` is the time since the first `/health` request.

## 5. The broken-device flows

[`generate_faulty_flows.ps1`](generate_faulty_flows.ps1) writes the 19 fault flows from the
healthy flows. For each file it reads the healthy flow, replaces exact text in the physics
function (for example `z1l ? 400` by `z1l ? 0` for a lamp that gives no light, or by
`z1l ? -400` for a lamp that takes light away; the replacement of a lamp or blind with the
text of its cross-zone contribution is listed in the script), renames the tab label, and sets
the `profile` in the `/health` answer to the name of the new file. If a text to replace is
not found, or the `/health` line is not found exactly once, the script stops with an error.
It then reads every generated file back to check that it is valid JSON.

The files are written as UTF-8 without a byte-order mark and with LF line endings, which is
the committed form. Running the script on the committed files therefore changes nothing (`git
status` stays clean). The phase-2 workflow runs it before the fault cells. Run it again, and
commit the result, only after a change to a healthy flow that a fault flow is derived from.
The script only changes files in this folder and needs no Node-RED.

## 6. The Node-RED user folders

Node-RED keeps its settings and its runtime files in a *user folder*. The runners give every
simulator its own, `.node-red-<profile>` in the repository root, for example `.node-red-lab2`
or `.node-red-lab3_f1dead`, and create it on the first start. The folders hold Node-RED's own
settings and installed-node information, not simulator logic: the flow is passed on the
command line. `.gitignore` excludes `.node-red-lab*/`, but five settings files of each of 16
folders were committed earlier and stay tracked; the other folders are local.
