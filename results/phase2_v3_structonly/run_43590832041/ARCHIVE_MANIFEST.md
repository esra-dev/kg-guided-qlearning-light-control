# Phase-2 structure-only archive — run 43590832041 (registered dispatch, seeds 1-20)

- **Workflow:** `.github/workflows/phase2.yml`, GitHub Actions run `43590832041`
  (completed success 2026-11-15 02:06Z, 282/282 jobs green), head `b5bf2146`,
  branch `phase2-structonly-2026-11`.
- **Registration:** `docs/phase2_structonly_registration_2026-11-14.md`
  (BINDING, commit `d43dd41b`, contained in the dispatch head; implementation
  freeze `c4334b2a`). Dispatch record:
  `docs/phase2_structonly_dispatch_record_2026-11-14.md`.
- **Inputs:** `adapt_profiles=lab2_f1bdead,lab3_f1bdead,lab3_f2dead_lowsun,labmon2_f2dead_lowsun`,
  `seeds=1-20`, `run_mode=phase1_v2_kg_only`, `adapt_episodes=0`,
  `knowledge_layer=brick`, `publish_results=true`
  (`analysis/out/workflow_inputs.json`).
- **Source artifact:** `phase2-consolidated`, id `14141229360`,
  `sha256:7148e099ccfbca24a64f6f3c999a331ba2c408d55486e50608cc721d6024e7f7`
  (36,353,346 bytes).
- **Publish:** results branch snapshot `phase2/43590832041-20261115-020615`
  (commit `ed473d6c` on `results`), verified on the remote; the Phase-2
  workflow creates no results tag (disclosed long-standing mechanism).
- **Treatment delivery (knowledge layer):** all 160 adaptation cells requested
  AND delivered the BRICK layer: every ADAPT_OK manifest records
  `knowledge_layer = brick` and `knowledge_layer_delivered = brick` (the
  delivered value is read back from the `StereotypeReasoner: knowledge layer`
  banner in the cell log at run time; a mismatch fails the cell). Per-cell
  banner evidence distilled from the downloaded adapt logs:
  `verification/adapt_log_knowledge_layer_evidence.csv` (160 rows, all
  `delivered_layer = brick`). The clean parents are NOT switched: all 120
  parents trained in-run under `phase1_v2_kg_only` (protocol `phase1-v2`
  TRAINING_OK manifests under `_artifacts/clean/`).
- **Treatment delivery (low sun):** the two `*_lowsun` cells started every
  adaptation episode from the pinned schedules
  `benchmark/train_scenarios_lab3_lowsun.json` (`119dc755…`) /
  `benchmark/train_scenarios_labmon2_lowsun.json` (`8e9f5467…`) — identical
  hashes to the round-3 delivered low-sun record; 80/80 manifests record
  `lowsun_pin_delivered=true`, `scenario_sunshine_values=[100]`.
- **Contents:** `recovery_root/seed*/` (ADAPT_OK gate manifests — protocol
  `phase2-v2`, detector `fault-detector-v2`, settled starts, zero fallbacks,
  paired-arm schedule identity, parent-qtable hashes, treatment-delivery and
  knowledge-layer keys — plus recovery CSVs with the full `BlacklistEvents`
  record and per-episode adapt metrics; recovery/metrics CSVs are gitignored
  per the generic ignore patterns, kept complete in this local extraction and
  in the 90-day workflow artifacts), `_artifacts/clean/` (parent protocol
  `phase1-v2` TRAINING_OK provenance + Q-tables; Q-tables local-only,
  hash-inventoried), `analysis/out/` aggregates + `workflow_inputs.json`
  (`knowledge_layer = brick`), `verification/` (adapt-log banner evidence).
- **Gates:** `python analysis/validate_phase2_v2_archive.py <this dir>
  --profiles lab2_f1bdead,lab3_f1bdead,lab3_f2dead_lowsun,labmon2_f2dead_lowsun
  --seeds 1-20 --require-treatment-delivery --require-knowledge-layer brick`
  passes (20 seeds × 4 profiles × 2 arms).
- **Inventory:** `SHA256SUMS.csv` (2,164 files).
