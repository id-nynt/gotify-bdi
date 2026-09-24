"""Validate engineer inputs and explicitly regenerate persistent project artifacts."""
import argparse
from pathlib import Path
import sys
from project_artifacts import ROOT, ModelError, generate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-dir', type=Path, default=ROOT)
    parser.add_argument('--pipeline', type=Path)
    parser.add_argument('--goal', type=Path)
    parser.add_argument('--policy', type=Path)
    parser.add_argument('--bindings', type=Path)
    args = parser.parse_args()
    for path in generate(args.project_dir, args.pipeline or args.project_dir/'models/01_pipeline.yaml',
                         args.goal or args.project_dir/'models/02_goal.yaml', args.policy, args.bindings):
        print(path)


if __name__ == '__main__':
    try:
        main()
    except (ModelError, OSError, ValueError) as error:
        print(f'Project generation failed: {error}', file=sys.stderr)
        raise SystemExit(2)
