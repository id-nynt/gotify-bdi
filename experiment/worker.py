#!/usr/bin/env python3
"""Execute one Jason-selected entity. This file never selects a successor or recovery."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from artifacts import download

ROOT = Path(__file__).resolve().parents[1]
SOURCE = '74b75e931e61e44b72eb9cfeb8489e61f60b5ace'

def command(argv):
    subprocess.run(argv, cwd=ROOT, check=True)

def main():
    entity = os.environ['ENTITY']
    trial = os.environ['CAMPAIGN_ID']
    execution = os.environ['EXECUTION_ID']
    for identifier in (trial, execution):
        if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', identifier): raise ValueError('Invalid operation identity')
    if os.environ['RELEASE_SHA'] != SOURCE: raise ValueError('Unexpected Gotify application revision')
    if os.environ.get('FAILURE_MODE', 'none') != 'none' or os.environ.get('EXPERIMENT_MODE', 'normal') != 'normal':
        raise ValueError('Fault injection belongs to the external neutral harness')
    if os.environ.get('ATTEMPT', '1') != '1': raise ValueError('No unconfigured operation retry')
    public = ROOT / 'experiment/build/worker-evidence'
    public.mkdir(parents=True, exist_ok=True)
    intent = {'entity': entity, 'trial': trial, 'execution_id': execution, 'source_sha': SOURCE}
    (public / 'intent.json').write_text(json.dumps(intent, indent=2))

    def operation(action, environment=None, release='v2', target=None, extra=()):
        argv = [sys.executable, str(ROOT / 'experiment/operations.py'), action,
                '--approach', 'bdi', '--trial', trial, '--release', release, *extra]
        if environment: argv.extend(['--environment', environment])
        if target: argv.extend(['--execution-id', target])
        result = subprocess.run(argv, text=True, capture_output=True, timeout=240)
        print(result.stdout, end='', flush=True)
        if result.stderr: print(result.stderr, file=sys.stderr)
        receipt = json.loads(result.stdout)
        (public / ('operation-' + receipt['operation_id'] + '.json')).write_text(json.dumps(receipt, indent=2))
        if 'repair_evidence' in receipt:
            (public / 'receipt.json').write_text(json.dumps(receipt['repair_evidence'], indent=2))
        if result.returncode: raise RuntimeError('Requested operation failed')
        return receipt

    if entity == 'build':
        command(['make', 'build-js'])
    elif entity == 'test':
        download('ui-' + trial, ROOT / 'ui/build')
        for argv in (['go', 'mod', 'download'], ['make', 'download-tools'], ['make', 'test'], ['make', 'check-ci']):
            command(argv)
        command([sys.executable, '-m', 'pip', 'install', '--user', 'PyYAML==6.0.2'])
        command([sys.executable, '-m', 'unittest', 'discover', '-s', 'experiment', '-p', 'test_*.py', '-v'])
    elif entity == 'package':
        download('ui-' + trial, ROOT / 'ui/build')
        command(['bash', 'experiment/build_images.sh'])
    elif entity == 'prepare':
        download('release-' + trial, ROOT / 'experiment/build')
        operation('prepare', extra=['--manifest', str(ROOT / 'experiment/build/images.json')])
        # Mechanical baseline fixture restoration; no candidate deployment or recovery choice.
        for environment in ('staging', 'production'):
            operation('reset', environment, 'v1', execution + '-' + environment)
            operation('probe', environment, 'v1', execution + '-' + environment)
    elif entity in ('staging', 'production'):
        operation('deploy', entity, 'v2', execution)
    elif entity == 'rollback':
        operation('rollback', 'production', 'v1', execution)
    elif entity in ('diagnose_production', 'restart_production'):
        target = os.environ.get('TARGET_EXECUTION_ID', '')
        if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', target): raise ValueError('Missing diagnosis target')
        binding = json.loads(os.environ['DIAGNOSTIC_BINDING'])
        if binding != {'compose_project': 'gotify-bdi-production', 'app_service': 'gotify', 'database_kind': 'sqlite'}:
            raise ValueError('Unexpected diagnostic binding')
        operation('diagnose' if entity == 'diagnose_production' else 'restart', 'production', 'v2', target)
    else:
        raise ValueError('Unknown requested entity')
    (public / 'result.json').write_text(json.dumps({**intent, 'status': 'PASS'}, indent=2))

if __name__ == '__main__':
    main()
