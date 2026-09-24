"""Persistent project generation and read-only consistency validation."""
import hashlib
import json
import os
from pathlib import Path
import sys
import yaml

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'parser'))
from workflow_model import ModelError, compile_inputs, generate_agent, load_workflow, validate_agent

GENERATOR_FILES = ['project_artifacts.py', 'generate_project.py', 'parser/workflow_model.py',
                   'parser/model_transform.py', 'generator/controller_generic.asl']


def digest(path):
    # Git checkouts may use CRLF; text artifacts have identical semantics on both OSes.
    return hashlib.sha256(Path(path).read_text(encoding='utf-8').encode('utf-8')).hexdigest()


def paths(project):
    project = Path(project).resolve()
    return project / 'models/03_workflow_model.yaml', project / 'bdi/controller_agent.asl', project / 'models/generation-manifest.json'


def generate(project, pipeline, goal, policy=None, bindings=None):
    project = Path(project).resolve()
    workflow, agent, manifest = paths(project)
    policy = Path(policy) if policy is not None else project / 'config/controller_policy.yaml'
    bindings = Path(bindings) if bindings is not None else project / 'config/runtime_bindings.yaml'
    sources = dict(pipeline=pipeline, goal=goal, policy=policy, bindings=bindings)
    document, _ = compile_inputs(**dict(pipeline=pipeline, goals=goal, policy=policy, bindings=bindings))
    workflow.parent.mkdir(parents=True, exist_ok=True)
    agent.parent.mkdir(parents=True, exist_ok=True)
    workflow.write_text('# Generated from 01_pipeline.yaml, 02_goal.yaml, controller_policy.yaml and runtime_bindings.yaml; do not edit.\n' + yaml.safe_dump(document, sort_keys=False), encoding='utf-8', newline='\n')
    generate_agent(workflow, ROOT / 'generator/controller_generic.asl', agent)
    record = {'schema_version': 2,
              'inputs': {name: {'path': os.path.relpath(Path(path).resolve(), project).replace('\\', '/'),
                                'sha256': digest(path)} for name, path in sources.items()},
              'generator_sha256': {name: digest(ROOT / name) for name in GENERATOR_FILES},
              'workflow_sha256': digest(workflow), 'generated_agent_sha256': digest(agent)}
    # Written last: partial/interrupted generation will fail consistency validation.
    manifest.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8', newline='\n')
    validate(project)
    return workflow, agent, manifest


def validate(project):
    project = Path(project).resolve()
    workflow, agent, manifest = paths(project)
    try:
        record = json.loads(manifest.read_text(encoding='utf-8'))
        if record['schema_version'] != 2:
            raise ModelError('unsupported generation manifest')
        inputs = {}
        if set(record['inputs']) != {'pipeline','goal','policy','bindings'}:
            raise ModelError('Four source inputs are required in generation provenance')
        for name in ('pipeline', 'goal', 'policy', 'bindings'):
            entry = record['inputs'][name]
            inputs[name] = project / entry['path']
            if digest(inputs[name]) != entry['sha256']:
                raise ModelError(f'{name} input changed')
        if record['generator_sha256'] != {name: digest(ROOT / name) for name in GENERATOR_FILES}:
            raise ModelError('generator or generic policy changed')
        for path, key in [(workflow, 'workflow_sha256'), (agent, 'generated_agent_sha256')]:
            if digest(path) != record[key]:
                raise ModelError(f'{path.name} changed')
        document, model = load_workflow(workflow)
        if document != compile_inputs(inputs['pipeline'], inputs['goal'], inputs['policy'], inputs['bindings'])[0]:
            raise ModelError('workflow disagrees with engineer inputs')
        validate_agent(workflow, ROOT / 'generator/controller_generic.asl', agent)
        return document, model, record, inputs
    except (OSError, ValueError, KeyError, TypeError, ModelError) as error:
        raise ModelError(f'Missing, stale or inconsistent project artifacts: {error}. '
                         'Run python bdi-cicd-framework/generate_project.py with the intended '
                         '--project-dir, --pipeline, --goal, --policy and --bindings before starting a campaign.') from error
