import React, { useEffect, useMemo, useRef, useState } from 'react';
import LabView from './LabView.jsx';
import { useData } from '../lib/useData.js';
import SourceTag from './SourceTag.jsx';
import { offState, RANK_NAMES } from '../lib/physics.js';

const MODE_META = {
  ql_true: { name: 'Knowledge arm', short: 'ql_true', color: 'var(--kg)' },
  ql_false: { name: 'Plain arm', short: 'ql_false', color: 'var(--tr)' },
  rule_based: { name: 'Hand-written controller', short: 'rule_based', color: 'var(--ctx)' },
};

function initActuators(lab, init) {
  const s = offState(lab);
  for (const k of Object.keys(s)) if (k in init) s[k] = !!init[k];
  return s;
}

function paneStateAt(lab, scen, mode, idx) {
  const steps = scen.modes[mode]?.steps || [];
  const i = Math.min(idx, steps.length);
  if (i === 0) return initActuators(lab, scen.init);
  return { ...offState(lab), ...steps[i - 1].act };
}

function captionAt(scen, mode, idx) {
  const m = scen.modes[mode];
  if (!m) return { text: 'no data for this mode', cls: '' };
  const steps = m.steps;
  if (idx === 0) {
    return steps.length === 0 && m.outcome?.goal
      ? { text: 'Already at target — the trained agent correctly does nothing.', cls: 'goal' }
      : { text: 'Initial state (before the first action).', cls: '' };
  }
  if (idx > steps.length) {
    return m.outcome?.goal
      ? { text: `Episode over — goal reached after ${steps.length} action${steps.length === 1 ? '' : 's'}.`, cls: 'goal' }
      : { text: `Episode over — goal NOT reached within ${steps.length} steps.`, cls: 'diff' };
  }
  const st = steps[idx - 1];
  const deltas = [];
  st.before.forEach((b, zi) => {
    const a = st.after[zi];
    if (b != null && a != null && a !== b) {
      deltas.push(`Z${zi + 1} ${RANK_NAMES[b]}→${RANK_NAMES[a]}`);
    }
  });
  const effect = deltas.length ? deltas.join(', ') : 'no rank change';
  return { text: null, action: st.action.text, effect, cls: '' };
}

export default function Replay({ lab, modes = ['ql_true', 'ql_false'], defaultScenario = null, title }) {
  const { data, error } = useData(`replay_${lab.id}.json`);
  const [sid, setSid] = useState(defaultScenario);
  const [idx, setIdx] = useState(0);
  const [playing, setPlaying] = useState(false);
  const playRef = useRef(null);

  const scenIds = useMemo(
    () => (data ? Object.keys(data.scenarios).sort((a, b) => +a - +b) : []),
    [data],
  );
  const cur = data && (sid || scenIds[0]);
  const scen = data && cur ? data.scenarios[cur] : null;

  const maxSteps = useMemo(() => {
    if (!scen) return 0;
    return Math.max(...modes.map((m) => (scen.modes[m]?.steps || []).length), 0);
  }, [scen, modes]);

  // pick the most interesting scenario by default: the one where outcomes or
  // step counts differ the most between the two arms
  useEffect(() => {
    if (!data || sid) return;
    let best = scenIds[0]; let bestScore = -1;
    for (const id of scenIds) {
      const s = data.scenarios[id];
      const a = s.modes[modes[0]]; const b = s.modes[modes[1]];
      if (!a || !b) continue;
      const ga = a.outcome?.goal ? 1 : 0; const gb = b.outcome?.goal ? 1 : 0;
      const score = Math.abs(ga - gb) * 100
        + Math.abs((a.steps?.length || 0) - (b.steps?.length || 0)) * 3
        + (a.steps?.length || 0) * 0.1;
      if (score > bestScore) { bestScore = score; best = id; }
    }
    setSid(best);
  }, [data, scenIds, modes, sid]);

  // switching labs invalidates the selected scenario id
  useEffect(() => { setSid(defaultScenario); }, [lab.id]);
  useEffect(() => { setIdx(0); setPlaying(false); }, [cur, lab.id]);

  useEffect(() => {
    if (!playing) return undefined;
    playRef.current = setInterval(() => {
      setIdx((i) => {
        if (i >= maxSteps + 1) { setPlaying(false); return i; }
        return i + 1;
      });
    }, 1000);
    return () => clearInterval(playRef.current);
  }, [playing, maxSteps]);

  if (error) return <p className="note">Replay data missing ({String(error.message)}). The replay files are frozen illustration data and are not rebuilt by the data script; restore them from version control.</p>;
  if (!data || !scen) return <p className="loading">loading replay…</p>;

  const sun = Number(scen.init.Sunshine ?? 0);
  const diffAt = (i) => {
    if (i === 0) return false;
    const a = scen.modes[modes[0]]?.steps[i - 1]?.action.text;
    const b = scen.modes[modes[1]]?.steps[i - 1]?.action.text;
    return a != null && b != null && a !== b;
  };
  const firstDiff = (() => {
    for (let i = 1; i <= maxSteps; i += 1) if (diffAt(i)) return i;
    return null;
  })();

  return (
    <div className="replay">
      <div className="cardhead" style={{ marginBottom: 8 }}>
        <h3 style={{ margin: 0 }}>{title || 'Replay of one early-prototype run'}</h3>
        <SourceTag kind="illustration">one run of an early prototype (recorded 2026-06-10, before the correction); not a result, not a statistic</SourceTag>
      </div>

      <div className="toolbar">
        <select className="btn" value={cur} onChange={(e) => setSid(e.target.value)} aria-label="scenario">
          {scenIds.map((id) => {
            const s = data.scenarios[id];
            const sunTxt = `sun ${s.init.Sunshine ?? '?'}`;
            return <option key={id} value={id}>#{id} · {sunTxt} · {s.desc || 'scenario'}</option>;
          })}
        </select>
      </div>

      <div className="panes">
        {modes.map((m) => {
          const meta = MODE_META[m];
          const out = scen.modes[m]?.outcome;
          const cap = captionAt(scen, m, idx);
          const ended = idx > (scen.modes[m]?.steps || []).length;
          return (
            <div className="pane" key={m}>
              <div className="phead">
                <span className="who"><span className="dot" style={{ background: meta.color }} />{meta.name}</span>
                {out && (
                  <span style={{ display: 'flex', gap: 6 }}>
                    <span className={`chip ${out.goal ? 'ok' : 'bad'}`}>{out.goal ? `goal in ${out.steps} steps` : 'goal missed'}</span>
                    {out.energy != null && <span className="chip">energy {Math.round(out.energy)}</span>}
                  </span>
                )}
              </div>
              <LabView lab={lab} state={paneStateAt(lab, scen, m, idx)} sun={sun}
                power={lab.power ? lab.power(paneStateAt(lab, scen, m, idx)) : null}
                budget={lab.energyBudget ?? null} compact={false} />
              <div className={`actionline ${cap.cls} ${!ended && diffAt(idx) ? 'diff' : ''}`}>
                {cap.text != null
                  ? cap.text
                  : <>step {idx}: <b>{cap.action}</b> → {cap.effect}{diffAt(idx) ? ' · agents diverge here' : ''}</>}
              </div>
            </div>
          );
        })}
      </div>

      <div className="stepper">
        <button type="button" className="btn" onClick={() => setIdx(Math.max(0, idx - 1))} disabled={idx === 0}>‹ prev</button>
        <button type="button" className="btn primary" onClick={() => setPlaying(!playing)} disabled={maxSteps === 0}>
          {playing ? 'pause' : 'play ▸'}
        </button>
        <button type="button" className="btn" onClick={() => setIdx(Math.min(maxSteps + 1, idx + 1))} disabled={idx >= maxSteps + 1}>next ›</button>
        <input type="range" min={0} max={Math.max(maxSteps + 1, 1)} value={idx}
          onChange={(e) => setIdx(+e.target.value)} aria-label="step" />
        <span className="stepnum">step {idx} / {maxSteps + 1}</span>
      </div>

      <div className="trackrow" aria-hidden="true">
        {Array.from({ length: maxSteps + 2 }, (_, i) => {
          const goalA = i > 0 && i === (scen.modes[modes[0]]?.steps || []).length + 1 && scen.modes[modes[0]]?.outcome?.goal;
          const goalB = i > 0 && i === (scen.modes[modes[1]]?.steps || []).length + 1 && scen.modes[modes[1]]?.outcome?.goal;
          return (
            <button key={i} type="button"
              className={`t ${i === idx ? 'cur' : ''} ${diffAt(i) ? 'diff' : ''} ${goalA || goalB ? 'goalstep' : ''}`}
              onClick={() => setIdx(i)}>{i}</button>
          );
        })}
      </div>
      <p className="note">
        Amber steps = the two agents chose <b>different actions</b>{firstDiff ? ` (first divergence at step ${firstDiff})` : ''};
        green = an agent's episode ends with the goal reached. Zone lux is reconstructed from the logged actuator state
        with the toy-room light formulas. This is a single run, so it shows what one agent did, not how often.
      </p>
    </div>
  );
}
