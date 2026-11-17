import React from 'react';
import { Legend } from '../components/charts.jsx';
import SourceTag from '../components/SourceTag.jsx';
import { useData, fmt } from '../lib/useData.js';

const MODES = [
  { color: 'var(--kg)', label: 'knowledge arm (ql_true): Q-learning primed with the knowledge graph' },
  { color: 'var(--tr)', label: 'plain arm (ql_false): the same Q-learning without the knowledge graph' },
  { color: 'var(--ctx)', label: 'hand-written controller (rule_based): a fixed reference, not learned' },
];

const q2 = (q) => Number(q).toPrecision(2);
const range = (lo, hi) => (lo === hi ? fmt(lo, 2) : `${fmt(lo, 2)} to ${fmt(hi, 2)}`);

// Each card: static question text, plus a result sentence built only from overview.json.
function phaseCards(o) {
  const p1 = o.phase1; const p2 = o.phase2; const p3 = o.phase3; const p4 = o.phase4;
  const labs = Object.entries(p4.steps_per_test_run);
  const reductions = labs.map(([, v]) => v.reduction_pct);
  const dl = p3.deadlines;
  const met = (arm) => Object.values(dl).map((d) => `${d[arm].met} of ${d[arm].total}`);
  return [
    {
      id: 'phase1', num: 'PHASE 1', title: 'Clean labs: does the knowledge help learning?',
      body: 'Three simulated labs of rising size. Does priming the Q-learner with the knowledge graph make it learn faster than the same learner starting from scratch?',
      result: (
        <>In lab2 the knowledge arm succeeded in {fmt(100 * p1.lab2_auc_goal_gain, 2)} percentage points more of
          its training episodes than the plain arm ({p1.n_seeds} paired seeds, q = {q2(p1.lab2_auc_goal_q)}).
          That is a small gain, and a simple rule that only discourages redundant moves reproduces
          {' '}{fmt(100 * p1.redundancy_share, 0)}% of it.</>
      ),
    },
    {
      id: 'phase2', num: 'PHASE 2', title: 'Faulty labs: does the agent notice a broken device?',
      body: 'One lamp or blind silently breaks. The agent should notice that reality differs from the knowledge graph, drop the broken device and re-learn with what is left.',
      result: (
        <>Of {p2.n_cells} registered fault cells, {p2.n_supported} favour the knowledge arm (faster recovery),
          {' '}{p2.n_null} show no clear difference and {p2.n_adverse} go against it. In
          {' '}{p2.adverse_cells_knowledge_arm_never_detected} of the {p2.n_adverse} adverse cells the knowledge arm
          never detected the fault in any of the {p2.n_seeds} paired runs.</>
      ),
    },
    {
      id: 'phase3', num: 'PHASE 3', title: 'Slow labs: can the agent learn how fast a device reacts?',
      body: 'Blinds need 60 seconds to move, which the knowledge graph does not say. The agent measures each device’s delay, writes it to a file and plans against deadlines with the measured values.',
      result: (
        <>The learned blind delay was {range(p3.learned_ticks_min, p3.learned_ticks_max)} ticks
          (true value {fmt(p3.ground_truth_ticks, 0)}). Deadlines met over the timed goals: knowledge arm
          {' '}{met('knowledge_arm').join(' and ')}, plain arm {met('plain_arm').join(' and ')}
          {' '}(lab2_slow and lab3_slow). Counts only: this phase is reported without test statistics.</>
      ),
    },
    {
      id: 'phase4', num: 'PHASE 4', title: 'Knowledge ladder: facts only the graph holds',
      body: 'A hidden smart-plug dependency and a per-lamp energy rating that trial and error cannot see directly.',
      result: (
        <>In the three smart-plug labs the knowledge arm needs {fmt(Math.min(...reductions), 0)} to
          {' '}{fmt(Math.max(...reductions), 0)}% fewer steps per test run
          (the tables call this measure “redundant actions”). In lab5 the share of test runs that reach the
          goal within the energy budget is {fmt(p4.lab5.knowledge_arm, 3)} for the knowledge arm,
          {' '}{fmt(p4.lab5.plain_arm, 3)} for the plain arm and {fmt(p4.lab5.hand_written, 2)} for the
          hand-written controller (corrected scoring).</>
      ),
    },
  ];
}

export default function Overview({ go }) {
  const { data: o, error } = useData('overview.json');
  return (
    <div className="page">
      <h1>KG-QL · Knowledge-Guided Reinforcement Learning for Building Automation</h1>
      <p className="lede">
        One BDI agent (JaCaMo) controls the lighting of simulated labs (Node-RED). The learner is plain tabular
        Q-learning. The one thing that changes between the two learning arms is a <b>knowledge graph</b> that describes
        what each device does (“lamps <i>cause</i> light; blinds <i>mediate</i> sunshine”) and is used to prime the learner.
        Four phases ask what that knowledge buys.
      </p>

      <div className="q">
        <b>The question behind all four phases:</b> does knowledge about normal physics help a learner in clean rooms,
        and what happens when a device breaks, reacts slowly, or hides a dependency? The cards below show what the
        stored results say, nothing more.
      </div>

      <h2>The three agents compared in every lab</h2>
      <SourceTag kind="illustration">a description of the set-up, no numbers</SourceTag>
      <Legend items={MODES} />
      <p className="note">Same Q-learner, same reward, same seeds. The only difference between the two learning arms is
        the knowledge graph, so each headline is a paired comparison of the knowledge arm with the plain arm in the same lab.</p>

      <h2>The four phases</h2>
      <SourceTag kind="results">every number below is read from overview.json, which is generated from the result tables</SourceTag>
      {error && <p className="warn">overview.json could not be loaded; run <code>py -3 dashboard/scripts/prepare_data.py</code>.</p>}
      {!o && !error && <p className="loading">loading results…</p>}
      {o && (
        <div className="phasecards">
          {phaseCards(o).map((p) => (
            <button type="button" key={p.id} className="phasecard" onClick={() => go(p.id)}>
              <span className="pnum">{p.num}</span>
              <h3>{p.title}</h3>
              <p>{p.body}</p>
              <div className="stat">{p.result}</div>
            </button>
          ))}
        </div>
      )}
      <p className="note">All results are from simulated labs; they do not show that the approach works in a real building.</p>

      <h2>The lab ladder</h2>
      <SourceTag kind="illustration">a diagram of the labs</SourceTag>
      <div className="ladder">
        <div className="rung"><b>lab1 · Trivial</b>1 zone, 1 lamp · 8 states<br />the smallest test</div>
        <span className="arrow">→</span>
        <div className="rung"><b>lab2 · Intermediate</b>2 zones · lamp + blind · 1,024 states<br />adds the blind that only helps when the sun is up</div>
        <span className="arrow">→</span>
        <div className="rung"><b>lab3 · Complex</b>+ light spilling between zones + a shared spotlight · 2,048 states</div>
        <span className="arrow">→</span>
        <div className="rung"><b>Phase 2 labs</b>lab1/2/3 with one lamp or blind dead or inverted</div>
        <span className="arrow">→</span>
        <div className="rung"><b>Phase 3 labs</b>lab2/3_slow: blinds respond after 60 s</div>
        <span className="arrow">→</span>
        <div className="rung"><b>Phase 4 labs</b>lab4 smart-plug AND-gate · 4,096 states<br />lab5 efficient and inefficient lamps · 8,192 states</div>
      </div>
      <p className="note">
        Every lab is a lighting problem: bring each zone to its target brightness rank (0 dark, 1 dim, 2 medium,
        3 bright; bounds 50, 100 and 300 lux) within a 20-step episode. The sunshine is fixed per episode at one of
        {' '}{'{0, 100, 400, 900}'} lux. The phase pages also hold interactive toy rooms (click the devices), which are
        illustrations, and the tables from the stored results.
      </p>

      <h2>How a decision flows</h2>
      <SourceTag kind="illustration">a diagram of the software</SourceTag>
      <div className="ladder">
        <div className="rung"><b>Node-RED simulator</b>the room physics of each lab</div>
        <span className="arrow">→</span>
        <div className="rung"><b>CArtAgO artifacts</b>WoT Thing Description actions · SPARQL over the graph</div>
        <span className="arrow">→</span>
        <div className="rung"><b>Jason BDI agent</b>tabular Q-learning; the graph primes its initial action preferences</div>
        <span className="arrow">→</span>
        <div className="rung"><b>Knowledge graph</b>Brick building topology + device stereotypes (what each device does, power gates, energy cost)</div>
      </div>
    </div>
  );
}
