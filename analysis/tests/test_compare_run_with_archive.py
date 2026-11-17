"""Guards analysis/compare_run_with_archive.py with small synthetic run and archive folders.

Covered: an identical file, a changed data value (must fail), a differing wall-clock
column only, a receipt with added keys and a new timestamp, a receipt with a changed
shared value (must fail), a receipt with a key only in the archive (must fail), a file
without an archive counterpart, a CRLF / LF pair, the Phase-2 per-lab archive mapping,
repository-relative output paths, the exit code, and the Phase-3 count comparison.
"""
import csv
import json

from analysis import compare_run_with_archive as cra

CELL = "train-lab2-stereo-false-seed-1"
ARCH = "results/camp/run_1"
METRICS = "metrics_stereotypes_false_lab2.csv"


def _write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8") if isinstance(text, str) else text)


def _arch_file(root, name, cell=CELL):
    """Path of an archive counterpart in the layout results/<campaign>/run_<id>/_artifacts/train/<cell>/."""
    return root / ARCH / "_artifacts" / "train" / cell / name


def _run(tmp_path, files, archive_files, extra_archives=(), phase="phase1", run_cell=CELL):
    run_dir = tmp_path / "run"
    for name, text in files.items():
        _write(run_dir / run_cell / name, text)
    for name, text in archive_files.items():
        _write(_arch_file(tmp_path / "root", name), text)
    out = tmp_path / "out"
    argv = ["--run-dir", str(run_dir), "--phase", phase, "--archive", str(tmp_path / "root" / ARCH),
            "--archive-root", str(tmp_path / "root"), "--out", str(out)]
    for a in extra_archives:
        argv += ["--archive", a]
    code = cra.main(argv)
    rows = list(csv.DictReader(open(out / "compare.csv", newline="", encoding="utf-8")))
    return code, {r["file"]: r for r in rows}, out


CSV_A = "step,value\n1,10\n2,20\n"


def test_identical_file_passes(tmp_path):
    code, rows, _ = _run(tmp_path, {METRICS: CSV_A}, {METRICS: CSV_A})
    assert code == 0
    assert rows[METRICS]["status"] == cra.IDENTICAL


def test_changed_data_value_fails(tmp_path):
    code, rows, _ = _run(tmp_path, {METRICS: "step,value\n1,10\n2,21\n"}, {METRICS: CSV_A})
    assert code == 1
    assert rows[METRICS]["status"] == cra.DATA
    assert "value" in rows[METRICS]["detail"]


def test_only_wall_clock_column_differs_passes(tmp_path):
    name = "benchmark_results_ql_false.csv"
    run = "run,LegacyWallClockTotalEnergyCost,Goal\n1,8.0,1\n2,5.0,1\n"
    arch = "run,LegacyWallClockTotalEnergyCost,Goal\n1,9.0,1\n2,5.0,1\n"
    code, rows, _ = _run(tmp_path, {name: run}, {name: arch})
    assert code == 0
    assert rows[name]["status"] == cra.EXPECTED


def test_wall_clock_column_plus_other_column_fails(tmp_path):
    name = "benchmark_results_ql_false.csv"
    run = "run,LegacyWallClockTotalEnergyCost,Goal\n1,8.0,1\n"
    arch = "run,LegacyWallClockTotalEnergyCost,Goal\n1,9.0,0\n"
    code, rows, _ = _run(tmp_path, {name: run}, {name: arch})
    assert code == 1
    assert rows[name]["status"] == cra.DATA


def test_receipt_with_added_keys_and_new_timestamp_passes(tmp_path):
    arch = {"run_mode": "x", "timestamp": "2026-07-21T18:27:30Z", "episodes": 3}
    run = {"run_mode": "x", "timestamp": "2026-11-16T08:35:39Z", "episodes": 3,
           "stereo_requested_props": {"redundancyOnly": False}, "new_key": 1}
    code, rows, _ = _run(tmp_path, {"TRAINING_OK.json": json.dumps(run)}, {"TRAINING_OK.json": json.dumps(arch)})
    assert code == 0
    r = rows["TRAINING_OK.json"]
    assert r["status"] == cra.ADDED_KEYS
    assert "same values in all 2 shared keys" in r["detail"]
    assert "2 added keys" in r["detail"]


def test_receipt_timestamp_only_is_expected_field(tmp_path):
    arch = {"run_mode": "x", "timestamp": "a"}
    run = {"run_mode": "x", "timestamp": "b"}
    code, rows, _ = _run(tmp_path, {"TRAINING_OK.json": json.dumps(run)}, {"TRAINING_OK.json": json.dumps(arch)})
    assert code == 0
    assert rows["TRAINING_OK.json"]["status"] == cra.EXPECTED


def test_receipt_with_changed_shared_value_fails(tmp_path):
    arch = {"run_mode": "x", "episodes": 3}
    run = {"run_mode": "x", "episodes": 4, "extra": 1}
    code, rows, _ = _run(tmp_path, {"TRAINING_OK.json": json.dumps(run)}, {"TRAINING_OK.json": json.dumps(arch)})
    assert code == 1
    assert rows["TRAINING_OK.json"]["status"] == cra.DATA
    assert "episodes" in rows["TRAINING_OK.json"]["detail"]


def test_receipt_with_key_only_in_archive_fails(tmp_path):
    arch = {"run_mode": "x", "old_key": 1}
    run = {"run_mode": "x"}
    code, rows, _ = _run(tmp_path, {"TRAINING_OK.json": json.dumps(run)}, {"TRAINING_OK.json": json.dumps(arch)})
    assert code == 1
    assert rows["TRAINING_OK.json"]["status"] == cra.DATA


def test_file_without_counterpart_is_reported_but_passes(tmp_path):
    code, rows, _ = _run(tmp_path, {METRICS: CSV_A, "qtable_initial_x.csv": "a,b\n1,2\n"}, {METRICS: CSV_A})
    assert code == 0
    assert rows["qtable_initial_x.csv"]["status"] == cra.MISSING_ARCHIVE


def test_archive_file_the_run_did_not_produce_fails(tmp_path):
    other = "metrics_stereotypes_false_lab2_extra.csv"
    code, rows, _ = _run(tmp_path, {METRICS: CSV_A}, {METRICS: CSV_A, other: CSV_A})
    assert code == 1
    assert rows[other]["status"] == cra.MISSING_RUN


def test_crlf_versus_lf_is_not_a_difference(tmp_path):
    code, rows, out = _run(tmp_path, {METRICS: CSV_A.replace("\n", "\r\n")}, {METRICS: CSV_A})
    assert code == 0
    assert rows[METRICS]["status"] == cra.LINE_ENDINGS
    assert "CRLF" in (out / "summary.md").read_text(encoding="utf-8")


TTL = "learned_stereotypes_false_lab2.ttl"
TTL_OLD = ("@prefix learned: <http://example.org/was/learned#> .\n"
           "@prefix elem:    <http://w3id.org/elementary#> .\n\n"
           "[] a learned:DiscoveredEffect ;\n   learned:slotIndex 0 .\n")
TTL_NEW = TTL_OLD.replace("http://w3id.org/elementary#", "http://www.w3id.org/elementary#")


def test_elem_prefix_address_only_is_expected_field(tmp_path):
    code, rows, _ = _run(tmp_path, {TTL: TTL_NEW}, {TTL: TTL_OLD})
    assert code == 0
    assert rows[TTL]["status"] == cra.EXPECTED
    assert "prefix" in rows[TTL]["detail"]


def test_elem_prefix_address_in_a_file_that_uses_the_prefix_fails(tmp_path):
    used = "[] elem:hasQuantity learned:x .\n"
    code, rows, _ = _run(tmp_path, {TTL: TTL_NEW + used}, {TTL: TTL_OLD + used})
    assert code == 1
    assert rows[TTL]["status"] == cra.DATA


def test_elem_prefix_address_plus_another_changed_line_fails(tmp_path):
    code, rows, _ = _run(tmp_path, {TTL: TTL_NEW.replace("slotIndex 0", "slotIndex 1")}, {TTL: TTL_OLD})
    assert code == 1
    assert rows[TTL]["status"] == cra.DATA


def test_crlf_does_not_hide_a_data_change(tmp_path):
    code, rows, _ = _run(tmp_path, {METRICS: "step,value\r\n1,10\r\n2,99\r\n"}, {METRICS: CSV_A})
    assert code == 1
    assert rows[METRICS]["status"] == cra.DATA


def test_outputs_have_repository_relative_paths_only(tmp_path):
    code, rows, out = _run(tmp_path, {METRICS: CSV_A}, {METRICS: CSV_A})
    assert rows[METRICS]["archive_path"] == f"{ARCH}/_artifacts/train/{CELL}/{METRICS}"
    for f in ("compare.csv", "summary.md"):
        text = (out / f).read_text(encoding="utf-8")
        assert str(tmp_path) not in text and str(tmp_path).replace("\\", "/") not in text
        assert "\\" not in text


def test_nothing_compared_fails(tmp_path):
    (tmp_path / "run").mkdir()
    (tmp_path / "root" / ARCH).mkdir(parents=True)
    code = cra.main(["--run-dir", str(tmp_path / "run"), "--phase", "phase1", "--archive",
                     str(tmp_path / "root" / ARCH), "--archive-root", str(tmp_path / "root"),
                     "--out", str(tmp_path / "out")])
    assert code == 1


def test_phase2_per_lab_archive_mapping(tmp_path):
    root = tmp_path / "root"
    name = "metrics_adapted_stereotypes_false_lab3_f2dead_lowsun.csv"
    cell = "adapt-lab3_f2dead_lowsun-ql_false-seed-1"
    # two archive runs hold a file of this name with different content; only the pinned one matches
    _write(root / "results/camp/run_a/recovery_root/seed1" / name, "x,y\n1,2\n")
    _write(root / "results/camp/run_b/recovery_root/seed1" / name, "x,y\n1,3\n")
    run_dir = tmp_path / "run"
    _write(run_dir / cell / name, "x,y\n1,3\n")
    base = ["--run-dir", str(run_dir), "--phase", "phase2", "--archive-root", str(root), "--out", str(tmp_path / "out")]
    unpinned = base + ["--archive", str(root / "results/camp/run_a")]
    assert cra.main(unpinned) == 1
    pinned = base + ["--archive", str(root / "results/camp/run_a"),
                     "--archive", f"lab3_f2dead_lowsun={root / 'results/camp/run_b'}"]
    assert cra.main(pinned) == 0
    rows = list(csv.DictReader(open(tmp_path / "out" / "compare.csv", newline="", encoding="utf-8")))
    assert rows[0]["archive_path"] == f"results/camp/run_b/recovery_root/seed1/{name}"


# ------------------------------------------------------------------ Phase 3
TB_HEADER = "goal_id,deadline_sec,chosen_label,learned_delay_sec,met,actual_delay_sec\n"
DL_HEADER = "action_label,delay_seconds,response_class\n"


def _phase3(tmp_path, run_rows, arch_reps):
    """arch_reps: {replica number: rows}; the run is replica 1 of lab2_slow ql_true."""
    root = tmp_path / "root"
    tb = "timebounded_results_true_lab2_slow.csv"
    for rep, rows in arch_reps.items():
        _write(root / f"results/p3/run_1/dynamics_root/rep{rep}" / tb, TB_HEADER + rows)
        _write(root / f"results/p3/run_1/dynamics_root/rep{rep}" / "dynamics_delays_true_lab2_slow.csv",
               DL_HEADER + "SetZ1Light=ON,5.0,short\n")
    cell = tmp_path / "run" / "dynamics-lab2_slow-ql_true-rep-1"
    _write(cell / tb, TB_HEADER + run_rows)
    _write(cell / "dynamics_delays_true_lab2_slow.csv", DL_HEADER + "SetZ1Light=ON,5.5,short\n")
    code = cra.main(["--run-dir", str(tmp_path / "run"), "--phase", "phase3", "--archive",
                     str(root / "results/p3/run_1"), "--archive-root", str(root), "--out", str(tmp_path / "out")])
    return code, (tmp_path / "out" / "summary.md").read_text(encoding="utf-8")


def test_phase3_same_flags_and_choices_pass(tmp_path):
    rows = "g1,60,SetZ1Blinds=ON,60.0,1,12.0\ng2,60,SetZ1Light=ON,5.0,0,1.0\n"
    arch = "g1,60,SetZ1Blinds=ON,60.6,1,13.0\ng2,60,SetZ1Light=ON,5.5,0,1.5\n"
    code, summary = _phase3(tmp_path, rows, {1: arch})
    assert code == 0
    assert "deadline flag (`met`) identical: 2 of 2" in summary
    assert "identical to the archive replica of the same number: 2 of 2" in summary
    assert (tmp_path / "out" / "phase3_compare.csv").is_file()


def test_phase3_other_choice_within_archive_spread_passes(tmp_path):
    run = "g1,60,SetZ2Blinds=ON,60.0,1,12.0\n"
    rep1 = "g1,60,SetZ1Blinds=ON,60.0,1,12.0\n"
    rep2 = "g1,60,SetZ2Blinds=ON,60.0,1,12.0\n"
    code, summary = _phase3(tmp_path, run, {1: rep1, 2: rep2})
    assert code == 0
    assert "identical to the archive replica of the same number: 0 of 1" in summary
    assert "chosen by the archive in another replica of the same goal: 1" in summary


def test_phase3_choice_outside_archive_spread_fails(tmp_path):
    code, _ = _phase3(tmp_path, "g1,60,SetZ2Blinds=ON,60.0,1,12.0\n", {1: "g1,60,SetZ1Blinds=ON,60.0,1,12.0\n"})
    assert code == 1


def test_phase3_deadline_flag_difference_fails(tmp_path):
    code, _ = _phase3(tmp_path, "g1,60,SetZ1Blinds=ON,60.0,0,12.0\n", {1: "g1,60,SetZ1Blinds=ON,60.0,1,12.0\n"})
    assert code == 1
