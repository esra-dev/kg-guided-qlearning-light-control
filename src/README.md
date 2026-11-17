# Source code of the agent

This folder holds everything the JaCaMo platform runs: the Jason agent programs
(`agt/`), the Java classes with the CArtAgO artifacts (`env/tools/`), the knowledge graphs and
device descriptions that the agent loads (`resources/`), and the Java tests (`test/`). The
design is explained in [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md), the terms in
[`../docs/VOCABULARY.md`](../docs/VOCABULARY.md), and the rooms in
[`../docs/LABS.md`](../docs/LABS.md). This page is the map of the files.

One agent program runs at a time. Each is started through its own project file in the
repository root and a Gradle task (`build.gradle`); the runner scripts
(`run_full_project.ps1`, `run_phase2_adapt.ps1`, `run_phase3_dynamics.ps1`) call these tasks
for every cell of a campaign.

## `agt/`: agent programs

| File | Role | Started by | Phases |
|---|---|---|---|
| `illuminance_controller_agent_ql.asl` | Training: tabular Q-learning over the episodes of one lab, with the plain arm (`use_stereotypes(false)`) or the knowledge arm (`true`); writes the Q-tables and training metrics. | `task_ql.jcm`, Gradle task `taskQl`; `run_full_project.ps1` | 1, 1b, 4; also the parent training of Phase 2 |
| `illuminance_controller_agent_bench.asl` | Test: runs one agent mode (`rule_based`, `ql_false`, `ql_true`) over the fixed test situations of a lab and writes `benchmark_results_<mode>.csv` through `BenchmarkLogger`. | `task_bench.jcm`, `taskBench`; `runFullSweep`, called by `run_full_project.ps1` | 1, 1b, 4 |
| `illuminance_controller_agent_adapt.asl` | Fault stage: loads the Q-table of the healthy parent lab and continues in the lab with a broken device; detects the broken device from contradicted expectations, removes its actions, and learns again; writes the recovery files. | `task_adapt.jcm`, `taskAdapt`; `run_phase2_adapt.ps1` | 2 |
| `illuminance_controller_agent_dynamics.asl` | Slow-device stage: does not train a Q-table; probes each actuator to learn its response delay, then plans against time-bounded goals; writes the delay and compliance files. | `task_dynamics.jcm`, `taskDynamics`; `run_phase3_dynamics.ps1` | 3 |
| `lab_profiles.asl` | Not an agent: the registry of lab profiles (device description, knowledge graph files, scenario files, rank bounds, targets, training budget). The training, test, fault and dynamics programs include it and select a profile by name. | included by the four experiment programs | all |

The profile of a lab is selected by name (Gradle property `-Pprofile`, which the runners
set). Which flow, knowledge graph and scenario files each profile names is listed per lab in
[`../docs/LABS.md`](../docs/LABS.md), which is generated from this file.

## `env/tools/`: Java classes

Package `tools`. The six artifacts are CArtAgO classes that the agent programs create with
`makeArtifact` and call through their operations; the others are plain Java helpers or
command-line programs.

### Artifacts used by the agent programs

| Class | What it does | Created by |
|---|---|---|
| `LabEnvironment` | The agent's window on the simulated room: reads the Web of Things description, reads the state, sends actions and states to the simulator, loads scenario files, and turns lux values into ranks. | all four agent programs |
| `QLearner` | Decomposed tabular Q-learning: state encoding, the action space (taken from the knowledge graph through `StereotypeReasoner`), Q-table initialisation, action selection, the update, and the training and fault-stage files. | training, test, fault and dynamics programs |
| `BenchmarkLogger` | Records the per-step and per-run metrics of the test (goal reached, steps, wasted steps, actuator cycling, policy energy, and others) and writes the result files. | test program |
| `StereotypeLearner` | Accumulates the observed effect of every action on every state entry (running mean and variance) and can write the effects it found as Turtle. | training and fault programs |
| `DynamicsLearner` | Accumulates the measured response delay of each actuator. | slow-device program |
| `OntologyArtifact` | SPARQL queries over the knowledge graph (Jena) for the test program's knowledge-graph reading. | test program |

### Helpers (not artifacts)

| Class | What it does |
|---|---|
| `StereotypeReasoner` | Loads the knowledge graph and answers the queries that give the action registry, the state layout, the starting values, the soft preference among actions and the fault-expectation model, for each knowledge layer (full, Brick-only, redundancy). Created by `QLearner`. |
| `ScenarioCatalog` | Strict, ordered loader of the scenario files (training schedule and test situations). Used by `LabEnvironment`. |
| `FirstGoalPresentationTracker` | Counts how often each training situation is presented and when its goal is first reached. Owned by `QLearner`. |
| `Phase1PolicyEnergy` | Deterministic actuator power per decision (the policy-energy measure). Used by `BenchmarkLogger`. |
| `SimulatorHttpClient` | HTTP client with timeouts and bounded retries for the simulator. Used by `LabEnvironment`. |
| `WotInputValidator` | Checks actions and states against the Web of Things description before they reach the simulator. Used by `LabEnvironment`. |
| `Converters` | Conversions between Jason terms and Java values, shared by the artifacts. |
| `LoggingBootstrap` | Routes `java.util.logging` into SLF4J (configured by `resources/logback.xml`). Called by `LabEnvironment`. |
| `jia/` (`SystemProp`, `system_prop`, `system_prop_num`) | Jason internal actions `tools.jia.system_prop` and `system_prop_num`: read a Java system property (`-D...`) into an agent program, so that runners and Gradle can set values without editing the program text. |

### Command-line programs

Started by Gradle tasks of `build.gradle`; no agent calls them.

| Class | Gradle task | What it does |
|---|---|---|
| `TurtleValidator` | `validateTurtle` | Parses every Turtle file in a folder with Jena; exit 1 if one fails. Part of `preflight`. |
| `RegistryGoldenCheck` | `verifyActionRegistry` | Checks that the action registry discovered from each registered lab's knowledge graph equals the stored golden file in `config/golden_registry/`. Part of `preflight`. |
| `ActionRegistryDump` | `dumpActionRegistry` | Writes those golden files; run only after an intended change, and review the diff. |
| `OverlaySynthesizer` | `synthesizeOverlay` | Turns the raw effect statistics of `StereotypeLearner` into a learned overlay Turtle file and a claims CSV. |
| `Phase1SparqlEvidence` | `phase1SparqlEvidence` | Prints a worked example of knowledge-graph use for lab2; its output is stored in [`../docs/evidence/`](../docs/evidence/). |

## `resources/`: knowledge graphs, device descriptions, logging

All files are on the class path, so profiles name them without a folder. Their meaning
per lab is in [`../docs/LABS.md`](../docs/LABS.md); the terms and the part of the vocabulary
that is not in the published Elementary ontology are in
[`../docs/VOCABULARY.md`](../docs/VOCABULARY.md) and
[`../docs/KNOWLEDGE_PROVENANCE.md`](../docs/KNOWLEDGE_PROVENANCE.md).

| Files | Contents |
|---|---|
| `building_<n>_<name>.ttl` | The knowledge graph of one lab: Brick structure (zones, components, feeds) plus the component stereotypes and their physical mechanisms from the Elementary ontology. Self-contained; a profile lists it in its `ont([...])`. The variants with `_infoonly` and `_nostereo` belong to the monitor labs, in which the stereotype of the monitor is reduced or missing. |
| `interactions-<lab>.ttl` | The Web of Things Thing Description of one lab: the properties and the actions its simulator flow accepts, with the port of that lab. `LabEnvironment` reads it. |
| `lab-ontology.ttl`, `wot-mappings.ttl`, `interactions-lab-custom2.ttl` | The first, shared knowledge graph, the mapping from its actions to Web of Things actions, and a Thing Description. No lab profile uses them. They remain as fixtures of unit tests (`StereotypeReasonerOntologyLoadTest`, `TestInfrastructureSmokeTest`, `WotInputValidatorTest`) and, for the first two, as the registry set `custom` of the action-registry check. |
| `logback.xml` | Logging configuration: a console appender and a JSON appender (switched with `-Dlogback.json`). |

The files that a lab uses are generated into the lab's table in
[`../docs/LABS.md`](../docs/LABS.md). The learned files that the agent writes at run time
(learned stereotypes, Q-tables, metrics) are not here; they go to the run's result folders
and are archived under [`../results/`](../results/README.md).

## `test/`: Java tests

`./gradlew test` runs them with JUnit 5; no simulator is needed. The Python tests are in
[`../analysis/tests/`](../analysis/README.md#7-other-files-in-this-folder).

| Test class (in `test/java/tools/`) | What it protects |
|---|---|
| `TestInfrastructureSmokeTest` | That JUnit and the test class path work and the bundled ontology is on it. |
| `StereotypeReasonerOntologyLoadTest`, `StereotypeReasonerInMemoryLoaderTest` | Loading of the production graph and of an in-memory fixture; a non-trivial, consistent action registry and state layout. |
| `BrickOnlyKnowledgeLayerTest` | In lab1 to lab3 the Brick-only layer makes the same structural claims as the full layer but carries no Elementary mechanism knowledge (no sunshine gate on the blinds, untyped cross-zone coupling). |
| `Phase4KnowledgeLadderLayerTest`, `Phase4KgDiscoveryTest` | What each knowledge layer delivers in the dependency and energy labs; the discovery of power gates and energy costs. |
| `Phase4PolicyEnergyTest`, `Phase1ProtocolV2Test` | The policy-energy weights; the bookkeeping of the corrected protocol (scenario order, first-goal counts). |
| `Phase1bStateSpaceTest`, `Phase1bRelevanceDirectionTest` | The state spaces of the Phase-1b labs and the relevance and direction channels of the reasoner. |
| `Phase2DetectorV2Test`, `Phase2AdaptSettingsTest`, `Phase2QGapStateEncodingTest` | The fault detector's abstain rule; the seeding of the fault-stage random stream and strict reading of numeric properties; the state encoding behind the Q-gap study. |
| `MonitorKgDiscoveryTest` | Discovery of the monitor lab, in which the lighting device is not described by the stereotype. |
| `OverlaySynthesizerTest`, `OverlayRoundTripTest` | The learned-overlay writer and the loading of an overlay after the incomplete graph. The second uses `test/resources/phase5_test_nospill_lab.ttl`. |
| `BenchmarkFormatMixTest`, `ScenarioFallbackCountTest` | Mixed protocol records are an error; the fallback counter that the runners copy into their receipts. |
| `StereotypeEffectivePropsTest` | The report of the values actually in force, which the runner copies into `TRAINING_OK.json`. |
| `SimulatorHttpClientTest`, `WotInputValidatorTest` | Retry behaviour against a local test server; validation against a Thing Description. |
| `jia/SystemPropTest` | The internal action `system_prop`. |
