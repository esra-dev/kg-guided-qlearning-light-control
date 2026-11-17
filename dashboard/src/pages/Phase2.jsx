import React from 'react';
import FaultLab from '../components/FaultLab.jsx';
import SourceTag from '../components/SourceTag.jsx';
import { HBarChart, Legend } from '../components/charts.jsx';
import { useData, fmt } from '../lib/useData.js';

function Tier1Results({ data, nSeeds }) {
  const bound = data.adapt_horizon + 1;
  const groups = data.tier1.map((row) => ({
    name: row.name,
    note: `Δ ${fmt(row.mean, 0)} ep · q ${row.q.toPrecision(2)} · ${row.verdict}`
      + (row.censored_ql_true + row.censored_ql_false > 0
        ? ` · censored ${row.censored_ql_true}/${row.censored_ql_false}` : ''),
    bars: [
      { label: 'knowledge arm', value: row.mean_ql_true, color: 'var(--kg)' },
      { label: 'plain arm', value: row.mean_ql_false, color: 'var(--tr)' },
    ],
  }));
  return (
    <>
      <Legend items={[{ color: 'var(--kg)', label: `knowledge arm (ql_true${nSeeds ? `, ${nSeeds} seeds` : ''})` },
        { color: 'var(--tr)', label: `plain arm (ql_false${nSeeds ? `, ${nSeeds} seeds` : ''})` }]} />
      <HBarChart groups={groups} unit="episodes" fmt={(v) => String(Math.round(v))} />
      <p className="note">
        <b>RecoveryEpisodes</b> = episodes from fault detection back to a re-converged policy over the
        surviving devices (lower is better). A mean of {bound.toLocaleString('en-US')} is the censoring bound
        (horizon {data.adapt_horizon.toLocaleString('en-US')} + 1): that arm <b>never detected the fault</b> in those
        runs, so it never adapted, and the seed pair is kept, not dropped. Two-sided exact paired sign-flip tests
        over the paired seeds, BH correction across the frozen family of {data.tier1.length} cells.
      </p>
      <details className="tblview"><summary>table view: registered Tier-1 family ({data.tier1.length} cells)</summary><div className="inner">
        <table className="data">
          <thead><tr><th>cell</th><th className="num">knowledge mean</th><th className="num">plain mean</th>
            <th className="num">Δ</th><th className="num">95% CI</th><th className="num">exact p</th>
            <th className="num">BH q</th><th className="num">censored knowledge/plain</th><th>verdict</th></tr></thead>
          <tbody>
            {data.tier1.map((row) => (
              <tr key={row.cell} className={row.verdict === 'supported' ? 'hl' : ''}>
                <td>{row.name}</td>
                <td className="num">{fmt(row.mean_ql_true, 1)}</td>
                <td className="num">{fmt(row.mean_ql_false, 1)}</td>
                <td className="num">{fmt(row.mean, 2)}</td>
                <td className="num">[{fmt(row.ci_lo, 1)}, {fmt(row.ci_hi, 1)}]</td>
                <td className="num">{row.p.toPrecision(3)}</td>
                <td className="num">{row.q.toPrecision(3)}</td>
                <td className="num">{row.censored_ql_true}/{row.censored_ql_false}</td>
                <td>{row.verdict}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div></details>
    </>
  );
}

function DetectionTable({ rows }) {
  return (
    <details className="tblview"><summary>table view: detection family ({rows.length} cells, episodes until the fault is detected)</summary><div className="inner">
      <table className="data">
        <thead><tr><th>cell</th><th className="num">knowledge mean</th><th className="num">plain mean</th>
          <th className="num">Δ</th><th className="num">BH q</th>
          <th className="num">censored knowledge/plain</th></tr></thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.cell}>
              <td>{row.name}</td>
              <td className="num">{fmt(row.mean_ql_true, 2)}</td>
              <td className="num">{fmt(row.mean_ql_false, 2)}</td>
              <td className="num">{fmt(row.mean, 2)}</td>
              <td className="num">{row.q.toPrecision(3)}</td>
              <td className="num">{row.censored_ql_true}/{row.censored_ql_false}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div></details>
  );
}

export default function Phase2() {
  const { data } = useData('phase2.json');
  const { data: overview } = useData('overview.json');
  if (!data || !overview) return <p className="loading">loading corrected Phase 2 results…</p>;
  const o = overview.phase2;
  const adverse = data.tier1.filter((row) => row.verdict === 'adverse');
  const identical = data.detection.filter((row) => row.mean === 0).length;
  return (
    <div className="page">
      <h1>Phase 2 · Faulty Labs</h1>
      <div className="q"><b>What the results below are:</b> protocol <code>{data.protocol_version}</code>, detector
        {' '}<code>{data.detector_version}</code>, runs {Object.values(data.runs).join(' / ')}, {o.n_seeds} paired
        seeds, explicit censoring at the horizon.</div>
      <div className="warn"><b>An earlier protocol (v1) had a defect and its results are withdrawn.</b> The corrected
        record below replaces them. See <code>docs/notices/PHASE2_PROTOCOL_AFFECTED_NOTICE_2026-07-22.md</code>;
        registration: <code>docs/registrations/phase2_correction_registration_2026-07-22.md</code>.</div>
      <div className="q"><b>Question:</b> when a real device silently breaks, can the agent <b>detect</b> it
        (reality differs from the knowledge graph), <b>blacklist</b> it, <b>alert</b> the user and <b>re-learn</b> over
        what survives, and does the knowledge arm recover faster than the plain arm?</div>

      <p className="lede">
        Each faulty lab is a clean Phase-1 lab in which the simulator injects one hardware fault (a lamp or
        blind that is <b>dead</b> or <b>inverted</b>) while the knowledge graph keeps describing normal physics.
        The agent should not silently adapt: it has to detect the fault first.
      </p>

      <FaultLab />

      <h2>Registered result: {o.n_supported} supported, {o.n_adverse} adverse, {o.n_null} null</h2>
      <div className="card">
        <SourceTag kind="results">registered Tier-1 cells, generated from the stored results</SourceTag>
        <Tier1Results data={data} nSeeds={o.n_seeds} />
      </div>

      <div className="card">
        <h3>The trade between false alarms and missed faults</h3>
        <SourceTag kind="results">counts generated from the stored results</SourceTag>
        <p>The stored event record contains <b>{data.false_positive_blacklist_events}</b> blacklist events that
          name a device outside the injected fault. In
          {' '}{o.adverse_cells_knowledge_arm_never_detected} of the {adverse.length} adverse cells
          ({adverse.map((row) => row.name).join('; ')}) the knowledge arm never detected the fault in any of the
          {' '}{o.n_seeds} paired runs, while the plain arm did not detect it in
          {' '}{adverse.map((row) => `${row.censored_ql_false}`).join(', ')} of them.</p>
        <p className="note">Explanation given in the campaign manifest: the detector refuses a “dead” verdict while
          another light source of the same zone is switched on, and the knowledge arm’s policy keeps such helper
          devices on, so it rarely reaches a state in which the verdict is allowed. The detection family below is a new
          instrument (detector v2) and is not comparable to the withdrawn record. In {identical} of
          {' '}{data.detection.length} detection cells the two arms have identical mean detection times. All values are
          conditional on the simulator.</p>
        <DetectionTable rows={data.detection} />
      </div>

      <p className="note">Sources: <code>{data.source}</code>;
        {' '}<code>results/phase2_v2_corrected/CAMPAIGN_MANIFEST.md</code>.</p>
    </div>
  );
}
