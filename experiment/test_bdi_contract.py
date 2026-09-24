"""Gotify source ownership, correlation, fairness and generation regression tests."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK = ROOT / 'bdi-cicd-framework'
sys.path.insert(0, str(FRAMEWORK))
sys.path.insert(0, str(FRAMEWORK / 'parser'))
from project_artifacts import validate
from workflow_model import read, compile_sources, ModelError

class GotifyContractTest(unittest.TestCase):
    def setUp(self):
        self.sources = [read(FRAMEWORK / path) for path in
            ('models/01_pipeline.yaml', 'models/02_goal.yaml', 'config/controller_policy.yaml', 'config/runtime_bindings.yaml')]

    def test_generated_agent_matches_all_four_inputs(self):
        document, model, manifest, inputs = validate(FRAMEWORK)
        self.assertEqual(document['bindings']['project'], 'gotify')
        self.assertEqual(set(inputs), {'pipeline', 'goal', 'policy', 'bindings'})
        self.assertEqual(list(model.entities), ['build', 'test', 'package', 'prepare', 'staging', 'production', 'rollback'])
        self.assertNotIn('payment_', json.dumps(document))

    def test_shared_limits_and_thresholds_match(self):
        common = json.loads((ROOT / 'experiment/policy.json').read_text())
        policy = self.sources[2]
        for key in ('observation_attempts', 'observation_interval_seconds', 'observation_timeout_seconds', 'healthy_observations'):
            self.assertEqual(common[key], policy['execution'][key], key)
        for key in ('error_rate_high_gt', 'latency_p95_ms_high_gt'):
            self.assertEqual(common[key], policy['telemetry_constraints'][key], key)
        self.assertEqual(common['restart_attempts'], policy['candidate_repair']['production']['max_attempts'])
        self.assertEqual(common['recovery_timeout_seconds'], policy['candidate_repair']['production']['deadline_seconds'])

    def test_uncorrelated_probe_is_rejected(self):
        for marker in ('{{repo_root}}', '{{trial_id}}', '{{release}}', '{{execution_id}}'):
            sources = deepcopy(self.sources)
            binding = sources[3]['telemetry']['environments']['production']
            binding['probe_command'] = [s.replace(marker, 'unqualified') for s in binding['probe_command']]
            with self.subTest(marker=marker), self.assertRaises(ModelError): compile_sources(*sources)

    def test_unverified_or_retried_rollback_is_rejected(self):
        for field, value in (('verify_health', False), ('retryable', True), ('terminal_on_success', 'achieved')):
            sources = deepcopy(self.sources)
            sources[2]['recovery_policy']['rollback'][field] = value
            with self.subTest(field=field), self.assertRaises(ModelError): compile_sources(*sources)

    def test_restart_budget_cannot_silently_expand(self):
        sources = deepcopy(self.sources)
        sources[2]['candidate_repair']['production']['max_attempts'] = 2
        with self.assertRaises(ModelError): compile_sources(*sources)

if __name__ == '__main__':
    unittest.main()
