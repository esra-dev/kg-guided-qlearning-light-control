# Corrected tables

Re-computations of archived result tables whose archived version is known to
be wrong in a documented way. They are written from the archives by scripts in
`analysis/`; they are not archives themselves, so they have no pins file and
`analysis/check_pins.py` skips this folder in its layout check. The archived
tables stay byte-for-byte unchanged and are still rebuilt by their reproducers.

Each subfolder's `README.md` says what is corrected, why, and which command
writes it:

- `phase3_v2_corrected/`: Phase-3 paired table without the p- and q-values the
  Phase-3 registration forbids.
- `phase4_v2_corrected/`: Phase-4 registered family with lab5 test runs that
  need no step scored from their declared start state (corrected compliance
  levels; paired differences unchanged).
- `phase4_v2_ladder/`: the Phase-4 knowledge-ladder tables with the same lab5
  correction.
