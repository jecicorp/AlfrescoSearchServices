#!/usr/bin/env python3
"""Generate the load that exercises the ACL and cascade trackers.

generate_tree.py only creates nodes, which exercises the MetadataTracker. Two of the
tuning knobs govern other trackers, and measuring them needs the matching load:

  acl     sets a distinct local permission on N nodes, so the repository mints N ACLs
          the AclTracker has to index (one Acl document each, plus the readers of every
          node it covers).
  rename  renames a folder, so the CascadeTracker has to rewrite the PATH of every
          descendant. One request, as much indexing work as there are nodes below it.
  verify  counts, through Solr, the nodes whose path actually carries the new name —
          the check that the cascade did propagate rather than merely going quiet.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_tree import Repository, RepositoryError  # noqa: E402

PAGE_SIZE = 100


def discover_targets(repository, root_id, count, folders_only):
    targets = []
    skip = 0
    while len(targets) < count:
        query = "/nodes/{}/children?maxItems={}&skipCount={}&fields=id,isFolder".format(
            root_id, PAGE_SIZE, skip)
        status, raw = repository.request("GET", query)
        if status != 200:
            raise RepositoryError("listing {} -> HTTP {}: {}".format(
                root_id, status, raw[:200].decode("utf-8", "replace")))
        entries = json.loads(raw)["list"]["entries"]
        if not entries:
            break
        for entry in entries:
            node = entry["entry"]
            if folders_only and not node.get("isFolder"):
                continue
            targets.append(node["id"])
            if len(targets) == count:
                break
        skip += PAGE_SIZE
    return targets


def set_local_permission(repository, node_id, authority):
    body = {"permissions": {
        "isInheritanceEnabled": False,
        "locallySet": [{"authorityId": authority, "name": "Consumer",
                        "accessStatus": "ALLOWED"}]}}
    status, raw = repository.request("PUT", "/nodes/{}".format(node_id), body)
    if status != 200:
        raise RepositoryError("permission on {} -> HTTP {}: {}".format(
            node_id, status, raw[:200].decode("utf-8", "replace")))


def rename(repository, node_id, new_name):
    status, raw = repository.request("PUT", "/nodes/{}".format(node_id), {"name": new_name})
    if status != 200:
        raise RepositoryError("rename {} -> HTTP {}: {}".format(
            node_id, status, raw[:200].decode("utf-8", "replace")))


def solr_count(solr_url, core, secret, query, parser, timeout):
    params = urllib.parse.urlencode({
        "q": query, "defType": parser, "rows": 0, "wt": "json"})
    url = "{}/{}/select?{}".format(solr_url.rstrip("/"), core, params)
    request = urllib.request.Request(url)
    if secret:
        request.add_header("X-Alfresco-Search-Secret", secret)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)["response"]["numFound"]


def write_result(path, payload):
    if not path:
        json.dump(payload, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")
    sys.stderr.write("written to {}\n".format(path))


def load_manifest(path):
    try:
        with open(path, encoding="utf-8") as handle:
            manifest = json.load(handle)
    except FileNotFoundError:
        sys.stderr.write(
            "no manifest at {}\n"
            "Phases c and d act on a tree generate_tree.py created: run phase a first, or\n"
            "point --manifest at the run directory that holds it.\n".format(path))
        raise SystemExit(2)
    except ValueError as error:
        sys.stderr.write("manifest {} is not valid JSON: {}\n".format(path, error))
        raise SystemExit(2)
    if "root_id" not in manifest:
        sys.stderr.write("manifest {} carries no root_id\n".format(path))
        raise SystemExit(2)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=("acl", "rename", "verify"))
    parser.add_argument("--manifest", default="manifest.json",
                        help="manifest written by generate_tree.py, for the root node id")
    parser.add_argument("--repo-url", default="http://localhost:8080/alfresco")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--out", default=None, help="where to write the result JSON")
    # acl
    parser.add_argument("--count", type=int, default=500,
                        help="acl: how many nodes to give a local permission to")
    parser.add_argument("--authority", default="GROUP_EVERYONE")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--folders-only", action="store_true",
                        help="acl: only put permissions on folders")
    # rename / verify
    parser.add_argument("--suffix", default=None,
                        help="rename: appended to the folder name (default: -r<epoch>)")
    parser.add_argument("--name", default=None,
                        help="verify: the folder name to count descendants of")
    parser.add_argument("--solr-url", default="http://localhost:8984/solr")
    parser.add_argument("--core", default="alfresco")
    parser.add_argument("--secret", default=os.environ.get("SEARCH_SECRET", "secret"))
    args = parser.parse_args(argv)

    manifest = load_manifest(args.manifest)
    root_id = manifest["root_id"]
    repository = Repository(args.repo_url, args.user, args.password, args.timeout)
    started = time.monotonic()

    if args.action == "acl":
        targets = discover_targets(repository, root_id, args.count, args.folders_only)
        if not targets:
            sys.stderr.write("no target found under {}\n".format(root_id))
            return 1
        failures = []

        def apply(node_id):
            try:
                set_local_permission(repository, node_id, args.authority)
            except RepositoryError as error:
                failures.append(str(error))

        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            for _ in pool.map(apply, targets):
                pass

        result = {"action": "acl", "root_id": root_id, "requested": args.count,
                  "applied": len(targets) - len(failures), "failed": len(failures),
                  "authority": args.authority, "folders_only": args.folders_only,
                  "elapsed_s": round(time.monotonic() - started, 3),
                  "first_failures": failures[:5]}
        write_result(args.out, result)
        return 1 if failures else 0

    if args.action == "rename":
        suffix = args.suffix or "-r{}".format(int(time.time()))
        new_name = manifest["root_name"] + suffix
        rename(repository, root_id, new_name)
        result = {"action": "rename", "root_id": root_id,
                  "previous_name": manifest["root_name"], "new_name": new_name,
                  "descendants_expected": manifest.get("nodes_total"),
                  "elapsed_s": round(time.monotonic() - started, 3)}
        write_result(args.out, result)
        return 0

    name = args.name or manifest["root_name"]
    query = 'PATH:"/app:company_home/cm:{}//*"'.format(name)
    try:
        matched = solr_count(args.solr_url, args.core, args.secret, query, "afts", args.timeout)
    except (urllib.error.URLError, OSError, KeyError, ValueError) as error:
        write_result(args.out, {"action": "verify", "query": query, "error": str(error)})
        return 1
    expected = manifest.get("nodes_total")
    result = {"action": "verify", "query": query, "matched": matched,
              "expected": expected,
              "complete": expected is not None and matched >= expected - 1}
    write_result(args.out, result)
    return 0 if result["complete"] else 2


if __name__ == "__main__":
    sys.exit(main())
