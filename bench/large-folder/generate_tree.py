#!/usr/bin/env python3
"""Create a large content tree in an Alfresco repository over the public REST API.

Writes a manifest describing what was created, which the sampler and the report
use as the expected index target. Safe to re-run with ``--resume``: nodes that
already exist under the same names are reused instead of failing.
"""

import argparse
import base64
import json
import os
import sys
import threading
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection, HTTPException, HTTPSConnection
from urllib.parse import quote, urlsplit

import profiles

API_PREFIX = "/api/-default-/public/alfresco/versions/1"
RETRY_STATUSES = frozenset((429, 500, 502, 503, 504))
MAX_ATTEMPTS = 5
BACKOFF_BASE = 0.5


class RepositoryError(RuntimeError):
    pass


class Repository:
    def __init__(self, url, user, password, timeout):
        parsed = urlsplit(url)
        self.secure = parsed.scheme == "https"
        self.host = parsed.hostname
        self.port = parsed.port or (443 if self.secure else 80)
        self.prefix = parsed.path.rstrip("/") + API_PREFIX
        self.timeout = timeout
        token = base64.b64encode("{}:{}".format(user, password).encode()).decode()
        self.authorization = "Basic " + token
        self._local = threading.local()

    def _connection(self):
        connection = getattr(self._local, "connection", None)
        if connection is None:
            factory = HTTPSConnection if self.secure else HTTPConnection
            connection = factory(self.host, self.port, timeout=self.timeout)
            self._local.connection = connection
        return connection

    def _drop_connection(self):
        connection = getattr(self._local, "connection", None)
        if connection is not None:
            try:
                connection.close()
            except OSError:
                pass
            self._local.connection = None

    def request(self, method, path, body=None):
        headers = {"Authorization": self.authorization, "Accept": "application/json"}
        payload = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(body).encode()

        last_error = None
        for attempt in range(MAX_ATTEMPTS):
            try:
                connection = self._connection()
                connection.request(method, self.prefix + path, payload, headers)
                response = connection.getresponse()
                raw = response.read()
                status = response.status
            except (OSError, HTTPException) as error:
                self._drop_connection()
                last_error = error
                time.sleep(BACKOFF_BASE * (2 ** attempt))
                continue

            if status in RETRY_STATUSES and attempt < MAX_ATTEMPTS - 1:
                time.sleep(BACKOFF_BASE * (2 ** attempt))
                continue
            return status, raw

        raise RepositoryError("{} {} failed after {} attempts: {}".format(
            method, path, MAX_ATTEMPTS, last_error))

    def create_child(self, parent_id, name, is_folder, resume):
        body = {"name": name, "nodeType": "cm:folder" if is_folder else "cm:content"}
        status, raw = self.request("POST", "/nodes/{}/children".format(parent_id), body)
        if status == 201:
            return json.loads(raw)["entry"]["id"], True
        if status == 409 and resume:
            return self.resolve_child(parent_id, name), False
        raise RepositoryError("create {!r} under {} -> HTTP {}: {}".format(
            name, parent_id, status, raw[:300].decode("utf-8", "replace")))

    def resolve_child(self, parent_id, name):
        path = "/nodes/{}?relativePath={}".format(parent_id, quote(name, safe=""))
        status, raw = self.request("GET", path)
        if status == 200:
            return json.loads(raw)["entry"]["id"]
        raise RepositoryError("resolve {!r} under {} -> HTTP {}: {}".format(
            name, parent_id, status, raw[:300].decode("utf-8", "replace")))

    def node_exists(self, node_id):
        status, _ = self.request("GET", "/nodes/{}".format(node_id))
        return status == 200


class Progress:
    def __init__(self, total, every, started):
        self.total = total
        self.every = every
        self.started = started
        self.done = 0
        self.reused = 0
        self.samples = []
        self._lock = threading.Lock()
        self._last_count = 0
        self._last_time = started

    def record(self, created):
        with self._lock:
            self.done += 1
            if not created:
                self.reused += 1
            if self.done % self.every and self.done != self.total:
                return
            now = time.monotonic()
            window = now - self._last_time
            chunk = self.done - self._last_count
            rate = chunk / window if window > 0 else 0.0
            self.samples.append({
                "created": self.done,
                "elapsed_s": round(now - self.started, 3),
                "window_rate_nodes_per_s": round(rate, 2),
            })
            self._last_count = self.done
            self._last_time = now
            sys.stderr.write("  {:>7}/{} nodes  {:>7.1f} nodes/s  {:>6.0f}s elapsed\n".format(
                self.done, self.total, rate, now - self.started))
            sys.stderr.flush()


def group_by_level(plan):
    level_of = {0: 0}
    levels = defaultdict(list)
    for node in plan:
        level = level_of[node.parent] + 1
        level_of[node.index] = level
        levels[level].append(node)
    return [levels[key] for key in sorted(levels)]


def create_level(repository, level, node_ids, lock, workers, resume, progress):
    def create(node):
        with lock:
            parent_id = node_ids[node.parent]
        node_id, created = repository.create_child(parent_id, node.name, node.is_folder, resume)
        with lock:
            node_ids[node.index] = node_id
        progress.record(created)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for _ in pool.map(create, level):
            pass


def write_manifest(path, manifest):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-url", default="http://localhost:8080/alfresco")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--profile", default="flat", choices=sorted(profiles.PROFILES))
    parser.add_argument("--total", type=int, default=30000,
                        help="total nodes to create, folders included")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--parent-id", default="-root-")
    parser.add_argument("--root-name", default=None,
                        help="name of the benchmark root folder (default: bench-<profile>-<total>)")
    parser.add_argument("--resume", action="store_true",
                        help="reuse nodes that already exist instead of failing on HTTP 409")
    parser.add_argument("--progress-every", type=int, default=1000)
    parser.add_argument("--manifest", default="manifest.json")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the planned shape and exit without touching the repository")
    args = parser.parse_args(argv)

    plan = profiles.build(args.profile, args.total)
    shape = profiles.describe(plan)
    root_name = args.root_name or "bench-{}-{}".format(args.profile, args.total)

    sys.stderr.write("profile {} -> {}\n".format(args.profile, json.dumps(shape)))
    if args.dry_run:
        return 0

    repository = Repository(args.repo_url, args.user, args.password, args.timeout)
    started_wall = time.time()
    started = time.monotonic()

    root_id, root_created = repository.create_child(args.parent_id, root_name, True, args.resume)
    sys.stderr.write("root folder {} ({}) {}\n".format(
        root_name, root_id, "created" if root_created else "reused"))

    node_ids = {0: root_id}
    lock = threading.Lock()
    progress = Progress(len(plan), max(1, args.progress_every), started)
    levels = group_by_level(plan)
    interrupted = False

    try:
        for depth, level in enumerate(levels, start=1):
            sys.stderr.write("level {}/{}: {} nodes\n".format(depth, len(levels), len(level)))
            create_level(repository, level, node_ids, lock, args.workers, args.resume, progress)
    except KeyboardInterrupt:
        interrupted = True
        sys.stderr.write("interrupted — writing a partial manifest\n")

    elapsed = time.monotonic() - started
    manifest = {
        "root_id": root_id,
        "root_name": root_name,
        "parent_id": args.parent_id,
        "profile": args.profile,
        "repo_url": args.repo_url,
        "requested_total": args.total,
        "shape": shape,
        "nodes_created": progress.done - progress.reused + (1 if root_created else 0),
        "nodes_reused": progress.reused + (0 if root_created else 1),
        "nodes_total": progress.done + 1,
        "workers": args.workers,
        "interrupted": interrupted,
        "started_epoch": started_wall,
        "elapsed_s": round(elapsed, 3),
        "rate_nodes_per_s": round(progress.done / elapsed, 2) if elapsed > 0 else None,
        "rate_samples": progress.samples,
    }
    write_manifest(args.manifest, manifest)
    sys.stderr.write("manifest written to {}\n".format(args.manifest))
    sys.stderr.write("{} nodes in {:.0f}s ({:.1f} nodes/s)\n".format(
        manifest["nodes_total"], elapsed, manifest["rate_nodes_per_s"] or 0.0))
    return 1 if interrupted else 0


if __name__ == "__main__":
    sys.exit(main())
