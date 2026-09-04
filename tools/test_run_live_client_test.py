from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from tools import run_live_client_test as runner


class ScenarioValidationTest(unittest.TestCase):
    def test_rejects_executable_or_unknown_operations(self) -> None:
        with self.assertRaises(runner.ScenarioError):
            runner.validate_scenario({"steps": [{"op": "python", "code": "pass"}]})
        with self.assertRaises(runner.ScenarioError):
            runner.validate_scenario({"steps": [{"op": "checkpoint", "shell": "true"}]})

    def test_accepts_every_declared_scenario(self) -> None:
        for path in Path("tools/scenarios").glob("*.json"):
            scenario, digest = runner.canonical_scenario(path)
            self.assertTrue(scenario["steps"])
            self.assertEqual(64, len(digest))


class EvidenceTest(unittest.TestCase):
    def test_atomic_json_replaces_complete_document(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.json"
            runner.atomic_json(path, {"status": "PASS"})
            self.assertEqual({"status": "PASS"}, json.loads(path.read_text()))
            self.assertFalse(path.with_name("result.json.tmp").exists())

    def test_partial_jsonl_is_not_exposed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            events = directory / "events.jsonl"
            events.write_text('{"result":{"stepId":1', encoding="utf-8")
            reader = runner.EvidenceReader(directory)
            self.assertEqual([], reader.poll())
            with events.open("a", encoding="utf-8") as output:
                output.write(',"kind":"CHECKPOINT_RESULT"}}\n')
            records = reader.poll()
            self.assertEqual(1, len(records))


class AggregateTest(unittest.TestCase):
    def test_preserves_outlier_and_excludes_warmup(self) -> None:
        warmup = runner.RunReport("warm", True, status="FAIL", failure="TIMEOUT: warm")
        first = runner.RunReport("one", False, status="PASS",
                                 timings_ms={"pose": [1.0, 2.0]},
                                 snapshots=[{"active": 1}])
        second = runner.RunReport("two", False, status="PASS",
                                  timings_ms={"pose": [1000.0]},
                                  snapshots=[{"active": 500}])
        result = runner.aggregate([warmup, first, second])
        self.assertEqual(0, result["failureCount"])
        self.assertEqual([1.0, 2.0, 1000.0], result["timingsMs"]["pose"]["all"])
        self.assertEqual(1000.0, result["timingsMs"]["pose"]["max"])
        self.assertEqual(500, result["resources"]["active"]["max"])


class ExitCodeTest(unittest.TestCase):
    def invoke_with(self, status: str) -> int:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scenario = root / "scenario.json"
            scenario.write_text('{"steps":[{"op":"checkpoint"}]}', encoding="utf-8")

            class FakeRun:
                def __init__(self, *args, **kwargs):
                    pass

                def execute(self):
                    failure = None if status == "PASS" else f"{status}: injected"
                    return runner.RunReport("run", False, status=status, failure=failure)

            with mock.patch.object(runner, "TmuxConsole"), \
                    mock.patch.object(runner, "ScenarioRun", FakeRun):
                return runner.main(["--player", "Test", "--scenario", str(scenario),
                                    "--output", str(root)])

    def test_pass_is_zero(self) -> None:
        self.assertEqual(0, self.invoke_with("PASS"))

    def test_assertion_failure_and_timeout_are_nonzero(self) -> None:
        self.assertEqual(1, self.invoke_with("FAIL"))
        self.assertEqual(1, self.invoke_with("TIMEOUT"))

    def test_invalid_scenario_is_configuration_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            scenario = Path(temporary) / "bad.json"
            scenario.write_text('{"steps":[{"op":"shell"}]}', encoding="utf-8")
            self.assertEqual(2, runner.main([
                "--player", "Test", "--scenario", str(scenario),
                "--output", temporary,
            ]))


if __name__ == "__main__":
    unittest.main()
