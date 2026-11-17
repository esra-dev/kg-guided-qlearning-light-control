import React from 'react';

// Every panel carries one of two labels:
//   kind="results"      -> "From the stored results"  (generated from tables under results/)
//   kind="illustration" -> "Illustration"             (toy rooms, replays of single early runs,
//                                                       walk-throughs, diagrams, planner with its own numbers)
const LABELS = {
  results: 'From the stored results',
  illustration: 'Illustration',
};

export default function SourceTag({ kind, children }) {
  const label = LABELS[kind];
  return (
    <span className="srcwrap">
      <span className={`srctag ${kind}`} title={kind === 'results'
        ? 'Generated from the stored result tables under results/.'
        : 'Not a result: a toy model, a single early run, or a diagram.'}>{label}</span>
      {children ? <span className="srcnote">{children}</span> : null}
    </span>
  );
}
