#!/usr/bin/env python3
"""Generate docs/LABS.md, the reference of every simulated room ("lab").

The document is built only from files that the experiments themselves read:

* config/run_config.json      - which labs exist, their ports and flow files,
                                and which phase uses which lab;
* src/agt/lab_profiles.asl    - per lab: device description, knowledge graph,
                                scenario files, light and sun rank bounds,
                                zone targets, episode budget;
* simulator/simulator_flow_*.json - the Node-RED flows; the light formula of
                                each zone is copied from the flow's
                                "Update environment" function node;
* benchmark/*.json            - the start situations (sun levels, energy budget).

Nothing is typed in by hand, so the document cannot drift from the code:
run the script again after changing a flow, a profile or the configuration.

Usage:
  python scripts/make_labs_doc.py           # write docs/LABS.md
  python scripts/make_labs_doc.py --check   # exit 1 if docs/LABS.md is out of date

Standard library only.
"""

from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OUT = REPO / "docs" / "LABS.md"
CONFIG = REPO / "config" / "run_config.json"
PROFILES = REPO / "src" / "agt" / "lab_profiles.asl"
SIM = REPO / "simulator"
RES = REPO / "src" / "resources"

# Identifiers that occur in flow code but are not lab variables.
JS_WORDS = {"var", "flow", "get", "set", "Math", "max", "min", "true", "false",
            "null", "undefined", "return", "msg", "if", "else", "function",
            "random", "floor", "abs", "round"}


# ---------------------------------------------------------------------------
# Reading the inputs
# ---------------------------------------------------------------------------

def load_json(path: Path):
    # Some committed files carry a UTF-8 byte-order mark; utf-8-sig accepts both.
    return json.loads(path.read_text(encoding="utf-8-sig"))


def repair_text(text: str) -> str:
    """Undo UTF-8 text that was decoded as Windows-1252 (once or twice).

    A few committed flows carry such garbled characters in comments and tab
    labels. Only used for labels shown in the document; code is never changed.
    """
    for _ in range(3):
        buf = bytearray()
        try:
            for ch in text:
                code = ord(ch)
                if code < 0x80:
                    buf.append(code)
                elif 0x80 <= code <= 0x9F or 0xA0 <= code <= 0xFF:
                    try:
                        buf += ch.encode("cp1252")
                    except UnicodeEncodeError:
                        buf.append(code)
                else:
                    buf += ch.encode("cp1252")
            fixed = buf.decode("utf-8")
        except (UnicodeEncodeError, UnicodeDecodeError):
            return text
        if fixed == text:
            return text
        text = fixed
    return text


def parse_profiles(text: str) -> dict:
    """Parse every lab_profile(...) fact of lab_profiles.asl into a dict."""
    profiles = {}
    for m in re.finditer(r'lab_profile\("([^"]+)",(.*?)\)\)\.', text, re.S):
        name, body = m.group(1), m.group(2) + ")"
        def field(key, body=body):
            fm = re.search(key + r"\((.*?)\)\s*(?:,|$)", body, re.S)
            return fm.group(1).strip() if fm else None
        p = {"name": name}
        p["td"] = (field("td") or "").strip('"')
        p["ont"] = re.findall(r'"([^"]+)"', field("ont") or "")
        p["scenarios"] = (field("scenarios") or "").strip('"')
        p["train_scenarios"] = (field(r"train_scenarios") or "").strip('"')
        sp = field("sim_port")
        p["sim_port"] = int(sp) if sp and sp.isdigit() else None
        p["light_bounds"] = [float(x) for x in re.findall(r"[\d.]+", field("light_bounds") or "")]
        p["sunshine_bounds"] = [float(x) for x in re.findall(r"[\d.]+", field("sunshine_bounds") or "")]
        zt = re.search(r"zone_targets\(\[(.*?)\]\)", body, re.S)
        p["targets"] = [(int(z), int(r)) for z, r in
                        re.findall(r"target\(\s*(\d+)\s*,\s*(\d+)\s*\)", zt.group(1))] if zt else []
        tp = re.search(r"training_params\(\s*(\d+)\s*,\s*([\d.]+)\s*\)", body)
        p["training_params"] = (int(tp.group(1)), tp.group(2)) if tp else None
        profiles[name] = p
    return profiles


def flow_functions(flow) -> dict:
    return {n.get("name", n.get("id")): n.get("func", "")
            for n in flow if n.get("type") == "function"}


def strip_comments(code: str) -> str:
    """Remove // comments and blank lines (flow code has no '//' inside strings)."""
    out = []
    for line in code.splitlines():
        cut = line.split("//", 1)[0].rstrip()
        if cut.strip():
            out.append(cut)
    return "\n".join(out)


def statement_at(code: str, start: int) -> str:
    """Return the statement that starts at `start`, up to the ';' at depth 0."""
    depth = 0
    for i in range(start, len(code)):
        c = code[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == ";" and depth == 0:
            return code[start:i + 1]
    return code[start:]


def function_def(code: str, name: str):
    m = re.search(r"function\s+" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    i = code.index("{", m.end())
    depth = 0
    for j in range(i, len(code)):
        if code[j] == "{":
            depth += 1
        elif code[j] == "}":
            depth -= 1
            if depth == 0:
                return code[m.start():j + 1]
    return None


def identifiers(expr: str):
    expr = re.sub(r"'[^']*'|\"[^\"]*\"", "", expr)
    return [w for w in re.findall(r"[A-Za-z_]\w*", expr) if w not in JS_WORDS]


def dedent(block: str) -> str:
    """Statements in the flows start in column 0; keep continuation lines as written."""
    return "\n".join(l.rstrip() for l in block.strip().splitlines())


class FlowPhysics:
    """What the document needs from one flow file."""

    def __init__(self, path: Path):
        self.path = path
        self.flow = load_json(path)
        funcs = flow_functions(self.flow)
        self.funcs = funcs
        tabs = [n.get("label", "") for n in self.flow if n.get("type") == "tab"]
        self.tab = repair_text(tabs[0]) if tabs else ""
        env_name = next((k for k in funcs if "environment" in k.lower()), None)
        self.env = funcs.get(env_name, "")
        self.code = strip_comments(self.env)
        action = funcs.get("Update action", "")
        self.devices = list(dict.fromkeys(re.findall(r"msg\.payload\.(\w+)\s*!==\s*undefined", action)))
        if not self.devices:
            # Older flows list their switches in one object literal.
            am = re.search(r"var\s+actuators\s*=\s*\{(.*?)\}", action, re.S)
            if am:
                self.devices = re.findall(r"(\w+)\s*:", am.group(1))
        self.status_keys = re.findall(r"^\s*(\w+)\s*:\s*flow\.get", funcs.get("Status", ""), re.M)
        self.zone_exprs = {int(z): e.strip() for z, e in
                           re.findall(r"flow\.set\(\s*['\"]Z(\d+)Level['\"]\s*,\s*(.+?)\)\s*;", self.code)}
        health = funcs.get("Health", "")
        hm = re.search(r"tab:\s*'([^']+)'", health)
        self.health_tab = hm.group(1) if hm else None
        dm = re.search(r"var\s+DELAY\s*=\s*(\d+)", self.code)
        self.delay_ticks = int(dm.group(1)) if dm else None
        self.uses_random = "Math.random" in self.code

    # -- light formula -----------------------------------------------------

    def _var_statement(self, name: str):
        m = re.search(r"(?m)^\s*var\s+" + re.escape(name) + r"\s*=", self.code)
        if not m:
            return None
        start = self.code.index("var", m.start())
        return statement_at(self.code, start)

    def _reassigned(self, name: str) -> bool:
        return bool(re.search(r"(?<![\w.])" + re.escape(name) + r"\s*(\+=|-=|\*=|=(?!=))", self.code
                              .replace("var " + name, "")))

    def formula(self):
        """Return (statements, readings) for the zone light levels, or None.

        statements: code statements, in source order, that compute every
        zone level; readings: {variable: status key} for plain flag reads.
        None means the flow does not use one expression per zone (older labs);
        the caller then shows the whole function.
        """
        if not self.zone_exprs:
            return None
        wanted, readings, seen = [], {}, set()

        def visit(name):
            if name in seen:
                return True
            seen.add(name)
            stmt = self._var_statement(name)
            if stmt is None:
                fdef = function_def(self.code, name)
                if fdef:
                    wanted.append(fdef)
                    for dep in identifiers(fdef):
                        if dep != name and self._var_statement(dep) is not None and dep.isupper():
                            visit(dep)
                    return True
                return False
            if self._reassigned(name):
                return False
            rm = re.fullmatch(r"var\s+\w+\s*=\s*flow\.get\(\s*['\"](\w+)['\"]\s*\)\s*\|\|\s*\w+\s*;",
                              re.sub(r"\s+", " ", stmt.strip()))
            if rm:
                readings[name] = rm.group(1)
                return True
            wanted.append(stmt)
            rhs = stmt.split("=", 1)[1]
            for dep in identifiers(rhs):
                if not visit(dep):
                    return False
            return True

        for z in sorted(self.zone_exprs):
            for ident in identifiers(self.zone_exprs[z]):
                if not visit(ident):
                    return None
        order = sorted(set(wanted), key=lambda s: self.code.index(s))
        return [dedent(s.strip()) for s in order], readings

    def energy_statement(self):
        m = re.search(r"(?m)^\s*var\s+perTick\s*=", self.code)
        if not m:
            return None
        return dedent(statement_at(self.code, self.code.index("var", m.start())).strip())


# ---------------------------------------------------------------------------
# Formatting helpers
# ---------------------------------------------------------------------------

def rank_ranges(bounds):
    b = [int(x) if float(x).is_integer() else x for x in bounds]
    return [f"below {b[0]}", f"{b[0]} to below {b[1]}", f"{b[1]} to below {b[2]}", f"{b[2]} or more"]


def rank_of(value, bounds):
    for i, b in enumerate(bounds):
        if value < b:
            return i
    return len(bounds)


def num(v):
    return str(int(v)) if isinstance(v, float) and v.is_integer() else str(v)


def rel(path: Path) -> str:
    return path.relative_to(REPO).as_posix()


def link_from_docs(relpath: str) -> str:
    return f"[`{relpath}`](../{relpath})"


def resource_path(classpath_name: str) -> str:
    return "src/resources/" + classpath_name.replace("classpath:", "")


def scenario_facts(relpath: str):
    p = REPO / relpath
    rows = load_json(p)
    suns = sorted({r["Sunshine"] for r in rows if "Sunshine" in r})
    budgets = sorted({r["energyBudget"] for r in rows if "energyBudget" in r})
    return len(rows), suns, budgets


def require(relpath: str, missing: list):
    if relpath and not (REPO / relpath).exists():
        missing.append(relpath)


# ---------------------------------------------------------------------------
# Document
# ---------------------------------------------------------------------------

def phase_table(cfg):
    """Which labs each phase uses, read from the configuration blocks."""
    p2 = cfg["phase2"]
    parents = []
    for a in p2["adapt_profiles"]:
        par = p2["parent_profile"][a]
        if par not in parents:
            parents.append(par)
    return [
        ("Phase 1 (healthy rooms)", "`profiles_to_run`", cfg["profiles_to_run"]),
        ("Phase 1b (mechanism rooms)", "`phase1b.phase1b_profiles`", cfg["phase1b"]["phase1b_profiles"]),
        ("Phase 2, healthy parent rooms (trained first)", "`phase2.parent_profile`", parents),
        ("Phase 2, rooms with broken devices", "`phase2.adapt_profiles`", p2["adapt_profiles"]),
        ("Phase 3 (slow devices)", "`phase3.dynamics_profiles`", cfg["phase3"]["dynamics_profiles"]),
        ("Phase 4 (dependencies and energy)", "`phase4.phase4_profiles`", cfg["phase4"]["phase4_profiles"]),
    ]


def lab_section(name, cfg, profiles, flows, missing, port, flow_file, kind, parent=None):
    prof = profiles.get(name)
    lines = [f"### {name}", ""]
    fp = flows[flow_file]
    if prof is None:
        lines += [f"No `lab_profile(\"{name}\", ...)` entry exists in `src/agt/lab_profiles.asl`.", ""]
        return lines
    lines.append(f"Flow tab label: \"{fp.tab}\".")
    lines.append("")
    zones = sorted(fp.zone_exprs) or sorted(int(k[1]) for k in fp.status_keys if re.fullmatch(r"Z\d+Level", k))
    lb, sb = prof["light_bounds"], prof["sunshine_bounds"]
    rows = [("Zones", ", ".join(f"Z{z}" for z in zones) or "none found")]
    rows.append(("Devices (switches the flow accepts)", ", ".join(f"`{d}`" for d in fp.devices)))
    if prof["targets"]:
        rr = rank_ranges(lb) if len(lb) == 3 else None
        tgt = "; ".join(f"Z{z}: rank {r}" + (f" ({rr[r]} lux)" if rr else "") for z, r in prof["targets"])
        rows.append(("Target", tgt))
    if len(lb) == 3:
        rows.append(("Light rank bounds (lux)", ", ".join(num(x) for x in lb)
                     + " (rank 0 " + rank_ranges(lb)[0] + ", rank 3 " + rank_ranges(lb)[3] + ")"))
    if len(sb) == 3:
        rows.append(("Sun rank bounds (lux)", ", ".join(num(x) for x in sb)))
    if kind == "fault":
        # The fault stage starts its episodes from the training situations only.
        sources = (("Start situations of the fault stage", "train_scenarios"),)
    else:
        sources = (("Test situations", "scenarios"), ("Training situations", "train_scenarios"))
    budgets_seen = []
    for label, key in sources:
        rp = prof[key]
        if not rp:
            continue
        require(rp, missing)
        if (REPO / rp).exists():
            n, suns, budgets = scenario_facts(rp)
            sun_txt = ", ".join(f"{num(s)} (rank {rank_of(s, sb)})" for s in suns) if suns and len(sb) == 3 \
                else (", ".join(num(s) for s in suns) if suns else "not set in the file")
            extra = f"; energy budget {', '.join(num(b) for b in budgets)}" if budgets else ""
            budgets_seen += budgets
            rows.append((label, f"{n} in {link_from_docs(rp)}; sun levels {sun_txt}{extra}"))
    if kind == "fault":
        p2map = cfg["phase2"]["train_scenarios_map"].get(name)
        if p2map and p2map != prof["train_scenarios"]:
            rows.append(("Fault-stage situations (config)", link_from_docs(p2map)))
    if prof["training_params"]:
        eps, decay = prof["training_params"]
        if kind == "fault":
            txt = f"{eps} episodes of re-learning (fault-stage budget), exploration decay {decay} per episode"
        else:
            txt = (f"{eps} episodes in the profile (the training runner replaces this with the run mode's "
                   f"`num_episodes`), exploration decay {decay} per episode")
        rows.append(("Episode budget", txt))
    dim = (cfg.get("expected_state_vec_dim", {}).get(name)
           or cfg["phase2"]["expected_state_vec_dim"].get(name)
           or cfg["phase3"]["expected_state_vec_dim"].get(name))
    if dim:
        rows.append(("State entries", str(dim)))
    rows.append(("Port", str(port) + ("" if prof["sim_port"] in (None, port)
                                     else f" (the profile says {prof['sim_port']})")))
    td = resource_path(prof["td"]) if prof["td"] else ""
    require(td, missing)
    for o in prof["ont"]:
        require("src/resources/" + o, missing)
    rows.append(("Flow file", link_from_docs("simulator/" + flow_file)))
    if td:
        rows.append(("Device description (Thing Description)", link_from_docs(td)))
    rows.append(("Knowledge graph", ", ".join(link_from_docs("src/resources/" + o) for o in prof["ont"])))
    if fp.health_tab:
        rows.append(("Name in the `/health` answer (`tab`)", f"`{fp.health_tab}`"))
    lines += ["| Item | Value |", "|---|---|"] + [f"| {a} | {b} |" for a, b in rows] + [""]

    feats = []
    if fp.delay_ticks is not None:
        sec = cfg["phase3"]["seconds_per_tick"]
        feats.append(f"The blinds react only after `DELAY = {fp.delay_ticks}` simulator ticks; "
                     f"`config/run_config.json` counts one tick as {num(float(sec))} seconds "
                     f"(`phase3.seconds_per_tick`). The lamps react at once. The flow keeps a tick "
                     f"counter `Tick` that the delay prober reads.")
    gate = [d for d in fp.devices if re.search(r"Plug|MasterSwitch|Breaker|Aux", d)]
    if gate:
        feats.append("Power dependency: the devices " + ", ".join(f"`{g}`" for g in gate)
                     + " give no light themselves; they decide whether a lamp can light (see the formula).")
    if budgets_seen:
        p4 = cfg["phase4"]
        feats.append(f"Energy budget: every situation carries `energyBudget` "
                     f"{', '.join(num(b) for b in sorted(set(budgets_seen)))}. The Phase-4 analysis scores the "
                     f"power of the final switch positions with `phase4.power_formula` = "
                     f"`{p4['power_formula']}` from the configuration and compares it with the budget. "
                     f"Energy is not part of the agent's reward.")
    if fp.uses_random:
        feats.append("The `Update environment` code contains `Math.random` (see the code below), so the "
                     "light levels are not always a fixed function of the switch positions.")
    if feats:
        lines += ["Special features:", ""] + [f"- {f}" for f in feats] + [""]

    form = fp.formula()
    lines.append("Light formula, copied from the flow's `Update environment` node (comments removed). "
                 "The short variable names hold switch positions and the sun level read from the flow "
                 "state, as listed under *Readings*; a switch that is on counts as true.")
    lines.append("")
    if form is None:
        lines += ["In this flow the light of a zone is not computed by one expression, so the whole node is shown "
                  "(comments removed).", "",
                  "<details><summary>Code of the <code>Update environment</code> node</summary>", "",
                  "```javascript", fp.code, "```", "", "</details>", ""]
    else:
        stmts, readings = form
        if readings:
            lines.append("Readings: " + ", ".join(f"`{v}` = `{k}`" for v, k in readings.items()) + ".")
            lines.append("")
        lines += ["```javascript"] + stmts + ["```", ""]
        for z in sorted(fp.zone_exprs):
            lines.append(f"`Z{z}Level` is set to `{fp.zone_exprs[z]}`.")
        lines.append("")
    en = fp.energy_statement()
    if en:
        lines += ["Energy counted per tick (`EnergyCost`, summed in `TotalEnergyCost`):", "",
                  "```javascript", en, "```", ""]
    if kind == "fault" and parent:
        lines += fault_diff(flows[flow_file], flows[parent_flow(cfg, parent)], parent)
    return lines


def parent_flow(cfg, parent):
    return cfg["simulator_flow_map"][parent]


def fault_diff(fp: FlowPhysics, base: FlowPhysics, parent: str):
    out = [f"Difference to the healthy flow of `{parent}` (code without comments):", ""]
    any_diff = False
    for node in ("Update environment", "Reset episode", "Set state (benchmark)", "Update action"):
        a = strip_comments(base.funcs.get(node, "")).splitlines()
        b = strip_comments(fp.funcs.get(node, "")).splitlines()
        d = [l for l in difflib.unified_diff(a, b, lineterm="", n=0) if not l.startswith(("---", "+++", "@@"))]
        if d:
            any_diff = True
            out += [f"Node `{node}`:", "", "```diff"] + d + ["```", ""]
    if not any_diff:
        out += ["(no code difference)", ""]
    return out


def build() -> str:
    cfg = load_json(CONFIG)
    profiles = parse_profiles(PROFILES.read_text(encoding="utf-8"))
    missing: list[str] = []

    base_flows = dict(cfg["simulator_flow_map"])
    fault_flows = dict(cfg["phase2"]["simulator_flow_map"])
    flows = {}
    for f in list(base_flows.values()) + list(fault_flows.values()):
        if f not in flows:
            path = SIM / f
            if not path.exists():
                missing.append(rel(path))
                continue
            flows[f] = FlowPhysics(path)

    phases = phase_table(cfg)
    used = {lab for _, _, labs in phases for lab in labs}
    alias_of = {}
    seen_flow = {}
    for lab, f in base_flows.items():
        if f in seen_flow:
            alias_of[lab] = seen_flow[f]
        else:
            seen_flow[f] = lab

    L = []
    L += ["# The simulated labs", "",
          "<!-- Generated by scripts/make_labs_doc.py from config/run_config.json, "
          "src/agt/lab_profiles.asl, simulator/*.json and benchmark/*.json. "
          "Do not edit by hand: change the sources and run the script again. -->", "",
          "A *lab* is one simulated room. Each lab is a Node-RED flow in `simulator/`. The flow keeps the "
          "switch positions and the sun level, and every 50 milliseconds (the *tick*, an `inject` node "
          "with `repeat` 0.05) its `Update environment` node computes the light level of every zone. "
          "The agent talks to the flow over HTTP: `GET /was/rl/status` returns the state, "
          "`POST /was/rl/action` sets switches, `POST /was/rl/setState` puts the room into a prepared "
          "start situation, and `GET /health` answers the runners' start-up check. A further endpoint, "
          "`POST /was/rl/reset`, draws a random start inside the flow; none of the agent programs calls it, "
          "every episode starts from a situation of a scenario file through `/was/rl/setState`.",
          "",
          "The agent does not see lux values. `LabEnvironment.java` turns each light level into a *rank* "
          "from 0 to 3 with the lab's light rank bounds (a value below the first bound is rank 0, a value "
          "at or above the third bound is rank 3), and the sun level into a sun rank the same way. "
          "A zone's *target* is a rank; the agent reaches its goal when every zone is at its target rank.",
          "",
          "Per lab, the profile entry in `src/agt/lab_profiles.asl` names the device description "
          "(a W3C Web of Things Thing Description in `src/resources/interactions-*.ttl`), the knowledge "
          "graph (`src/resources/building_*.ttl`), the test and training situations in `benchmark/`, "
          "the rank bounds and the targets. Ports and flow files come from `config/run_config.json`.",
          "",
          "## Which phase uses which lab", "",
          "| Phase | Configuration key | Labs |", "|---|---|---|"]
    for title, key, labs in phases:
        L.append(f"| {title} | {key} | {', '.join(f'[{l}](#{l})' for l in labs)} |")
    others = [lab for lab in base_flows if lab not in used]
    L += ["", "Labs that exist in the configuration but belong to no phase block: "
          + ", ".join(f"[{l}](#{l})" for l in others) + ". They are kept so that older runs stay "
          "reproducible; no accepted campaign under `results/` uses them.", ""]

    def section(title, labs, kind):
        out = [f"## {title}", ""]
        for lab in labs:
            if lab in alias_of:
                prof = profiles.get(lab, {})
                base = alias_of[lab]
                out += [f"### {lab}", "",
                        f"Uses the same flow ({link_from_docs('simulator/' + base_flows[lab])}) and port "
                        f"({cfg['simulator_port_map'][lab]}) as [{base}](#{base}); only the knowledge graph "
                        f"differs: " + ", ".join(link_from_docs("src/resources/" + o) for o in prof.get("ont", []))
                        + ".", ""]
                for o in prof.get("ont", []):
                    require("src/resources/" + o, missing)
                continue
            if kind == "fault":
                port = cfg["phase2"]["simulator_port_map"][lab]
                flow_file = fault_flows[lab]
                parent = cfg["phase2"]["parent_profile"][lab]
                base_parent = alias_of.get(parent, parent)
                if flow_file not in flows:
                    continue
                out += lab_section(lab, cfg, profiles, flows, missing, port, flow_file, "fault", base_parent)
            else:
                port = cfg["simulator_port_map"][lab]
                flow_file = base_flows[lab]
                if flow_file not in flows:
                    continue
                out += lab_section(lab, cfg, profiles, flows, missing, port, flow_file, "base")
        return out

    order = []
    def add(labs):
        for lab in labs:
            if lab not in order:
                order.append(lab)
    add(cfg["profiles_to_run"])
    L += section("Phase 1 labs", cfg["profiles_to_run"], "base")
    L += section("Phase 1b labs", cfg["phase1b"]["phase1b_profiles"], "base")
    parents = [lab for lab in phases[2][2] if lab not in cfg["profiles_to_run"]]
    L += section("Further Phase 2 parent labs", parents, "base")
    L += ["## Phase 2 labs with broken devices", "",
          "Each of these labs is a copy of its healthy parent flow in which one or two devices are broken: "
          "*dead* (the device has no effect on the light) or *inverted* (it lowers the light where it should "
          "raise it). The copies are written by `simulator/generate_faulty_flows.ps1` from the healthy flows. "
          "A fault lab runs on its parent's port and uses its parent's device description, so the agent "
          "cannot tell from the description that anything is broken. The table of every lab below ends with "
          "the code that differs from the parent.", ""]
    L += section("", cfg["phase2"]["adapt_profiles"], "fault")[2:]
    L += section("Phase 3 labs", cfg["phase3"]["dynamics_profiles"], "base")
    L += section("Phase 4 labs", cfg["phase4"]["phase4_profiles"], "base")
    L += section("Labs outside the phase blocks", others, "base")

    if missing:
        raise SystemExit("make_labs_doc: files named by the configuration are missing:\n  "
                         + "\n  ".join(sorted(set(missing))))
    text = "\n".join(L).rstrip() + "\n"
    return re.sub(r"\n{3,}", "\n\n", text)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="exit 1 if docs/LABS.md is out of date")
    args = ap.parse_args()
    text = build()
    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            print("docs/LABS.md is out of date; run: python scripts/make_labs_doc.py")
            return 1
        print("docs/LABS.md is up to date")
        return 0
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {rel(OUT)} ({len(text.splitlines())} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
