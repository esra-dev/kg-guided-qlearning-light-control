# Vocabulary of the knowledge graphs

Every lab has a knowledge graph (`src/resources/building_*.ttl`) and a device description
(`src/resources/interactions-*.ttl`). Both are written in Turtle, a text format for RDF. An
RDF graph is a set of statements of three parts (subject, predicate, object), called
*triples*; `lab:CeilingLight_Z1 brick:isLocatedIn lab:Zone1 .` is one triple. The short
word before the colon is a *prefix* that stands for a namespace, so terms from different
vocabularies can be told apart. This document lists the vocabularies, says which terms are
official and which are the project's own, and walks through one example. How the program
uses the terms is described in [ARCHITECTURE.md](ARCHITECTURE.md), section 5.

## 1. Prefixes

| Prefix | Namespace | Vocabulary | Used for |
|---|---|---|---|
| `brick:` | `https://brickschema.org/schema/Brick#` | Brick schema | building structure: rooms, zones, equipment classes (`brick:Luminaire`, `brick:Blind`, ...), location (`brick:isLocatedIn`), what feeds what (`brick:feeds`), parts (`brick:hasPart`) |
| `elem:` | `http://www.w3id.org/elementary#` | Elementary ontology (Ramanathan and Mayer, BuildSys 2023; Data-Centric Engineering 5:e40, 2024); only terms of the published ontology (section 3) | stereotypes, physical mechanisms, process variables, connection points, streams |
| `sosa:` | `http://www.w3.org/ns/sosa/` | W3C SOSA (sensors, observations) | which sensor observes which property (`sosa:observes`, `sosa:ObservableProperty`) |
| `qudt:`, `qudtqk:` | `http://qudt.org/schema/qudt/`, `http://qudt.org/vocab/quantitykind/` | QUDT (quantities and units) | the kind of a quantity (`qudt:hasQuantityKind qudtqk:Illuminance`, `qudtqk:ActivePower`, ...) |
| `td:`, `hctl:`, `htv:`, `js:`, `wotsec:` | `https://www.w3.org/2019/wot/td#` and related | W3C Web of Things Thing Description | the device description files: properties, actions, HTTP forms, data schemas, security |
| `ws:` | `http://example.org/was/lab/stereotypes#` | the project's own terms | stereotypes and mechanisms of the labs, the WoT mapping and state layout, and the project's extensions (section 4) |
| `lab:` | `http://example.org/was/lab#` | the project's own names | the individual things of a lab: zones, devices, sensors, streams |
| `rdf:`, `rdfs:`, `owl:`, `xsd:`, `dct:` | W3C and Dublin Core | general | types, labels, data types |

The prefix `elem:` is bound to `http://www.w3id.org/elementary#`, the namespace that the
published Elementary file (`core-ontology/elementary.owl.ttl` of
`github.com/autonomous-buildings/elementary`, commit `b56b3f9c`) declares, and it is used
only for terms that this file declares. The terms the project added itself carry the
project's prefix `ws:` (section 4). Until the hand-in clean-up the graphs bound `elem:` to
`http://w3id.org/elementary#` (without `www.`), a different namespace to an RDF tool, and
eight project terms also used the `elem:` prefix (section 3). The archived files under
`results/` keep the old names and the old address; the learned Turtle files there contain
no `elem:` term outside their `@prefix` line, so their triples are the same under either
address. The move changed names only: the action lists, starting values and query answers
that the program derives for every lab were compared before and after and are identical
once the renamed addresses are mapped back (one answer is itself a renamed address: the
quantity of a zone is now `ws:luminiscence`, before `elem:luminiscence`).

## 2. What each vocabulary contributes

- **Brick (structure).** Which devices exist, of which class, in which zone, and which
  sensor each device's light reaches (`brick:feeds`). The `brick` knowledge layer of the
  reasoner reads only this, together with the SOSA/QUDT typing and the WoT mapping.
- **Elementary (behaviour).** A *stereotype* describes a kind of device once
  (`ws:LampIncandescentStereotype`, `ws:BlindStereotype`, ...). It points to a *physical
  mechanism* (`elem:hasPhysicalMechanism`), and the mechanism names the *manipulated
  variable* (what the device changes, `elem:hasManipulatedVariable`), the *dependent
  variable* (what this affects, `elem:hasDependentVariable`) and, where it exists, an
  *independent variable* (a condition outside the device's control,
  `elem:hasIndependentVariable`, for example the outdoor light for a blind). Process
  variables carry the kind of stuff they deal with (`elem:dealsWithStuff`), their position
  (`elem:hasProcessPosition`: inlet or outlet) and their quantity (`elem:hasQuantity`).
  Connection points (`elem:InletConnectionPoint`, `elem:OutletConnectionPoint`) and streams
  (`elem:Stream`) describe how devices are linked.
- **SOSA and QUDT (measurement).** Which sensor observes which property, and that the
  property is an illuminance. The reasoner uses this to decide which zones have a light
  that the goal refers to.
- **Web of Things (interface).** The Thing Description tells `LabEnvironment` how to read
  the room and how to send each action. Inside the knowledge graph, every device carries
  its WoT action and state names (`ws:hasWoTActionSemanticType`,
  `ws:hasWoTStateSemanticType`) and its actions (`elem:hasComponentAction`, pointing to
  individuals of the class `ws:ComponentAction` with `ws:actionValue` true or false); this
  mapping defines the action list of both arms.
- **The project's own terms (`ws:`).** Everything the published vocabularies do not cover:
  the link from a device to its stereotype (`ws:hasBehavioralStereotype`), the layout of
  the learner's state, rank bounds, the minimum sun rank at which a blind has an effect,
  power dependencies, energy costs, and the completeness markers of Phase 1b.

The role of each term in the experiments is fixed by the knowledge-provenance contract,
[KNOWLEDGE_PROVENANCE.md](KNOWLEDGE_PROVENANCE.md), with its machine-readable table
`config/knowledge_provenance.csv` (checked by `analysis/tests/test_knowledge_provenance.py`).
That contract stays a separate document: it is binding for the Phase-1b registration and is
cited by the reasoner's source code and by two lab graphs.

## 3. Elementary terms: all from the published ontology

`analysis/elementary_term_conformance.py` compares every `elem:` term that the lab graphs
(`src/resources/*.ttl`) and the reasoner sources (`src/env/tools/*.java`) use with the
published Elementary file and writes the result to
`docs/evidence/elementary_term_conformance.csv`. The graphs and sources use 38 `elem:`
terms, and every one of them is declared in the published file:

| Kind in the published file | Terms |
|---|---|
| class | `Actuator`, `ConnectionPoint`, `Container`, `InletConnectionPoint`, `InternalConnection`, `OutletConnectionPoint`, `PhysicalMechanism`, `PhysicalStuff`, `ProcessVariable`, `Sensor`, `Stereotype`, `Stream`, `StreamJoiner`, `StreamKind` |
| property | `affectedUpstreamVariable`, `dealsWithStuff`, `directProportion`, `feeds`, `hasComponentAction`, `hasConnectionPoint`, `hasDependentVariable`, `hasIndependentVariable`, `hasManipulatedVariable`, `hasPhysicalMechanism`, `hasProcessPosition`, `hasQuantity`, `hasStreamKind`, `increases`, `inverseProportion`, `isPartOf`, `isRelatedToVariable`, `manipulates`, `observes`, `suitableFor` |
| individual | `contained`, `electrical_power`, `inlet`, `outlet` |

Eight terms that the project had coined itself used the `elem:` prefix until the
hand-in clean-up. They now carry the project's prefix `ws:` (spelling unchanged):

| Before | Now | What it is |
|---|---|---|
| `elem:hasBehavioralStereotype` | `ws:hasBehavioralStereotype` | the link from a device to its stereotype |
| `elem:hasStructuralStereotype` | `ws:hasStructuralStereotype` | the coupling class of a connection between zones (for example `ws:WeakOpticalCoupling`) |
| `elem:ComponentAction` | `ws:ComponentAction` | the class of the action individuals (`ws:SetZ1Blinds_ON`, ...); the property `elem:hasComponentAction` is published and keeps its prefix |
| `elem:luminiscence` | `ws:luminiscence` | the process variable "indoor illuminance" that the mechanisms change |
| `elem:electrical_power_input` | `ws:electrical_power_input` | the process variable "electrical power" that a lamp's mechanism manipulates |
| `elem:photons` | `ws:photons` | the kind of stuff that `ws:luminiscence` deals with |
| `elem:ResponseDynamic`, `elem:hasResponseDynamic` | (removed from the comment in `lab-ontology.ttl` where they were mentioned) | the slow labs use `ws:hasResponseDynamic` |

The most important of them is `ws:hasBehavioralStereotype`, which every query of the
reasoner follows from a device to its stereotype. The knowledge-provenance table
`config/knowledge_provenance.csv` lists it in layer 1 (paper-aligned stereotype). The
table also keeps a row for `elem:hasStereotype`, the link name of the published ontology,
which no graph and no query of this project uses.

## 4. The project's own `ws:` terms read by the program

The `ws:` namespace holds two kinds of things: names of individuals (the stereotypes,
mechanisms, process variables and connection points of the labs, such as
`ws:pm_daylight_ingress`) and properties that the program reads. The properties that
occur in the queries of `StereotypeReasoner.java`, and one class of the graphs:

| Term | Meaning | Layer of the provenance contract |
|---|---|---|
| `ws:hasWoTActionSemanticType`, `ws:hasWoTStateSemanticType`, `ws:actionValue` | the WoT action and state names of a device and the value of each of its actions; together with `elem:hasComponentAction` they define the action list | – (`elem:hasComponentAction`: 2, system topology) |
| `ws:stateVecIndex`, `ws:stateDomainSize`, `ws:stateSlotRole`, `ws:zoneIndex` | the layout of the learner's state: position, number of values and role (`zone_level`, `sunshine`, `boolean_actuator`) of each entry, and the number of a zone | – |
| `ws:rankBound_1`, `ws:rankBound_2`, `ws:rankBound_3` | the rank bounds of a sensed light level | 4, operational extension (named in `KNOWLEDGE_PROVENANCE.md`) |
| `ws:ivMinRank` | the lowest rank of the independent variable at which the mechanism has an observable effect in the simulator (1 in every lab graph that uses it) | 4, operational extension |
| `ws:powerGates` | a plug or breaker that must be on for a lamp to light (Phase-4 and chain labs) | 2, system topology |
| `ws:energyCost` | energy cost of a device per tick; declared only in `building_5_energy.ttl` (lab5) | 4, operational extension |
| `ws:behavioralDescriptionComplete` | marks a device's behavioural description as complete (Phase 1b) | 4, operational extension |
| `ws:gateWoTStateSemanticType`, `ws:gateMinValue` | bind a condition of a mechanism to a state entry and give the value at which it is met (Phase 1b) | 4, operational extension |
| `ws:connSource`, `ws:connTarget` | the two ends of an `elem:InternalConnection`; the reasoner reads them to find light that spills into another zone | 2, system topology |
| `ws:hasBehavioralStereotype` | the link from a device to its stereotype, followed by every query | 1, paper-aligned stereotype |
| `ws:hasStructuralStereotype` | the coupling class of an `elem:InternalConnection` (`ws:WeakOpticalCoupling` marks a secondary, weak spill) | 4, operational extension |
| `ws:ComponentAction` | the class of the action individuals named by `elem:hasComponentAction` (a class of the graphs; no query reads it) | 2, system topology |

`ws:responseDelay` (with `ws:responseDelayTicks` and `ws:responseDelaySamples`) is written
by the delay learner into `learned_dynamics_*.ttl` (Phase 3) and is not read back.
`KNOWLEDGE_PROVENANCE.md` states the binding rules for these terms: for example,
`ws:ivMinRank`, `ws:energyCost` and `ws:powerGates` must not be described as native terms
of the Elementary ontology.

## 5. A worked example: one lamp and one blind in lab2

From `src/resources/building_2_intermediate.ttl` (shortened). The device instances:

```turtle
lab:CeilingLight_Z1 a brick:Luminaire, elem:Actuator ;
    brick:isLocatedIn lab:Zone1 ;
    ws:hasBehavioralStereotype ws:LampIncandescentStereotype .

lab:Blind_Z1 a brick:Blind, elem:Actuator ;
    brick:isLocatedIn lab:Zone1 ;
    ws:hasBehavioralStereotype ws:BlindStereotype .

lab:CeilingLight_Z1 brick:feeds lab:LightSensor_Z1 .
lab:Blind_Z1        brick:feeds lab:LightSensor_Z1 .
lab:Outside         brick:feeds lab:Blind_Z1 .
```

The stereotypes and their mechanisms:

```turtle
ws:LampIncandescentStereotype a elem:Stereotype ;
    elem:hasPhysicalMechanism ws:pm_incandescent_light_emission .
ws:pm_incandescent_light_emission a elem:PhysicalMechanism ;
    elem:hasManipulatedVariable ws:electrical_power_input ;
    elem:hasDependentVariable   ws:luminiscence ;
    elem:increases              ws:luminiscence .

ws:BlindStereotype a elem:Stereotype ;
    elem:hasPhysicalMechanism ws:pm_daylight_ingress .
ws:pm_daylight_ingress a elem:PhysicalMechanism ;
    elem:hasIndependentVariable ws:outdoorIlluminance ;
    elem:hasManipulatedVariable ws:blindApertureRatio ;
    elem:hasDependentVariable   ws:luminiscence ;
    ws:ivMinRank                1 .

ws:luminiscence a elem:ProcessVariable ;
    elem:dealsWithStuff ws:photons ;
    elem:hasQuantity    qudtqk:Illuminance .
```

The WoT mapping and the actions:

```turtle
lab:Blind_Z1
    ws:hasWoTActionSemanticType "http://example.org/was#SetZ1Blinds" ;
    ws:hasWoTStateSemanticType  "http://example.org/was#Z1Blinds" ;
    ws:stateVecIndex 4 ; ws:stateDomainSize 2 ; ws:stateSlotRole "boolean_actuator" .
ws:SetZ1Blinds_ON  a ws:ComponentAction ; ws:actionValue "true"^^xsd:boolean .
ws:SetZ1Blinds_OFF a ws:ComponentAction ; ws:actionValue "false"^^xsd:boolean .
lab:Blind_Z1 elem:hasComponentAction ws:SetZ1Blinds_ON, ws:SetZ1Blinds_OFF .
```

What the program derives from it:

1. **Action list (both arms).** The WoT mapping gives the actions `SetZ1Blinds` off and on,
   `SetZ1Light` off and on, the same for zone 2, and "do nothing": nine actions.
2. **Effects (the knowledge arm, layer `full`).** The reasoner's enrichment query follows
   device → `ws:hasBehavioralStereotype` → `elem:hasPhysicalMechanism` → dependent
   variable with `elem:hasQuantity qudtqk:Illuminance`, and reads the independent variable
   and `ws:ivMinRank`. Result: the lamp raises the light of zone 1 unconditionally; the blind
   raises it only when the sun rank is at least 1. The query and its result rows for lab2
   are printed by `gradlew phase1SparqlEvidence`; a stored copy from 2026-07-21, made
   before the terms were moved and therefore with the old names and address, is
   `docs/evidence/lab2_sparql_execution_2026-07-21.txt`.
3. **Starting values.** In the state where zone 1 is at rank 0, zone 2 at rank 2, all
   devices are off and the sun is at rank 0 (with the demonstration targets rank 2 for both
   zones), the rules of [ARCHITECTURE.md](ARCHITECTURE.md), section 5.1 give, summed over
   the two zone tables: −100 for every "off" action (each is redundant: −50 in each table),
   −100 for both blinds "on" (sun below `ws:ivMinRank`: −50 in each table), +15 for
   zone 1's lamp "on" (two ranks missing in zone 1: 2 × 7.5), 0 for zone 2's lamp "on"
   (zone 2 is already at its target) and 0 for "do nothing". The same file shows these
   values; the knowledge arm would therefore first try zone 1's lamp.
4. **The `brick` layer** reads the same devices without the mechanisms. It still knows
   that both devices are in zone 1 and that zone 1 has a light sensor, so it treats both as
   helpful when zone 1 is too dark, but it does not know that the blind needs sun.
