import React, { useMemo, useState } from 'react';
import { LABS, offState } from '../lib/physics.js';
import Sandbox from '../components/Sandbox.jsx';
import Replay from '../components/Replay.jsx';
import { HBarChart, Legend } from '../components/charts.jsx';
import SourceTag from '../components/SourceTag.jsx';
import { useData, fmt } from '../lib/useData.js';

function Lab4Trap() {
  const lab = LABS.lab4;
  const trap = useMemo(() => ({ ...offState(lab), Z1Light: true, PlugZ1: false }), []);
  return (
    <div className="card">
      <div className="cardhead">
        <h3>The trap: lamp switch ON, smart plug OFF</h3>
        <SourceTag kind="illustration">toy room that starts in the trapped state</SourceTag>
      </div>
      <p className="note" style={{ marginTop: 0 }}>
        Zone 1 stays dark although its lamp switch is already ON. The fix is to switch the <b>plug</b> on, not the lamp.
        The graph holds <code>PlugZ1 ws:powerGates Z1Light</code>, which tells the knowledge arm to look at the plug; the
        plain arm has no such information and has to find out by trying. Try it: turn the plug on.
      </p>
      <Sandbox lab={lab} initial={trap} initialSun={0} showPower budget={null} />
    </div>
  );
}

function RegisteredFamily({ rows }) {
  return (
    <div style={{ overflowX: 'auto' }}>
      <table className="data">
        <thead><tr><th>registered member</th><th className="num">knowledge</th>
          <th className="num">plain</th><th className="num">mean Δ</th>
          <th className="num">95% CI</th><th className="num">exact p</th>
          <th className="num">BH q</th><th>verdict</th></tr></thead>
        <tbody>{rows.map((row) => (
          <tr key={row.name} className={row.verdict === 'supported' ? 'hl' : ''}>
            <td>{row.name}</td>
            <td className="num">{fmt(row.mean_ql_true, 4)}</td>
            <td className="num">{fmt(row.mean_ql_false, 4)}</td>
            <td className="num">{fmt(row.mean, 5)}</td>
            <td className="num">[{fmt(row.ci_lo, 5)}, {fmt(row.ci_hi, 5)}]</td>
            <td className="num">{fmt(row.p, 7)}</td>
            <td className="num">{fmt(row.q, 7)}</td>
            <td>{row.verdict}</td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}

function LadderTrend({ rows }) {
  return (
    <div className="card">
      <h3>Does the advantage grow with the depth of the dependency?</h3>
      <SourceTag kind="results">depth contrasts, generated from the stored results</SourceTag>
      <div style={{ overflowX: 'auto' }}>
        <table className="data">
          <thead><tr><th>depth contrast (Δ of per-seed Δs)</th><th>role</th>
            <th className="num">mean</th><th className="num">95% CI</th>
            <th className="num">exact p</th></tr></thead>
          <tbody>{rows.map((row) => (
            <tr key={row.contrast}>
              <td>{row.contrast.replaceAll('_', ' ')}</td>
              <td>{row.role === 'registered_ordered_secondary' ? 'registered secondary' : 'descriptive'}</td>
              <td className="num">{fmt(row.mean, 5)}</td>
              <td className="num">[{fmt(row.ci_lo, 5)}, {fmt(row.ci_hi, 5)}]</td>
              <td className="num">{fmt(row.p, 3)}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      <p className="note">Each row is the difference between two labs of the per-seed difference
        (knowledge arm minus plain arm) in steps per test run. A row whose interval contains zero shows no
        measurable growth with depth; here {rows.filter((r) => r.ci_lo <= 0 && r.ci_hi >= 0).length} of
        {' '}{rows.length} intervals contain zero. An earlier protocol (v1) had suggested growth with depth; that
        result is withdrawn.</p>
    </div>
  );
}

function EnergyResult({ lab5, levels }) {
  const groups = [{
    name: 'energy compliance',
    note: `goal reached AND steady power within budget (higher is better; ${levels.n_seeds} seeds)`,
    bars: [
      { label: 'knowledge arm', value: levels.ql_true, color: 'var(--kg)' },
      { label: 'plain arm', value: levels.ql_false, color: 'var(--tr)' },
      { label: 'hand-written controller', value: levels.rule_based, color: 'var(--ctx)' },
    ],
  }];
  return (
    <>
      <SourceTag kind="results">lab5 levels from the corrected table; paired statistics from the registered table</SourceTag>
      <Legend items={[
        { color: 'var(--kg)', label: `knowledge arm (ql_true, ${levels.n_seeds} seeds)` },
        { color: 'var(--tr)', label: `plain arm (ql_false, ${levels.n_seeds} seeds)` },
        { color: 'var(--ctx)', label: 'hand-written controller (rule_based)' }]} />
      <HBarChart groups={groups} xMax={1} fmt={(v) => fmt(v, 3)} />
      <p className="note">
        The energy cost is <b>not in the reward</b>: the only route to energy awareness is the graph’s
        <code> ws:energyCost</code> rating of each device. The paired difference between the two learning arms is
        +{fmt(lab5.mean, 4)} (q = {fmt(lab5.q, 4)}).
      </p>
      <p className="note">
        <b>Why these levels differ from the archived table:</b> {levels.n_zero_step_runs_per_arm.toLocaleString('en-US')} of
        the {levels.n_test_runs_per_arm.toLocaleString('en-US')} test runs per arm need no step, so the step log has no row
        for them. The archived table scored those runs as “all devices off” ({fmt(levels.archived_ql_true, 3)} and
        {' '}{fmt(levels.archived_ql_false, 3)}); the corrected levels score them from the start state the scenario file
        declares. The paired difference is the same under both scorings.
      </p>
    </>
  );
}

export default function Phase4() {
  const [labId, setLabId] = useState('lab4');
  const { data } = useData('phase4.json');
  const lab = LABS[labId];
  if (!data) return <p className="loading">loading corrected Phase 4 evidence…</p>;
  const lab5 = data.registered_family.find((row) => row.cell === 'lab5');
  return (
    <div className="page">
      <h1>Phase 4 · Knowledge Labs</h1>
      <div className="q"><b>What the results below are:</b> protocol <code>{data.run_mode}</code>, runs {data.runs.seeds_1_10} +
        {' '}{data.runs.seeds_11_20}, {data.lab5_levels.n_seeds} paired seeds, registered family of {data.registered_family.length} cells.</div>
      <div className="warn"><b>An earlier protocol (v1) had a defect and its results are withdrawn.</b> The numbers
        here come from the corrected campaign. See <code>docs/notices/PHASE4_PROTOCOL_AFFECTED_NOTICE_2026-07-22.md</code>;
        registration: <code>docs/registrations/phase4_correction_registration_2026-07-22.md</code>.</div>
      <div className="q"><b>Question:</b> can the knowledge graph hold facts that a plain learner cannot see directly
        (a hidden wiring dependency and the energy rating of each device), and does the knowledge arm use them?</div>

      <div className="toolbar" role="tablist">
        {['lab4', 'lab5'].map((id) => (
          <button key={id} type="button" role="tab" aria-selected={labId === id}
            className={`btn ${labId === id ? 'primary' : ''}`} onClick={() => setLabId(id)}>
            {LABS[id].name}
          </button>
        ))}
      </div>
      <p className="lede" style={{ marginTop: 0 }}>{lab.tagline} · {lab.states.toLocaleString('en-US')} states · port {lab.port}</p>

      {labId === 'lab4' ? (
        <>
          <Lab4Trap />
          <div className="card">
            <div className="cardhead"><h3>Full toy room</h3>
              <SourceTag kind="illustration">the plug draws no power itself, it only gates the lamp</SourceTag></div>
            <Sandbox lab={lab} showPower />
            <p className="note"><b>What the knowledge graph says:</b> {lab.kg}</p>
          </div>
          <div className="card">
            <Replay lab={lab} title="Replay of one early-prototype run: lab4 scenarios" />
          </div>
        </>
      ) : (
        <>
          <div className="card">
            <div className="cardhead">
              <h3>Toy room: two lamps, same light, different power</h3>
              <SourceTag kind="illustration">watch the power meter against the budget; the budget value is the toy room’s own</SourceTag>
            </div>
            <Sandbox lab={lab} showPower budget={lab.energyBudget} initialSun={0} />
            <p className="note"><b>What the knowledge graph says:</b> {lab.kg}</p>
          </div>
          <div className="card">
            <Replay lab={lab} title="Replay of one early-prototype run: lab5 scenarios" />
          </div>
          <div className="card">
            <EnergyResult lab5={lab5} levels={data.lab5_levels} />
          </div>
        </>
      )}

      <h2>Registered family of {data.registered_family.length} (corrected)</h2>
      <div className="card">
        <SourceTag kind="results">registered tests, generated from the stored results</SourceTag>
        <RegisteredFamily rows={data.registered_family} />
        <p className="note">Two-sided exact paired sign-flip tests over the paired seeds; bootstrap intervals estimate
          means; BH correction is applied once across these rows. The three redundancy rows count steps per test run
          (the registered measure “redundant actions”), so a negative Δ favours the knowledge arm; the lab5 row is
          energy compliance, where a positive Δ favours it. The lab5 levels use the corrected scoring explained in
          the lab5 panel. All values are conditional on the simulator.</p>
      </div>

      <LadderTrend rows={data.ladder_trend} />

      <p className="note">Sources: runs {data.runs.seeds_1_10} and {data.runs.seeds_11_20};
        {' '}<code>{data.source}</code>; registration <code>docs/registrations/phase4_correction_registration_2026-07-22.md</code>;
        corrected lab5 levels: <code>{data.lab5_levels.source}</code>.</p>
    </div>
  );
}

