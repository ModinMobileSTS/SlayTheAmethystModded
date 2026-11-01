#!/usr/bin/env python3
"""Export an Android PCK from a clean staging copy, without changing the upstream checkout."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "agent-tmp/godot-spike"

BOOTSTRAP = '''extends Node
var game: Node
var bridge: Object
var leaving := false

func _ready() -> void:
\tprocess_mode = Node.PROCESS_MODE_ALWAYS
\tif Engine.has_singleton("SlingPrototype"):
\t\tbridge = Engine.get_singleton("SlingPrototype")
\tif ResourceLoader.exists("res://scenes/main.tscn"):
\t\tgame = load("res://scenes/main.tscn").instantiate()
\t\tadd_child(game)
\telse:
\t\tvar label := Label.new()
\t\tlabel.text = "Godot external engine + Android PCK\\nDrag / tap the moving circle\\nSave and return to test persistence"
\t\tlabel.position = Vector2(20, 120)
\t\tadd_child(label)
\tif bridge: bridge.report("ready")

func _process(_delta: float) -> void:
\tif not bridge or leaving or not bridge.should_leave(): return
\tleaving = true
\tif game and not game.session.save():
\t\tbridge.report("save_failed")
\t\treturn
\tget_tree().paused = true
\tif game == null:
\t\tvar file := FileAccess.open("user://prototype-save.txt", FileAccess.WRITE)
\t\tif file == null:
\t\t\tbridge.report("save_failed")
\t\t\treturn
\t\tfile.store_string(str(Time.get_unix_time_from_system()))
\t\tfile.close()
\tbridge.report("saved")
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Upstream Slingbreak repository")
    parser.add_argument("--godot", default="godot")
    parser.add_argument("--minimal", action="store_true")
    args = parser.parse_args()
    version = subprocess.check_output([args.godot, "--version"], text=True).strip()
    if not version.startswith("4.7.2.stable."):
        parser.error("Requires the matching Godot 4.7.2 stable engine and Android templates")
    OUT.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix="pck-", dir=OUT))
    if not args.minimal:
        # Reuse the upstream whitelist and fingerprints; no upstream files are edited.
        import sys
        tools = args.source.resolve() / "godot/tools"
        sys.path.insert(0, str(tools))
        spec = importlib.util.spec_from_file_location("upstream_export", tools / "export_native.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.verify_assets()
        module.stage_project(stage)
    else:
        (stage / "project.godot").write_text('''config_version=5
[application]
config/name="Godot split prototype"
config/features=PackedStringArray("4.7", "GL Compatibility")
[display]
window/size/viewport_width=412
window/size/viewport_height=915
window/stretch/mode="canvas_items"
[rendering]
renderer/rendering_method="gl_compatibility"
''')
        presets = (args.source / "godot/export_presets.cfg").read_text()
        (stage / "export_presets.cfg").write_text(presets)
        (stage / "probe.gd").write_text('''extends Node2D
var t := 0.0
var point := Vector2(206, 500)
func _process(delta: float) -> void:
\tt += delta
\tqueue_redraw()
func _draw() -> void:
\tdraw_circle(point + Vector2(sin(t) * 60, cos(t) * 60), 35, Color.CORNFLOWER_BLUE)
func _input(event: InputEvent) -> void:
\tif event is InputEventScreenTouch or event is InputEventScreenDrag: point = event.position
''')
        BOOTSTRAP_MIN = BOOTSTRAP.replace('if bridge: bridge.report("ready")', 'var probe: Node = load("res://probe.gd").new()\n\tadd_child(probe)\n\tif bridge: bridge.report("ready")')
    # Native upstream owns its bridge and UI now. Never wrap it with a second
    # save/ready controller; keep injection only for minimal/legacy fixtures.
    if args.minimal or not (stage / "src/core/launcher.gd").is_file():
        bootstrap = BOOTSTRAP_MIN if args.minimal else BOOTSTRAP
        (stage / "prototype.gd").write_text(bootstrap)
        (stage / "prototype.tscn").write_text('''[gd_scene load_steps=2 format=3]
[ext_resource type="Script" path="res://prototype.gd" id="1"]
[node name="Prototype" type="Node"]
script = ExtResource("1")
''')
        project = (stage / "project.godot").read_text()
        import re
        project = re.sub(r'^run/main_scene=.*\n', '', project, flags=re.M)
        project = project.replace('[application]', '[application]\nrun/main_scene="res://prototype.tscn"')
        (stage / "project.godot").write_text(project)
    def run_engine(command):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        print(result.stdout, end="")
        if result.returncode or "SCRIPT ERROR:" in result.stdout or "ERROR:" in result.stdout:
            raise SystemExit("Godot reported an export/import error; refusing to publish a broken PCK")

    run_engine([args.godot, "--headless", "--path", str(stage), "--editor", "--import"])
    output = OUT / ("minimal.pck" if args.minimal else "game.pck")
    run_engine([args.godot, "--headless", "--path", str(stage), "--export-pack", "Android Preview", str(output)])
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".pck.sha256").write_text(f"{digest}  {output.name}\n")
    print(json.dumps({"pck": str(output), "bytes": output.stat().st_size, "sha256": digest, "stage": str(stage)}))


if __name__ == "__main__":
    main()
