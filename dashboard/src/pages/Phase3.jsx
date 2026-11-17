import React, { useState } from 'react';
import { LABS, SLOW } from '../lib/physics.js';
import Sandbox from '../components/Sandbox.jsx';
import { HBarChart, Legend, Tile } from '../components/charts.jsx';
import SourceTag from '../components/SourceTag.jsx';
import { useData, fmt } from '../lib/useData.js';
import { LampIcon, BlindIcon } from '../components/icons.jsx';

function DeadlinePlanner() {
  const [goal, setGoal] = useState(null); // 'now' | 'later'
  return (
    <div className="card">
      <div className="cardhead">
        <h3>Using the learned delay: deadline-aware planning</h3>
        <SourceTag kind="illustration">uses its own round numbers (5 s lamp, 60 s blind, energy 1 per tick for the lamp, 0 for the blind), not values from the results</SourceTag>
      </div>
      <p className="note" style={{ marginTop: 0 }}>Sunny episode (sun 900): both devices can bring the zone to bright.</p>
      <div className="toolbar">
        <span className="sunmeta" style={{ fontSize: 13 }}>Goal:</span>
        <button type="button" className={`btn ${goal === 'now' ? 'primary' : ''}`} onClick={() => setGoal('now')}>“bright immediately”</button>
        <button type="button" className={`btn ${goal === 'later' ? 'primary' : ''}`} onClick={() => setGoal('later')}>“bright within 5 minutes”</button>
      </div>
      {goal && (
        <div className="grid2">
          <div className={`pane ${goal === 'now' ? '' : ''}`} style={{ borderColor: goal === 'now' ? 'var(--good)' : undefined }}>
            <div className="phead"><span className="who"><LampIcon on size={18} /> Lamp</span>
              {goal === 'now' && <span className="chip ok">chosen</span>}</div>
            <p className="note" style={{ marginTop: 0 }}>Delay in this example: 5 s (1 tick), energy 1 per tick.
              Meets the deadline instantly{goal === 'later' ? ', but burns energy the blind would save' : ''}.</p>
          </div>
          <div className="pane" style={{ borderColor: goal === 'later' ? 'var(--good)' : undefined }}>
            <div className="phead"><span className="who"><BlindIcon on size={18} /> Blind</span>
              {goal === 'later' && <span className="chip ok">chosen</span>}</div>
            <p className="note" style={{ marginTop: 0 }}>Delay in this example: 60 s (12 ticks), energy 0.
              {goal === 'now' ? ' Too slow for “immediately” — correctly rejected.' : ' 60 s < 5 min, so the free daylight harvest wins.'}</p>
          </div>
        </div>
      )}
      {!goal && <p className="note">Pick a goal. This toy planner takes the delay of each device and chooses the cheapest device that still meets the deadline.</p>}
    </div>
  );
}

function DelayResults() {
  const { data } = useData('phase3.json');
  if (!data) return <p className="loading">loading results…</p>;
  const truth = Number(data.delay[0].ground_truth_ticks);
  const groups = data.delay.map((r) => ({
    name: `${r.profile} · ${r.mode === 'ql_true' ? 'knowledge arm' : 'plain arm'}`,
    note: `slowest ${r.slowest_label.replace('Set', '').replace('=ON', '')} · error ${fmt(+r.abs_err_ticks, 2)} ticks`,
    bars: [
      { label: 'learned blind delay', value: +r.slowest_learned_ticks, color: 'var(--kg)' },
      { label: 'learned lamp/spotlight delay', value: +r.mean_instant_ticks, color: 'var(--ctx)' },
    ],
  }));
  const comp = (profile, mode) => data.compliance.find((r) => r.profile === profile && r.mode === mode);
  const maxErr = Math.max(...data.delay.map((r) => +r.abs_err_ticks));
  const instant = data.delay.map((r) => +r.mean_instant_ticks);
  const first = data.compliance[0];
  const looseAll = data.compliance.every((r) => +r.loose_compliance_mean === 1);
  const pct = (v) => `${Math.round(+v * 100)}%`;
  return (
    <>
      <SourceTag kind="results">learned delays and deadline compliance, generated from the stored results</SourceTag>
      <HBarChart groups={groups} unit="ticks" refValue={truth} refLabel={`true delay: ${fmt(truth, 0)} ticks`}
        fmt={(v) => fmt(v, 1)} xMax={Math.ceil(truth * 1.25)} />
      <p className="note">The dynamics agent probes each device, measures the ticks between switching and the observed
        effect, and writes it as <code>ws:responseDelay</code> to a separate Turtle file, which no program reads back. The slowest device is recovered with an error of
        at most {fmt(maxErr, 2)} ticks; lamps and the spotlight come out at {fmt(Math.min(...instant), 2)} to
        {' '}{fmt(Math.max(...instant), 2)} ticks on average.</p>

      <h3>Deadline compliance ({first.n_goals_total} timed goals per replica, {first.n_replicas} replicas per cell)</h3>
      <div className="tiles">
        {['lab2_slow', 'lab3_slow'].map((p) => {
          const t = comp(p, 'ql_true'); const f = comp(p, 'ql_false');
          if (!t || !f) return null;
          return (
            <React.Fragment key={p}>
              <Tile label={`${p}: tight deadlines (“immediately”), knowledge arm`}
                value={pct(t.tight_compliance_mean)}
                delta={`plain arm: ${pct(f.tight_compliance_mean)}`}
                deltaDir="up" />
              <Tile label={`${p}: mean realised delay on timed goals, knowledge arm`}
                value={`${fmt(+t.mean_actual_delay_mean, 0)} s`}
                delta={`plain arm ${fmt(+f.mean_actual_delay_mean, 0)} s`}
                deltaDir="up" />
            </React.Fragment>
          );
        })}
      </div>
      <p className="note">{looseAll ? 'Loose deadlines (“within 5 min”) are met in every run by both arms, so the difference is in the tight goals. ' : ''}
        The planner is deterministic and the replicas sample measurement jitter, so these are descriptive numbers
        and no significance test is reported (the registration of this phase rules tests out). Energy is the
        deterministic tick-integrated meter ({data.energy_meter}).</p>
    </>
  );
}

function PhaseHeader() {
  const { data } = useData('phase3.json');
  if (!data) return null;
  const n = data.compliance[0].n_replicas;
  return (
    <div className="q"><b>What the results below are:</b> protocol <code>{data.protocol_version}</code>, run {data.run},
      {' '}{n} replicas per cell, tick-integrated energy ({data.energy_meter}). Earlier Phase-3 runs had a protocol
      defect and are withdrawn; see <code>docs/notices/PHASE3_PROTOCOL_AFFECTED_NOTICE_2026-07-22.md</code>.</div>
  );
}

export default function Phase3() {
  const [slowId, setSlowId] = useState('lab2_slow');
  const cfg = SLOW[slowId];
  const lab = LABS[cfg.base];
  return (
    <div className="page">
      <h1>Phase 3 · Slow Labs</h1>
      <PhaseHeader />
      <div className="q"><b>Question:</b> the graph says <i>what</i> each device does but not <i>how fast</i>. Can the agent
        <b> measure</b> each device’s response delay, <b>write it down</b> (<code>ws:responseDelay</code> in a Turtle file) and
        <b> use it</b> to meet time-bounded goals?</div>

      <div className="toolbar" role="tablist">
        {Object.keys(SLOW).map((id) => (
          <button key={id} type="button" role="tab" aria-selected={slowId === id}
            className={`btn ${slowId === id ? 'primary' : ''}`} onClick={() => setSlowId(id)}>
            {id} <span style={{ opacity: .7 }}>(forks {SLOW[id].base})</span>
          </button>
        ))}
      </div>

      <div className="card">
        <div className="cardhead">
          <h3>Toy room: feel the delay</h3>
          <SourceTag kind="illustration">toggle a blind and watch the countdown; the animation speed is arbitrary</SourceTag>
        </div>
        <Sandbox lab={lab} slow={cfg} initialSun={900} />
      </div>

      <DeadlinePlanner />

      <h2>Result: learned delays and deadlines</h2>
      <div className="card">
        <Legend items={[{ color: 'var(--kg)', label: 'delayed actuators (blinds)' }, { color: 'var(--ctx)', label: 'instantaneous actuators (lamps, spotlight)' }]} />
        <DelayResults />
      </div>
    </div>
  );
}
