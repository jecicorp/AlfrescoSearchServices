#!/usr/bin/env python3
"""Sample indexing progress while the trackers catch up.

Writes one CSV row per interval: Solr document counts per DOC_TYPE, the tracker
transaction lag, and — when container names are given — memory, CPU, restart
count and OOM-kill flag for each container. Exits when the index reaches the
expected node count, when it stops moving, or on timeout.
"""

import argparse
import csv
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DOC_TYPES = ("Node", "Acl", "Tx", "AclTx", "ErrorNode", "UnindexedNode")


def http_json(url, secret=None, timeout=30.0):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    if secret:
        request.add_header("X-Alfresco-Search-Secret", secret)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def solr_doc_types(solr_url, core, secret, timeout):
    query = urllib.parse.urlencode({
        "q": "*:*",
        "defType": "lucene",
        "rows": 0,
        "facet": "true",
        "facet.field": "DOC_TYPE",
        "facet.mincount": 0,
        "wt": "json",
        "omitHeader": "true",
    })
    url = "{}/{}/select?{}".format(solr_url.rstrip("/"), core, query)
    payload = http_json(url, secret, timeout)
    raw = payload["facet_counts"]["facet_fields"]["DOC_TYPE"]
    counts = dict(zip(raw[0::2], raw[1::2]))
    return {name: int(counts.get(name, 0)) for name in DOC_TYPES}


def tracker_summary(trackers_url, core, timeout):
    url = "{}/api/admin/summary?core={}".format(trackers_url.rstrip("/"), core)
    payload = http_json(url, None, timeout)
    report = payload.get(core, {})
    transactions = report.get("TX", {}) or {}
    lag = report.get("TX Lag", "")
    lag_seconds = None
    if isinstance(lag, str) and lag.endswith(" s"):
        try:
            lag_seconds = int(lag[:-2])
        except ValueError:
            lag_seconds = None
    return {
        "tx_indexed_id": transactions.get("Id"),
        "tx_server_id": transactions.get("IdOnServer"),
        "tx_remaining": report.get("Approx transactions remaining"),
        "tx_lag_s": lag_seconds,
        "tx_duration_lag_ms": report.get("TXDurationLag"),
    }


def parse_size(value):
    units = {"B": 1e-6, "KIB": 1e-3, "MIB": 1.0, "GIB": 1024.0,
             "KB": 1e-3, "MB": 1.0, "GB": 1024.0}
    text = value.strip().upper()
    for unit in sorted(units, key=len, reverse=True):
        if text.endswith(unit):
            try:
                return round(float(text[:-len(unit)]) * units[unit], 1)
            except ValueError:
                return None
    return None


def docker_stats(containers):
    result = {name: {} for name in containers}
    try:
        output = subprocess.run(
            ["docker", "stats", "--no-stream", "--format",
             "{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}"] + list(containers),
            capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return result
    for line in output.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 3 or parts[0] not in result:
            continue
        name, memory, cpu = parts
        result[name]["mem_mb"] = parse_size(memory.split("/")[0])
        result[name]["cpu_pct"] = cpu.strip().rstrip("%")

    try:
        output = subprocess.run(
            ["docker", "inspect", "-f",
             "{{.Name}}\t{{.State.OOMKilled}}\t{{.RestartCount}}\t{{.State.Status}}"]
            + list(containers),
            capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return result
    for line in output.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 4:
            continue
        name = parts[0].lstrip("/")
        if name not in result:
            continue
        result[name]["oom_killed"] = parts[1]
        result[name]["restarts"] = parts[2]
        result[name]["state"] = parts[3]
    return result


def sample(args, containers):
    row = {"timestamp": time.strftime("%Y-%m-%dT%H:%M:%S")}
    try:
        row.update(solr_doc_types(args.solr_url, args.core, args.search_secret, args.http_timeout))
        row["solr_error"] = ""
    except (urllib.error.URLError, OSError, KeyError, ValueError) as error:
        row.update({name: None for name in DOC_TYPES})
        row["solr_error"] = str(error)[:120]
    try:
        row.update(tracker_summary(args.trackers_url, args.core, args.http_timeout))
        row["trackers_error"] = ""
    except (urllib.error.URLError, OSError, KeyError, ValueError) as error:
        row.update({"tx_indexed_id": None, "tx_server_id": None, "tx_remaining": None,
                    "tx_lag_s": None, "tx_duration_lag_ms": None})
        row["trackers_error"] = str(error)[:120]
    for name, stats in docker_stats(containers).items():
        for key, value in stats.items():
            row["{}_{}".format(name, key)] = value
    return row


def build_fieldnames(containers):
    names = ["timestamp", "elapsed_s"]
    names += list(DOC_TYPES)
    names += ["tx_indexed_id", "tx_server_id", "tx_remaining", "tx_lag_s", "tx_duration_lag_ms"]
    for container in containers:
        names += ["{}_{}".format(container, key)
                  for key in ("mem_mb", "cpu_pct", "restarts", "oom_killed", "state")]
    names += ["solr_error", "trackers_error"]
    return names


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--solr-url", default="http://localhost:8984/solr")
    parser.add_argument("--trackers-url", default="http://localhost:8085")
    parser.add_argument("--core", default="alfresco")
    parser.add_argument("--search-secret", default=None,
                        help="value of X-Alfresco-Search-Secret; omit when going through Caddy :8984")
    parser.add_argument("--interval", type=float, default=5.0)
    parser.add_argument("--http-timeout", type=float, default=30.0)
    parser.add_argument("--until-nodes", type=int, default=None,
                        help="stop once the Node document count reaches this value")
    parser.add_argument("--stable-for", type=float, default=None,
                        help="stop after this many seconds without any change in the Node count")
    parser.add_argument("--timeout", type=float, default=7200.0)
    parser.add_argument("--containers", default="",
                        help="comma-separated container names to sample with docker stats")
    parser.add_argument("--out", default=None, help="CSV output path (default: stdout)")
    parser.add_argument("--once", action="store_true", help="print a single sample as JSON and exit")
    args = parser.parse_args(argv)

    containers = [name for name in args.containers.split(",") if name]

    if args.once:
        json.dump(sample(args, containers), sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 0

    handle = open(args.out, "w", newline="", encoding="utf-8") if args.out else sys.stdout
    writer = csv.DictWriter(handle, fieldnames=build_fieldnames(containers), extrasaction="ignore")
    writer.writeheader()

    started = time.monotonic()
    last_nodes = None
    last_change = started
    exit_code = 0

    try:
        while True:
            row = sample(args, containers)
            now = time.monotonic()
            row["elapsed_s"] = round(now - started, 1)
            writer.writerow(row)
            handle.flush()

            nodes = row.get("Node")
            if nodes is not None and nodes != last_nodes:
                last_change = now
                last_nodes = nodes

            if args.until_nodes is not None and nodes is not None and nodes >= args.until_nodes:
                sys.stderr.write("reached {} Node docs after {:.0f}s\n".format(nodes, now - started))
                break
            if args.stable_for is not None and last_nodes is not None \
                    and now - last_change >= args.stable_for:
                sys.stderr.write("Node count stable at {} for {:.0f}s — stopping\n".format(
                    last_nodes, args.stable_for))
                exit_code = 2 if args.until_nodes is not None else 0
                break
            if now - started >= args.timeout:
                sys.stderr.write("timeout after {:.0f}s at {} Node docs\n".format(
                    now - started, last_nodes))
                exit_code = 3
                break
            time.sleep(args.interval)
    except KeyboardInterrupt:
        exit_code = 130
    finally:
        if handle is not sys.stdout:
            handle.close()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
