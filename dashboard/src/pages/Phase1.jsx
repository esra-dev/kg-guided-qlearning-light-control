import React, { useState } from 'react';
import { LABS } from '../lib/physics.js';
import Sandbox from '../components/Sandbox.jsx';
import { Tile } from '../components/charts.jsx';
import SourceTag from '../components/SourceTag.jsx';
import { useData, fmt } from '../lib/useData.js';

const P1_LABS = ['lab1', 'lab2', 'lab3'];

function LabTabs({ cur, set }) {
  return (
    <div className="toolbar" role="tablist">
      {P1_LABS.map((id) => (
        <button key={id} type="button" role="tab" aria-selected={cur === id}
          className={`btn ${cur === id ? 'primary' : ''}`} onClick={() => set(id)}>
          {LABS[id].name}
        </button>
      ))}
    </div>
  );
}

function LabResult({ labId, data }) {
  const row = data.labs[labId];
  const isLab1 = labId === 'lab1';
  const episodes = data.protocol_gates.episodes_per_training_cell;
  return (
    <>
      <h3>{row.headline}</h3>
      <SourceTag kind="results">knowledge arm minus plain arm, mean over paired seeds</SourceTag>
      <div className="tiles">
        <Tile label="training auc_goal Δ (knowledge − plain)"
          value={fmt(row.auc_goal, 6)}
          delta={row.auc_goal === 0 ? 'exactly zero in this lab'
            : `change in the share of successful training episodes (of ${episodes.toLocaleString('en-US')} per run)`}
          deltaDir={row.auc_goal > 0 ? 'up' : (row.auc_goal < 0 ? 'down' : 'flat')} />
        <Tile label="first-goal presentations Δ"
          value={fmt(row.first_goal_presentations, 3)}
          delta="negative is earlier; includes terminal starts and censoring"
          deltaDir={row.first_goal_presentations < 0 ? 'up' : 'flat'} />
        {!isLab1 && <Tile label="benchmark goal-rate Δ"
          value={fmt(row.benchmark_goal_rate, 5)}
          delta="registered descriptive"
          deltaDir={row.benchmark_goal_rate > 0 ? 'up' : 'flat'} />}
        {!isLab1 && <Tile label="deterministic policy-energy Δ"
          value={fmt(row.benchmark_policy_energy, 4)}
          delta="policy-cost units, not watt-hours; lower is better"
          deltaDir={row.benchmark_policy_energy < 0 ? 'up' : 'down'} />}
        {!isLab1 && <Tile label="benchmark cycling Δ"
          value={fmt(row.benchmark_cycling, 5)}
          delta={row.benchmark_cycling < 0 ? 'lower for the knowledge arm (descriptive)' : 'not lower for the knowledge arm (descriptive)'}
          deltaDir={row.benchmark_cycling < 0 ? 'up' : 'flat'} />}
      </div>
    </>
  );
}

function RegisteredFamily({ rows }) {
  return (
    <div style={{ overflowX: 'auto' }}>
      <table className="data">
        <thead><tr><th>registered member</th><th className="num">mean Δ</th>
          <th className="num">95% CI</th><th className="num">exact p</th>
          <th className="num">BH q</th><th>verdict</th></tr></thead>
        <tbody>{rows.map((row) => (
          <tr key={row.name} className={row.verdict === 'supported' ? 'hl' : ''}>
            <td>{row.name}</td>
            <td className="num">{fmt(row.mean, 6)}</td>
            <td className="num">[{fmt(row.ci_lo, 6)}, {fmt(row.ci_hi, 6)}]</td>
            <td className="num">{fmt(row.p, 7)}</td>
            <td className="num">{fmt(row.q, 7)}</td>
            <td>{row.verdict}</td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}

export default function Phase1() {
  const [labId, setLabId] = useState('lab2');
  const { data } = useData('phase1.json');
  const { data: overview } = useData('overview.json');
  const lab = LABS[labId];
  if (!data) return <p className="loading">loading corrected Phase 1 results…</p>;
  const nSeeds = overview ? overview.phase1.n_seeds : null;
  const members = Object.fromEntries(data.registered_family.map((row) => [row.name, row]));
  const residual = members['Arm C minus redundancy, lab2'];
  const lab3 = data.labs.lab3;
  return (
    <div className="page">
      <h1>Phase 1 · Clean Labs</h1>
      <div className="q"><b>What the results below are:</b> protocol <code>{data.protocol_version}</code>,
        {nSeeds != null ? ` ${nSeeds} paired seeds,` : ''} {data.protocol_gates.episodes_per_training_cell.toLocaleString('en-US')} training
        episodes per run, {Object.keys(data.runs).length} runs (the knowledge arm and three controls).</div>
      <div className="warn"><b>An earlier protocol (v1) had a defect and its results are withdrawn.</b> The
        numbers on this page come from the corrected campaign only. See
        {' '}<code>docs/notices/PHASE1_PROTOCOL_AFFECTED_NOTICE_2026-07-21.md</code>.</div>

      <LabTabs cur={labId} set={setLabId} />
      <p className="lede" style={{ marginTop: 0 }}>{lab.tagline} · {lab.states.toLocaleString('en-US')} states</p>
      <div className="card">
        <div className="cardhead"><h3>Toy room (click the devices)</h3>
          <SourceTag kind="illustration">a re-implementation of the lab’s light formulas; energy, faults and delays are simplified</SourceTag></div>
        <Sandbox lab={lab} />
        <p className="note"><b>What the knowledge graph says:</b> {lab.kg}</p>
      </div>

      <div className="card"><LabResult labId={labId} data={data} /></div>

      <h2>Registered family of five</h2>
      <div className="card">
        <SourceTag kind="results">registered tests, table generated from the stored results</SourceTag>
        <RegisteredFamily rows={data.registered_family} />
        <p className="note">Two-sided paired sign-flip tests; bootstrap intervals estimate
          means; BH correction is applied once across these five rows. No bootstrap
          pseudo-p-values are used.</p>
      </div>

      <div className="card">
        <h3>Attribution and controls</h3>
        <SourceTag kind="results">decomposition and controls from the stored results</SourceTag>
        <p>A control that only discourages redundant moves reproduces
          {' '}<b>{fmt(100 * data.decomposition.redundancy_share, 1)}%</b> of the knowledge arm’s lab2 mean
          effect ({data.decomposition.category}). The knowledge arm keeps a smaller positive residual
          of {fmt(residual.mean, 6)} (q = {fmt(residual.q, 7)}). The baseline and the reward-shaping-only
          controls show {data.controls.baseline_corrected_contrasts_nonzero} and
          {' '}{data.controls.pbrs_corrected_contrasts_nonzero} non-zero corrected contrasts; only a
          withdrawn timing-dependent energy diagnostic varies.</p>
        <p className="note">In lab3 the deterministic policy-energy difference is
          {' '}{fmt(lab3.benchmark_policy_energy, 3)} ({lab3.benchmark_policy_energy > 0 ? 'higher' : 'lower'} for the
          knowledge arm; descriptive only). Policy-cost weights are arbitrary and are not watt-hours. These toy
          simulations do not establish a benefit in a real building or the need for RDF/SPARQL.</p>
        <p className="note">Sources: runs {Object.values(data.runs).join(', ')};
          {' '}<code>{data.source}</code>; report <code>docs/reports/phase1_results_v2.md</code>.</p>
      </div>
    </div>
  );
}
