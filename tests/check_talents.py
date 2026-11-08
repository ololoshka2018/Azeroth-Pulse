import json
import re
from collections import Counter
from pathlib import Path


CATALOG = Path(__file__).resolve().parents[1] / "app/src/main/assets/ui/talents-data.json"
CLASSES = {"Warrior", "Paladin", "Hunter", "Rogue", "Priest", "Shaman", "Mage", "Warlock", "Druid"}
GOALS = {"leveling", "dungeons", "raid", "pvp"}


def check_allocation(order, nodes, title):
    ranks = Counter()
    for level, node_id in enumerate(order, 10):
        tree, node = nodes[node_id]
        prefix = f"{title}, level {level}, {node['en']}"
        assert ranks[node_id] < node["max"], (prefix, "exceeds max rank")
        previous = sum(count for spent, count in ranks.items()
                       if nodes[spent][0] == tree and nodes[spent][1]["row"] < node["row"])
        assert previous >= 5 * node["row"], (prefix, "tier is locked")
        if prerequisite := node.get("req"):
            assert ranks[prerequisite] == nodes[prerequisite][1]["max"], (prefix, "missing prerequisite")
        ranks[node_id] += 1
    return ranks


def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    assert set(catalog["classes"]) == CLASSES
    atlas = catalog["atlas"]
    total = 0
    for name, class_data in catalog["classes"].items():
        trees = class_data["trees"]
        assert len(trees) == 3
        nodes = {}
        for tree_index, tree in enumerate(trees):
            cells = set()
            for node in tree["nodes"]:
                node_id, row, col = node["id"], node["row"], node["col"]
                assert node_id not in nodes and (row, col) not in cells, (name, node)
                assert 0 <= row <= 6 and 0 <= col <= 3 and 1 <= node["max"] <= 5, node
                assert 0 <= node["icon"] < atlas["rows"] * atlas["cols"], node
                assert len(node["effects"]) == node["max"], node["en"]
                for effect in node["effects"]:
                    assert re.search(r"[А-Яа-яЁё]", effect["text"]), node["en"]
                    assert re.fullmatch(r"[0-9a-f]{64}", effect["fingerprint"]), node["en"]
                cells.add((row, col))
                nodes[node_id] = (tree_index, node)
        assert all(node.get("req", node_id) in nodes for node_id, (_, node) in nodes.items())
        builds = class_data["builds"]
        assert {build["goal"] for build in builds} == GOALS, name
        for build in builds:
            title = f"{name} / {build['title']}"
            if build["goal"] == "leveling":
                order = build["order"]
            else:
                ranks = {int(node): count for node, count in build["ranks"].items()}
                assert all(node in nodes and count > 0 for node, count in ranks.items()), title
                ordered = sorted(nodes, key=lambda node: (nodes[node][0], nodes[node][1]["row"], nodes[node][1]["col"]))
                order = [node for node in ordered for _ in range(ranks.get(node, 0))]
            assert len(order) == 51, title
            spent = check_allocation(order, nodes, title)
            split = [sum(count for node, count in spent.items() if nodes[node][0] == tree) for tree in range(3)]
            assert split == build["split"], title
            total += 1
    assert set(catalog["stats"]["classes"]) == CLASSES
    for entry in catalog["stats"]["classes"].values():
        assert 0 < sum(entry["specs"].values()) <= entry["sample"]
    print(f"Validated {total} builds across {len(CLASSES)} classes.")


if __name__ == "__main__":
    main()
