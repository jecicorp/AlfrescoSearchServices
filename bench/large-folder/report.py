#!/usr/bin/env python3
"""Turn one or more benchmark runs into a Markdown summary.

Given a run directory (``manifest.json`` plus ``phase-*.csv``), prints creation
throughput, indexing throughput, tracker lag and container memory. Given several
run directories, prints the same metrics side by side — the before/after view.
"""

import argparse
import csv
import glob
import json
import os
import sys

CONTAINER_SUFFIXES = ("_mem_mb", "_cpu_pct", "_restarts", "_oom_killed", "_state")


def read_manifest(run_dir):
    path = os.path.join(run_dir, "manifest.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def read_phase(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def to_number(value):
    if value in (None, "", "None"):
        return None
    try:
        return float(value)
    except ValueError:
        return None


def container_names(rows):
    names = set()
    for row in rows[:1]:
        for key in row:
            for suffix in CONTAINER_SUFFIXES:
                if key.endswith(suffix):
                    names.add(key[: -len(suffix)])
    return sorted(names)


def phase_metrics(rows, target):
    metrics = {}
    if not rows:
        return metrics

    nodes = [(to_number(row["elapsed_s"]), to_number(row.get("Node"))) for row in rows]
    nodes = [(t, n) for t, n in nodes if t is not None and n is not None]
    if not nodes:
        return metrics

    first_time, first_count = nodes[0]
    last_time, last_count = nodes[-1]
    metrics["node_docs_start"] = int(first_count)
    metrics["node_docs_end"] = int(last_count)
    metrics["sampled_s"] = round(last_time - first_time, 1)

    gained = last_count - first_count
    span = last_time - first_time
    metrics["mean_nodes_per_s"] = round(gained / span, 1) if span > 0 else None

    peak = 0.0
    for (t0, n0), (t1, n1) in zip(nodes, nodes[1:]):
        if t1 > t0:
            peak = max(peak, (n1 - n0) / (t1 - t0))
    metrics["peak_nodes_per_s"] = round(peak, 1)

    if target is not None:
        reached = next((t for t, n in nodes if n >= target), None)
        metrics["target_node_docs"] = target
        metrics["time_to_target_s"] = round(reached - first_time, 1) if reached is not None else None
        metrics["missing_at_end"] = max(0, int(target - last_count))

    lags = [to_number(row.get("tx_lag_s")) for row in rows
            if to_number(row.get("Tx"))]
    lags = [value for value in lags if value is not None]
    metrics["max_tx_lag_s"] = int(max(lags)) if lags else None

    remaining = [to_number(row.get("tx_remaining")) for row in rows]
    remaining = [value for value in remaining if value is not None]
    metrics["max_tx_remaining"] = int(max(remaining)) if remaining else None

    errors = to_number(rows[-1].get("ErrorNode"))
    metrics["error_nodes_end"] = int(errors) if errors is not None else None

    for name in container_names(rows):
        memory = [to_number(row.get(name + "_mem_mb")) for row in rows]
        memory = [value for value in memory if value is not None]
        if memory:
            metrics["{}_peak_mem_mb".format(name)] = round(max(memory), 1)
        restarts = [to_number(row.get(name + "_restarts")) for row in rows]
        restarts = [value for value in restarts if value is not None]
        if restarts:
            metrics["{}_restarts".format(name)] = int(max(restarts) - min(restarts))
        oom = [str(row.get(name + "_oom_killed", "")).lower() for row in rows]
        metrics["{}_oom_killed".format(name)] = "yes" if "true" in oom else "no"

    failures = sum(1 for row in rows if row.get("solr_error") or row.get("trackers_error"))
    metrics["sample_failures"] = failures
    return metrics


def collect(run_dir):
    manifest = read_manifest(run_dir)
    target = None
    baseline_path = os.path.join(run_dir, "baseline.json")
    if os.path.exists(baseline_path):
        with open(baseline_path, encoding="utf-8") as handle:
            baseline = json.load(handle)
        base_nodes = baseline.get("Node")
        if base_nodes is not None and manifest.get("nodes_total"):
            target = int(base_nodes) + int(manifest["nodes_total"])
    elif manifest.get("nodes_total"):
        target = int(manifest["nodes_total"])

    run = {
        "label": os.path.basename(os.path.abspath(run_dir)),
        "manifest": manifest,
        "target": target,
        "phases": {},
    }
    for path in sorted(glob.glob(os.path.join(run_dir, "phase-*.csv"))):
        name = os.path.basename(path)[len("phase-"):-len(".csv")]
        run["phases"][name] = phase_metrics(read_phase(path), target)
    return run


def creation_metrics(manifest):
    return {
        "profile": manifest.get("profile"),
        "nodes_total": manifest.get("nodes_total"),
        "max_children_in_one_folder": (manifest.get("shape") or {}).get("max_children_in_one_folder"),
        "max_depth": (manifest.get("shape") or {}).get("max_depth"),
        "creation_elapsed_s": manifest.get("elapsed_s"),
        "creation_nodes_per_s": manifest.get("rate_nodes_per_s"),
        "creation_workers": manifest.get("workers"),
    }


def render_table(title, runs, extract):
    keys = []
    for run in runs:
        for key in extract(run):
            if key not in keys:
                keys.append(key)
    if not keys:
        return []
    lines = ["", "### {}".format(title), "",
             "| metric | " + " | ".join(run["label"] for run in runs) + " |",
             "|---|" + "---|" * len(runs)]
    for key in keys:
        values = []
        for run in runs:
            value = extract(run).get(key)
            values.append("—" if value is None else str(value))
        lines.append("| `{}` | {} |".format(key, " | ".join(values)))
    return lines


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dirs", nargs="+")
    parser.add_argument("--out", default=None, help="write Markdown here instead of stdout")
    args = parser.parse_args(argv)

    runs = [collect(path) for path in args.run_dirs]
    phase_names = []
    for run in runs:
        for name in run["phases"]:
            if name not in phase_names:
                phase_names.append(name)

    lines = ["# Large-folder indexing benchmark", ""]
    lines += render_table("Tree creation (repository side)", runs,
                          lambda run: creation_metrics(run["manifest"]))
    for name in phase_names:
        lines += render_table("Phase {}".format(name), runs,
                              lambda run, name=name: run["phases"].get(name, {}))
    lines.append("")

    text = "\n".join(lines)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)
        sys.stderr.write("report written to {}\n".format(args.out))
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
