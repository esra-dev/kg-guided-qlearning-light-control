from pathlib import Path

import pytest

from analysis import reproduce_phase1_v2 as reproduce


def _write(path: Path, value: str) -> None:
    path.write_text(f"metric,value\nauc_goal,{value}\n", encoding="utf-8")


def test_canonical_csv_ignores_cross_platform_float_repr_noise(tmp_path):
    expected = tmp_path / "expected.csv"
    actual = tmp_path / "actual.csv"
    _write(expected, "4.419999999999999")
    _write(actual, "4.42")
    reproduce._compare(expected, actual)


def test_canonical_csv_rejects_scientifically_different_number(tmp_path):
    expected = tmp_path / "expected.csv"
    actual = tmp_path / "actual.csv"
    _write(expected, "0.0037")
    _write(actual, "0.0038")
    with pytest.raises(ValueError, match="canonical byte comparison failed"):
        reproduce._compare(expected, actual)


# Archived JSON and Markdown outputs were written on Windows (CRLF); a rebuild
# on Linux writes LF. The comparison must not depend on line endings, but must
# still catch a single differing number.
JSON_TEXT = '{\n  "share": 0.698,\n  "category": "most",\n  "n": 20\n}\n'
MD_TEXT = "# Result\n\nshare 0.698\n"


@pytest.mark.parametrize("name,text", [("summary.json", JSON_TEXT), ("notes.md", MD_TEXT)])
def test_crlf_and_lf_files_with_identical_content_match(tmp_path, name, text):
    expected = tmp_path / "expected" / name
    actual = tmp_path / "actual" / name
    expected.parent.mkdir()
    actual.parent.mkdir()
    expected.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))
    actual.write_bytes(text.encode("utf-8"))
    assert b"\r\n" in expected.read_bytes() and b"\r" not in actual.read_bytes()
    reproduce._compare(expected, actual)


@pytest.mark.parametrize("name,text,old,new", [
    ("summary.json", JSON_TEXT, "0.698", "0.699"),
    ("summary.json", JSON_TEXT, '"n": 20', '"n": 20.0'),  # int vs float is a difference
    ("notes.md", MD_TEXT, "0.698", "0.699"),
])
def test_one_differing_number_fails(tmp_path, name, text, old, new):
    expected = tmp_path / "expected" / name
    actual = tmp_path / "actual" / name
    expected.parent.mkdir()
    actual.parent.mkdir()
    expected.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))
    actual.write_bytes(text.replace(old, new).encode("utf-8"))
    with pytest.raises(ValueError, match="canonical byte comparison failed"):
        reproduce._compare(expected, actual)


def test_archived_crlf_json_matches_its_lf_form(tmp_path):
    # The archived decomposition JSON has CRLF line endings; its LF form (what
    # a Linux rebuild writes) must compare equal.
    archived = (Path(__file__).resolve().parents[2] / "results" / "phase1_v2_corrected"
                / "analysis" / "registered" / "phase1_v2_decomposition.json")
    raw = archived.read_bytes()
    assert b"\r\n" in raw
    lf = tmp_path / archived.name
    lf.write_bytes(raw.replace(b"\r\n", b"\n"))
    reproduce._compare(archived, lf)
