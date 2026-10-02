#!/usr/bin/env python3
"""Generate the project's original, independently articulated CC0 drum kit using trimesh."""
from pathlib import Path
import math
import numpy as np
import trimesh
from trimesh.visual.material import PBRMaterial

ROOT = Path(__file__).resolve().parents[1]
scene = trimesh.Scene(base_frame="world")
materials = {
    "shell": PBRMaterial(name="Coral lacquer", baseColorFactor=[210, 76, 50, 255], metallicFactor=0.2, roughnessFactor=0.3),
    "chrome": PBRMaterial(name="Brushed chrome", baseColorFactor=[174, 189, 201, 255], metallicFactor=0.9, roughnessFactor=0.24),
    "skin": PBRMaterial(name="Coated drumhead", baseColorFactor=[229, 230, 220, 255], metallicFactor=0.0, roughnessFactor=0.75),
    "brass": PBRMaterial(name="Hammered brass", baseColorFactor=[211, 162, 54, 255], metallicFactor=0.85, roughnessFactor=0.28),
    "black": PBRMaterial(name="Rubber", baseColorFactor=[28, 33, 38, 255], metallicFactor=0.0, roughnessFactor=0.9),
}


def node(name, parent="world", position=(0, 0, 0), rx=0):
    transform = trimesh.transformations.translation_matrix(position)
    if rx:
        transform = transform @ trimesh.transformations.rotation_matrix(math.radians(rx), [1, 0, 0])
    scene.graph.update(frame_to=name, frame_from=parent, matrix=transform)


def cylinder(name, parent, radius, height, position=(0, 0, 0), material="chrome", rx=0):
    # trimesh cylinders are Z-up; the model coordinate system is Y-up, meters.
    mesh = trimesh.creation.cylinder(radius=radius, height=height, sections=48)
    mesh.apply_transform(trimesh.transformations.rotation_matrix(-math.pi / 2, [1, 0, 0]))
    mesh.visual = trimesh.visual.TextureVisuals(material=materials[material])
    transform = trimesh.transformations.translation_matrix(position)
    if rx:
        transform = transform @ trimesh.transformations.rotation_matrix(math.radians(rx), [1, 0, 0])
    scene.add_geometry(mesh, node_name=name, geom_name=name, parent_node_name=parent, transform=transform)


def tripod(parent, height):
    cylinder(parent + "_stand", parent, 0.012, height, (0, -height / 2, 0))
    for k in range(3):
        angle = k * math.tau / 3
        start = np.array([0, -height + 0.17, 0])
        end = np.array([math.cos(angle) * 0.23, -height + 0.02, math.sin(angle) * 0.23])
        mesh = trimesh.creation.cylinder(radius=0.009, segment=[start, end], sections=12)
        mesh.visual = trimesh.visual.TextureVisuals(material=materials["chrome"])
        scene.add_geometry(mesh, node_name=f"{parent}_leg{k}", geom_name=f"{parent}_leg{k}", parent_node_name=parent)


parts = [
    ("kick", (0, 0.34, -0.38), 0.34, 0.46),
    ("snare", (-0.28, 0.70, 0.44), 0.22, 0.16),
    ("tom1", (-0.28, 1.0, -0.25), 0.19, 0.22),
    ("tom2", (0.25, 1.0, -0.25), 0.21, 0.24),
    ("floor", (0.73, 0.65, 0.35), 0.27, 0.35),
]
for name, p, radius, height in parts:
    node(name, position=p)
    node(name + "_body", name, rx=90 if name == "kick" else 0)
    body = name + "_body"
    cylinder(name + "_shell", body, radius, height, material="shell")
    cylinder(name + "_head", body, radius * 1.008, 0.018, (0, height/2+0.009, 0), material="skin")
    cylinder(name + "_bottom", body, radius * 1.01, 0.022, (0, -height/2, 0), material="chrome")
    for k in range(8):
        a = k * math.tau / 8
        cylinder(f"{name}_lug{k}", body, 0.009, height, (math.sin(a)*radius, 0, math.cos(a)*radius))
    if name != "kick":
        tripod(name, p[1])

for name, p, radius in [("hat", (-0.85, 0.95, 0.42), 0.23), ("crash", (-0.74, 1.42, -0.48), 0.33), ("ride", (0.83, 1.23, -0.38), 0.35)]:
    node(name, position=p)
    tripod(name, p[1])
    node(name + "_cymbal", name)
    cymbal = name + "_cymbal"
    # Profile-driven lathe uses trimesh's existing revolve primitive.
    profile = np.array([[0.008, 0], [radius*0.12, 0.04], [radius*0.26, 0.012], [radius, 0], [radius, -0.004], [0.008, -0.004]])
    mesh = trimesh.creation.revolve(profile, sections=64)
    mesh.apply_transform(trimesh.transformations.rotation_matrix(-math.pi/2, [1, 0, 0]))
    mesh.visual = trimesh.visual.TextureVisuals(material=materials["brass"])
    scene.add_geometry(mesh, node_name=name + "_surface", geom_name=name + "_surface", parent_node_name=cymbal)
    cylinder(name + "_cap", cymbal, 0.017, 0.021, (0, 0.045, 0), material="black")
    if name == "hat":
        lower = mesh.copy()
        lower.apply_translation([0, -0.024, 0])
        scene.add_geometry(lower, node_name="hat_lower", geom_name="hat_lower", parent_node_name=name)

# Keep the original world placement while making the complete pedal assembly
# inherit every kick translation, rotation and scale. Local = world - kick.
node("beater", parent="kick", position=(0, 0.08 - 0.34, -0.06 - (-0.38)))
cylinder("beater_arm", "beater", 0.011, 0.3, (0, 0.15, 0))
cylinder("beater_head", "beater", 0.045, 0.055, (0, 0.31, 0), material="skin", rx=90)
out = ROOT / "app/src/main/assets/models/joydurm-kit.glb"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_bytes(scene.export(file_type="glb"))
print(f"Generated {out} ({out.stat().st_size:,} bytes, {len(scene.geometry)} meshes)")
