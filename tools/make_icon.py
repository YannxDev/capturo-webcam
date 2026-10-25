"""Génère les icônes Android à partir de l'icône commune de Capturo."""

from pathlib import Path

import gi

gi.require_version("GdkPixbuf", "2.0")
from gi.repository import GdkPixbuf  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "icon.svg"

densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, size in densities.items():
    target = ROOT / "res" / folder
    target.mkdir(parents=True, exist_ok=True)
    pixbuf = GdkPixbuf.Pixbuf.new_from_file_at_scale(
        str(SOURCE), size, size, True
    )
    pixbuf.savev(str(target / "ic_launcher.png"), "png", [], [])
    print(f"{folder}: {pixbuf.get_width()}×{pixbuf.get_height()}")

print("icônes générées depuis", SOURCE.name)
