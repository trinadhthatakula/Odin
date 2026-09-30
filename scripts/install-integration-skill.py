#!/usr/bin/env python3
"""Install the canonical Odin integration skill into agent skill directories."""
import argparse
from pathlib import Path
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directories', nargs='*', type=Path, help='Agent skill roots; defaults to common user roots')
args = parser.parse_args()
source = Path(__file__).resolve().parents[1] / '.claude' / 'skills' / 'odin' / 'SKILL.md'
roots = args.directories or [Path.home() / p for p in ('.codex/skills', '.claude/skills', '.agents/skills', '.gemini/skills', '.gemini/config/skills')]
for root in roots:
    target = root.expanduser().resolve() / 'odin' / 'SKILL.md'
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.resolve() != target.resolve():
        shutil.copyfile(source, target)
    print(target)
