import React, { useEffect, useState } from 'react';
import Overview from './pages/Overview.jsx';
import Phase1 from './pages/Phase1.jsx';
import Phase2 from './pages/Phase2.jsx';
import Phase3 from './pages/Phase3.jsx';
import Phase4 from './pages/Phase4.jsx';
import SourceTag from './components/SourceTag.jsx';

const PAGES = [
  { id: 'overview', label: 'Overview', sub: 'the question and the labs', el: Overview },
  { id: 'phase1', label: 'Phase 1 · Clean', sub: 'does knowledge help learning?', el: Phase1 },
  { id: 'phase2', label: 'Phase 2 · Faults', sub: 'broken devices', el: Phase2 },
  { id: 'phase3', label: 'Phase 3 · Dynamics', sub: 'slow devices', el: Phase3 },
  { id: 'phase4', label: 'Phase 4 · Knowledge', sub: 'hidden plug gate, energy', el: Phase4 },
];

function currentPage() {
  const h = window.location.hash.replace(/^#\/?/, '');
  return PAGES.some((p) => p.id === h) ? h : 'overview';
}

export default function App() {
  const [page, setPage] = useState(currentPage);

  useEffect(() => {
    const onHash = () => { setPage(currentPage()); window.scrollTo(0, 0); };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const go = (id) => { window.location.hash = `/${id}`; };
  const Active = PAGES.find((p) => p.id === page).el;

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          KG-QL Demo
          <small>Knowledge-guided Q-learning for lighting control in simulated labs</small>
        </div>
        <nav aria-label="phases">
          {PAGES.map((p) => (
            <button key={p.id} type="button"
              className={`navlink ${page === p.id ? 'active' : ''}`}
              onClick={() => go(p.id)}>
              {p.label}
              <span className="sub">{p.sub}</span>
            </button>
          ))}
        </nav>
        <div className="foot">
          Panels marked “From the stored results” are generated from the result tables;
          panels marked “Illustration” are not results.
        </div>
      </aside>
      <main className="main">
        <Active go={go} />
        <div className="aboutbox" role="note">
          <b>About the two labels.</b>{' '}
          <SourceTag kind="results" /> means the numbers were generated from the result tables stored under <code>results/</code> by
          the data script. <SourceTag kind="illustration" /> means a toy room, a replay of one early-prototype run, a walk-through,
          a diagram or a planner with its own numbers: it helps you see how something works and shows no result.
        </div>
      </main>
    </div>
  );
}
