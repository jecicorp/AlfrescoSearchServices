#!/usr/bin/env python3
"""Tree shapes used by the large-folder indexing benchmark.

A profile turns a target node count into a flat build plan: a list of
``PlannedNode`` ordered so that a parent always precedes its children.
Index ``0`` is the benchmark root folder itself and is never part of the plan.
"""

from collections import deque
from dataclasses import dataclass

# (share of the files, number of folders holding them, depth of those folders)
MIXED_SHAPE = (
    (0.30, 1, 1),
    (0.40, 40, 3),
    (0.30, 400, 6),
)

DEEP_BREADTH = 6
DEEP_FILES_PER_FOLDER = 40


@dataclass(frozen=True)
class PlannedNode:
    index: int
    parent: int
    name: str
    is_folder: bool


def _folder_name(index):
    return "f-{:06d}".format(index)


def _file_name(index):
    return "n-{:06d}.txt".format(index)


def flat(total):
    return [PlannedNode(i, 0, _file_name(i), False) for i in range(1, total + 1)]


def deep(total, breadth=DEEP_BREADTH, files_per_folder=DEEP_FILES_PER_FOLDER):
    folder_count = max(1, round(total / (files_per_folder + 1)))
    file_count = max(0, total - folder_count)

    nodes = []
    holders = []
    parents = deque([0])
    index = 1

    while len(holders) < folder_count:
        parent = parents.popleft()
        for _ in range(breadth):
            if len(holders) >= folder_count:
                break
            nodes.append(PlannedNode(index, parent, _folder_name(index), True))
            holders.append(index)
            parents.append(index)
            index += 1

    for position in range(file_count):
        parent = holders[position % len(holders)]
        nodes.append(PlannedNode(index, parent, _file_name(index), False))
        index += 1

    return nodes


def mixed(total, shape=MIXED_SHAPE):
    folder_count = sum((depth - 1) + count for _, count, depth in shape)
    file_count = max(0, total - folder_count)

    nodes = []
    index = 1
    groups = []

    for _, count, depth in shape:
        parent = 0
        for _ in range(depth - 1):
            nodes.append(PlannedNode(index, parent, _folder_name(index), True))
            parent = index
            index += 1
        holders = []
        for _ in range(count):
            nodes.append(PlannedNode(index, parent, _folder_name(index), True))
            holders.append(index)
            index += 1
        groups.append(holders)

    assigned = 0
    for position, (share, _, _) in enumerate(shape):
        is_last = position == len(shape) - 1
        group_files = file_count - assigned if is_last else round(share * file_count)
        holders = groups[position]
        for offset in range(group_files):
            parent = holders[offset % len(holders)]
            nodes.append(PlannedNode(index, parent, _file_name(index), False))
            index += 1
        assigned += group_files

    return nodes


PROFILES = {
    "flat": flat,
    "deep": deep,
    "mixed": mixed,
}


def build(profile, total):
    if profile not in PROFILES:
        raise ValueError("unknown profile {!r}; known: {}".format(
            profile, ", ".join(sorted(PROFILES))))
    return PROFILES[profile](total)


def describe(nodes):
    folders = sum(1 for node in nodes if node.is_folder)
    depth_of = {0: 0}
    max_depth = 0
    widest = {}
    for node in nodes:
        depth_of[node.index] = depth_of[node.parent] + 1
        max_depth = max(max_depth, depth_of[node.index])
        widest[node.parent] = widest.get(node.parent, 0) + 1
    return {
        "nodes": len(nodes),
        "folders": folders,
        "files": len(nodes) - folders,
        "max_depth": max_depth,
        "max_children_in_one_folder": max(widest.values()) if widest else 0,
    }
