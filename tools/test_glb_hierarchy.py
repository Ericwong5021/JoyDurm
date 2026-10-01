"""Validate the shipped GLB hierarchy and transformed pedal alignment with stdlib only."""
import copy
import json
import math
from pathlib import Path
import struct
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "app/src/main/assets/models/joydurm-kit.glb"


def identity():
    return [[float(r == c) for c in range(4)] for r in range(4)]


def multiply(a, b):
    return [[sum(a[r][k] * b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]


def transform(node):
    if "matrix" in node:
        return [[node["matrix"][c * 4 + r] for c in range(4)] for r in range(4)]
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    rotation = [
        [1 - 2*y*y - 2*z*z, 2*x*y - 2*z*w, 2*x*z + 2*y*w, 0],
        [2*x*y + 2*z*w, 1 - 2*x*x - 2*z*z, 2*y*z - 2*x*w, 0],
        [2*x*z - 2*y*w, 2*y*z + 2*x*w, 1 - 2*x*x - 2*y*y, 0],
        [0, 0, 0, 1],
    ]
    for r in range(3):
        for c in range(3):
            rotation[r][c] *= node.get("scale", [1, 1, 1])[c]
        rotation[r][3] = node.get("translation", [0, 0, 0])[r]
    return rotation


class GlbHierarchyTest(unittest.TestCase):
    def setUp(self):
        data = MODEL.read_bytes()
        self.assertEqual(struct.unpack_from("<III", data), (0x46546C67, 2, len(data)))
        length, tag = struct.unpack_from("<II", data, 12)
        self.assertEqual(tag, 0x4E4F534A)
        self.model = json.loads(data[20:20 + length])
        self.nodes = self.model["nodes"]
        self.names = {node.get("name"): i for i, node in enumerate(self.nodes)}
        self.parents = {}
        for parent, node in enumerate(self.nodes):
            for child in node.get("children", []):
                self.assertNotIn(child, self.parents, "A node has multiple parents")
                self.parents[child] = parent

    def world(self, index, nodes=None):
        nodes = self.nodes if nodes is None else nodes
        local = transform(nodes[index])
        parent = self.parents.get(index)
        return local if parent is None else multiply(self.world(parent, nodes), local)

    def center(self, name, nodes=None):
        matrix = self.world(self.names[name], nodes)
        return [matrix[r][3] for r in range(3)]

    def assert_vector_close(self, actual, expected):
        for a, e in zip(actual, expected):
            self.assertAlmostEqual(a, e, places=7)

    def test_beater_is_kick_child_and_keeps_original_world_placement(self):
        self.assertEqual(self.parents[self.names["beater"]], self.names["kick"])
        self.assert_vector_close(self.center("beater"), [0, 0.08, -0.06])
        self.assertEqual(self.parents[self.names["beater_arm"]], self.names["beater"])
        self.assertEqual(self.parents[self.names["beater_head"]], self.names["beater"])

    def test_kick_translation_rotation_scale_preserve_pedal_alignment(self):
        kick = self.names["kick"]
        old_kick = self.world(kick)
        old_pedal = self.center("beater_head")
        old_skin = self.center("kick_head")
        local_pedal = [old_pedal[i] - old_kick[i][3] for i in range(3)]
        local_skin = [old_skin[i] - old_kick[i][3] for i in range(3)]
        for degrees in (0, 90, 167):
            for scale in (0.3, 1, 2):
                nodes = copy.deepcopy(self.nodes)
                angle = math.radians(degrees) / 2
                nodes[kick] = dict(nodes[kick], translation=[1.3, 0.6, -2.1],
                    rotation=[0, math.sin(angle), 0, math.cos(angle)], scale=[scale]*3)
                nodes[kick].pop("matrix", None)
                matrix = self.world(kick, nodes)
                for name, point in (("beater_head", local_pedal), ("kick_head", local_skin)):
                    expected = [sum(matrix[r][c]*point[c] for c in range(3)) + matrix[r][3] for r in range(3)]
                    self.assert_vector_close(self.center(name, nodes), expected)
                distance = lambda a, b: math.sqrt(sum((x-y)**2 for x, y in zip(a, b)))
                self.assertAlmostEqual(distance(self.center("beater_head", nodes), self.center("kick_head", nodes)),
                    distance(old_pedal, old_skin)*scale, places=7)

    def test_eight_drum_roots_are_unique_independent_and_articulated(self):
        drums = {self.names[name] for name in ("kick", "snare", "tom1", "tom2", "floor", "hat", "crash", "ride")}
        for name, index in self.names.items():
            if index not in drums:
                continue
            self.assertEqual(sum(node.get("name") == name for node in self.nodes), 1)
            parent = self.parents.get(index)
            while parent is not None:
                self.assertNotIn(parent, drums)
                parent = self.parents.get(parent)
            self.assertTrue(self.nodes[index].get("children"))
        self.assertIn("hat_cymbal", self.names)
        self.assertIn("kick_head", self.names)


if __name__ == "__main__":
    unittest.main()
