"""Guards analysis/posthoc/_common.py, the helpers shared by the post-hoc scripts.

Covers seed-list parsing, the paired bootstrap summary (NaN seeds excluded, no
p- or q-values emitted), scenario state keys, the start-meets-goal test and
number formatting. Synthetic inputs only.
"""

import math

import pytest

from analysis.posthoc import _common as cm


def test_parse_seeds_ranges_and_lists():
    assert cm.parse_seeds("1-3") == (1, 2, 3)
    assert cm.parse_seeds("5,1-2, 2") == (1, 2, 5)
    with pytest.raises(ValueError):
        cm.parse_seeds("")


def test_paired_summary_signs_mean_and_nan_exclusion():
    left = {1: 3.0, 2: 1.0, 3: 2.0, 4: float("nan")}
    right = {1: 1.0, 2: 1.0, 3: 4.0, 4: 0.0}
    s = cm.paired_summary(left, right, (1, 2, 3, 4), "test", iters=2000)
    assert s["n_paired"] == 3
    assert s["seeds_excluded_undefined"] == "4"
    assert s["mean_paired_difference"] == pytest.approx(0.0)
    assert (s["n_seeds_left_higher"], s["n_seeds_tied"], s["n_seeds_left_lower"]) == (1, 1, 1)
    assert s["bootstrap_ci_lo_95"] <= s["mean_paired_difference"] <= s["bootstrap_ci_hi_95"]
    assert not any(k.startswith(("p_", "q_")) for k in s)  # no p- or q-values


def test_bootstrap_is_reproducible_and_label_specific():
    diffs = [0.1, -0.2, 0.3, 0.5, -0.1]
    a = cm.bootstrap_mean_interval(diffs, "x", iters=500)
    b = cm.bootstrap_mean_interval(diffs, "x", iters=500)
    c = cm.bootstrap_mean_interval(diffs, "y", iters=500)
    assert a == b
    assert a != c
    assert all(math.isnan(v) for v in cm.bootstrap_mean_interval([1.0], "z"))


def test_paired_summary_missing_seed_is_an_error():
    with pytest.raises(ValueError):
        cm.paired_summary({1: 1.0}, {2: 1.0}, (1,), "t", iters=10)


def test_state_key_ignores_id_comment_and_text():
    a = {"id": 1, "comment": "x", "description": "a", "Z1Light": True, "Sunshine": 0}
    b = {"id": 9, "description": "other", "Z1Light": True, "Sunshine": 0}
    assert cm.state_key(a) == cm.state_key(b)


def test_start_meets_goal_uses_all_target_zones():
    assert cm.start_meets_goal("lab1", {"Z1Level": 425})
    assert not cm.start_meets_goal("lab2", {"Z1Level": 425, "Z2Level": 299.9})
    assert cm.start_meets_goal("lab2", {"Z1Level": 300, "Z2Level": 475})


def test_num_drops_negative_zero():
    assert cm.num(-1e-17) == "0.0000"
    assert cm.num(-0.5, 1) == "-0.5"
