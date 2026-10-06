from __future__ import annotations

import copy
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from tools import run_live_client_test as runner


# Independent valid inputs exercise every allowed field, including fields ignored by
# a particular assertion mode. These expectations do not read the production table.
VALID_OPERATION_STEPS = {
    "pose": {"op": "pose", "dimension": "minecraft:overworld", "x": 0.0, "y": 100.0,
             "z": 0.0, "yaw": 90.0, "pitch": -45.0, "timeout_ms": 1000},
    "hold": {"op": "hold", "duration_ms": 1000, "cadence_ms": 100},
    "trace": {"op": "trace", "duration_ms": 1000, "cadence_ms": 100},
    "wait_until": {"op": "wait_until", "field": "active", "comparison": "==", "value": 3,
                   "timeout_ms": 1000, "cadence_ms": 100},
    "checkpoint": {"op": "checkpoint", "name": "saved"},
    "screenshot": {"op": "screenshot"},
    "assert": {"op": "assert", "mode": "delta", "field": "active", "comparison": ">=",
               "value": 1, "from": "before", "to": "after", "direction": "nondecreasing"},
    "reconnect_quic": {"op": "reconnect_quic"},
    "hold_quic": {"op": "hold_quic"},
    "resume_quic": {"op": "resume_quic"},
    "shader_reload": {"op": "shader_reload"},
    "shaders_on": {"op": "shaders_on"},
    "shaders_off": {"op": "shaders_off"},
    "shader_reload_all_changed": {"op": "shader_reload_all_changed"},
    "shader_option": {"op": "shader_option", "option": "TAA", "value": "true"},
    "zoom_in": {"op": "zoom_in"},
    "zoom_out": {"op": "zoom_out"},
    "zoom_max": {"op": "zoom_max"},
}

SCENARIO_HASHES = {
    "boundary_water_observation.json": "a6ca3ec259469c5c3c146e1db8154b19f97219e1f07f66cb601dac142a80baaa",
    "cache_start_populate.json": "c3cf023ad7bbbf182b433245164d3d8a00b259bdb651da896c0fd927391793a4",
    "cache_start_verify.json": "e44f0d7bec30aabbd1c126359ccdf74976e9cba02932e38f8e63027f783ad2fa",
    "camera_turn_response.json": "9add09e66ed397d7dd2b9e2b9b37b61a745126a3c9e586ae77f7085b90f23bc2",
    "deterministic_sweep.json": "e16e905baa96df34ffe9f81c2dacf83e6f21e4cbcc3f0a591bccb5ea9b5bdf4e",
    "dormancy_pressure_look_away.json": "c0b6e5f27d891bf6293f6e796df2f4427df461ee8067df17359ce9ce10a4b06a",
    "harness_smoke.json": "24e2bb4e334fda2f41afcc6f0a04a5b19468bec202def3039fb83512733d1881",
    "ok_zoomer_maximum.json": "da5484b054aa7de161497b1998c489715f8f91f98ccca5e27e7f4182f2ceef04",
    "ok_zoomer_zoom_cycle.json": "c27584123f1fe820f17f43a5231455271a97e9bb81b7649c9c08734f2dff9178",
    "quick_return.json": "1a68833982fb94ac9a43862c75615f7c1372dd918339ce9bb5753fc93f3da395",
    "reconnect_continuity.json": "5178ab5c3298a0ca14e589e60b00323a40a333be3d0d92b1233ae892020f225c",
    "regional_control_recovery.json": "0172340a8f1ea668032b495769d82bef463e84ac96ac6b532927c67fbc6bc79f",
    "shader_enable_variants.json": "fc7f4b4e1ee3fe26a7d09bda8079d3c5f9fee1a738c0e237a7db6da63cde97f4",
    "shader_reload_preservation.json": "81877084b9e90a40a97185efeeaa2278eb43ba43f377954bc7516d4733777a09",
    "stable_view_loading.json": "7530ecb4cbe032a214a39e4796897fabbfacbf3db58780daccf242109678132b",
}


class ScenarioValidationTest(unittest.TestCase):
    def test_all_operations_allow_exact_fields_and_reject_sorted_extras(self) -> None:
        self.assertIsInstance(runner.VALID_STEPS, set)
        self.assertEqual(set(VALID_OPERATION_STEPS), runner.VALID_STEPS)
        for name, step in VALID_OPERATION_STEPS.items():
            with self.subTest(operation=name):
                scenario = {"name": "complete", "steps": [copy.deepcopy(step)]}
                original = copy.deepcopy(scenario)
                runner.validate_scenario(scenario)
                self.assertEqual(original, scenario)
                invalid = {"steps": [{**step, "extra_z": 1, "extra_a": 2}]}
                original = copy.deepcopy(invalid)
                with self.assertRaises(runner.ScenarioError) as caught:
                    runner.validate_scenario(invalid)
                self.assertEqual("step 0 has unsupported fields: ['extra_a', 'extra_z']", str(caught.exception))
                self.assertEqual(original, invalid)

    def test_multiply_invalid_inputs_preserve_first_error(self) -> None:
        cases = [
            ({"steps": None, "extra": 1}, "scenario must be an object containing a steps array"),
            ({"steps": [{"op": "unknown"}], "z": 1, "a": 2}, "unsupported scenario fields: ['a', 'z']"),
            ({"steps": [{"op": "unknown"}], "name": 1}, "scenario name must be a string"),
            ({"steps": [], "initial_pose": {"extra": 1}}, "initial_pose.dimension must be a nonempty string"),
            ({"steps": [{"op": "pose", "extra": 1}]}, "step 0.dimension must be a nonempty string"),
            ({"steps": [{"op": "hold", "cadence_ms": 0, "extra": 1}]}, "step 0 requires positive duration_ms"),
            ({"steps": [{"op": "trace", "duration_ms": 1, "cadence_ms": 0, "extra": 1}]}, "step 0 requires positive cadence_ms"),
            ({"steps": [{"op": "wait_until", "field": 1, "comparison": "bad", "extra": 1}]}, "step 0.field has the wrong type"),
            ({"steps": [{"op": "wait_until", "field": "active", "comparison": "bad", "timeout_ms": 0}]}, "step 0 has an invalid comparison"),
            ({"steps": [{"op": "wait_until", "field": "active", "comparison": "==", "timeout_ms": 0}]}, "step 0 omits value"),
            ({"steps": [{"op": "wait_until", "field": "active", "comparison": "==", "value": 1, "timeout_ms": 0, "extra": 1}]}, "step 0 requires positive timeout_ms"),
            ({"steps": [{"op": "checkpoint", "name": 1, "extra": 1}]}, "step 0 checkpoint name must be a string"),
            ({"steps": [{"op": "shader_option", "option": "bad;name", "value": "bad;value", "extra": 1}]}, "step 0 has invalid shader option"),
            ({"steps": [{"op": "shader_option", "option": "TAA", "value": "bad;value", "extra": 1}]}, "step 0 has invalid shader value"),
            ({"steps": [{"op": "assert", "mode": "unknown", "field": 1, "extra": 1}]}, "step 0 has unsupported assertion mode"),
            ({"steps": [{"op": "assert", "field": 1, "comparison": "bad", "extra": 1}]}, "step 0.field has the wrong type"),
            ({"steps": [{"op": "assert", "field": "active", "comparison": "bad", "extra": 1}]}, "step 0 has incomplete assertion"),
            ({"steps": [{"op": "assert", "mode": "monotonicity", "field": "active", "direction": "bad", "extra": 1}]}, "step 0 has invalid monotonic direction"),
            ({"steps": [{"op": "assert", "mode": "delta", "field": "active", "comparison": "==", "value": 1, "to": 1, "extra": 1}]}, "step 0.from has the wrong type"),
        ]
        for scenario, message in cases:
            with self.subTest(message=message):
                original = copy.deepcopy(scenario)
                with self.assertRaises(runner.ScenarioError) as caught:
                    runner.validate_scenario(scenario)
                self.assertEqual(message, str(caught.exception))
                self.assertEqual(original, scenario)
        for value, name in (([], "list"), ({}, "dict")):
            with self.subTest(unhashable=name):
                with self.assertRaises(TypeError) as caught:
                    runner.validate_scenario({"steps": [{"op": value, "extra": 1}]})
                self.assertEqual(f"unhashable type: '{name}'", str(caught.exception))
        for value in (set(), {"pose"}):
            with self.subTest(set_operation=value):
                with self.assertRaises(runner.ScenarioError) as caught:
                    runner.validate_scenario({"steps": [{"op": value, "extra": 1}]})
                self.assertEqual("step 0 has an unsupported operation", str(caught.exception))

    def test_numeric_rules_preserve_bool_zero_negative_and_nonfinite_behavior(self) -> None:
        targets = [("hold", "duration_ms"), ("hold", "cadence_ms"),
                   ("trace", "duration_ms"), ("trace", "cadence_ms"),
                   ("wait_until", "timeout_ms"), ("wait_until", "cadence_ms"),
                   ("pose", "timeout_ms")]
        for operation, field in targets:
            for value in (True, False, 0, -1, -math.inf, None, "1"):
                with self.subTest(operation=operation, field=field, rejected=value):
                    with self.assertRaises(runner.ScenarioError) as caught:
                        runner.validate_scenario({"steps": [{**VALID_OPERATION_STEPS[operation], field: value}]})
                    where = "step 0" if operation == "pose" else "0"
                    self.assertEqual(f"step {where} requires positive {field}", str(caught.exception))
            for value in (1, 0.5, math.nan, math.inf):
                with self.subTest(operation=operation, field=field, accepted=value):
                    runner.validate_scenario({"steps": [{**VALID_OPERATION_STEPS[operation], field: value}]})
        for field in ("x", "y", "z", "yaw", "pitch"):
            for value in (True, False, math.nan, math.inf, -math.inf):
                with self.subTest(pose_field=field, rejected=value):
                    with self.assertRaises(runner.ScenarioError) as caught:
                        runner.validate_scenario({"steps": [{**VALID_OPERATION_STEPS["pose"], field: value}]})
                    self.assertEqual(f"step 0.{field} must be finite", str(caught.exception))
            for value in (0, -1):
                runner.validate_scenario({"steps": [{**VALID_OPERATION_STEPS["pose"], field: value}]})
        for value in (-91, 91):
            with self.assertRaises(runner.ScenarioError) as caught:
                runner.validate_scenario({"steps": [{**VALID_OPERATION_STEPS["pose"], "pitch": value}]})
            self.assertEqual("step 0.pitch must be within [-90, 90]", str(caught.exception))
        self.assertTrue(math.isnan(runner.positive_number({"n": math.nan}, "n", 0)))
        self.assertEqual(math.inf, runner.positive_number({"n": math.inf}, "n", 0))

    def test_assertion_defaults_and_ignored_allowed_fields_remain_accepted(self) -> None:
        cases = [{"op": "assert", "field": "active", "comparison": "==", "value": 3},
                 {"op": "assert", "mode": "monotonicity", "field": "active", "direction": "nonincreasing"},
                 {"op": "assert", "mode": "absence_of_failure", "field": None,
                  "comparison": "invalid", "value": None, "from": 1, "to": False, "direction": None}]
        for step in cases:
            runner.validate_scenario({"steps": [step]})

    def test_all_operation_dispatch_commands_and_defaults(self) -> None:
        direct = {"reconnect_quic", "hold_quic", "resume_quic", "shader_reload", "shaders_on",
                  "shaders_off", "shader_reload_all_changed", "shader_option", "zoom_in", "zoom_out", "zoom_max"}
        for name, operation in VALID_OPERATION_STEPS.items():
            with self.subTest(operation=name):
                run = object.__new__(runner.ScenarioRun)
                run.step = 7
                run.run_id = "declaration-test"
                run.checkpoints = {"before": {"active": 2}, "after": {"active": 3}}
                run.report = runner.RunReport(run.run_id, False, snapshots=[{"active": 3}])
                run.command_and_wait = mock.Mock(return_value={"result": {"snapshot": {"active": 3}}})
                original = copy.deepcopy(operation)
                with mock.patch.object(runner.time, "monotonic", side_effect=(0, 0)), \
                        mock.patch.object(runner.time, "sleep") as sleep:
                    run.execute_step(operation, 0)
                self.assertEqual(original, operation)
                sleep.assert_not_called()
                if name in direct:
                    arguments = " TAA true" if name == "shader_option" else ""
                    expected = (f"voxytest {name} declaration-test 8{arguments}", {"CHECKPOINT_RESULT"}, 120, f"{name}[0]")
                elif name == "pose":
                    expected = ("voxytest pose declaration-test 8 minecraft:overworld 0.0 100.0 0.0 90.0 -45.0 1000",
                                {"POSE_REACHED", "POSE_FAILED"}, 11, "pose[0]")
                elif name in {"hold", "trace"}:
                    expected = ("voxytest trace declaration-test 8 1000 100", {"CHECKPOINT_RESULT"}, 31, f"{name}[0]")
                elif name == "wait_until":
                    expected = ("voxytest checkpoint declaration-test 8", {"CHECKPOINT_RESULT"}, 30, "wait_until[0]")
                elif name == "checkpoint":
                    expected = ("voxytest checkpoint declaration-test 8", {"CHECKPOINT_RESULT"}, 30, "checkpoint[0]")
                    self.assertEqual({"active": 3}, run.checkpoints["saved"])
                elif name == "screenshot":
                    expected = ("voxytest screenshot declaration-test 8", {"SCREENSHOT_RESULT"}, 180, "screenshot[0]")
                else:
                    run.command_and_wait.assert_not_called()
                    self.assertEqual([{"index": 0, "mode": "delta", "actual": 1, "passed": True}], run.report.assertions)
                    self.assertEqual(7, run.step)
                    continue
                run.command_and_wait.assert_called_once_with(*expected)
                self.assertEqual(8, run.step)
        for name in ("hold", "trace"):
            run = object.__new__(runner.ScenarioRun)
            run.step = 0
            run.run_id = "defaults"
            run.command_and_wait = mock.Mock()
            run.execute_step({"op": name, "duration_ms": 1000.5}, 2)
            run.command_and_wait.assert_called_once_with("voxytest trace defaults 1 1000 250", {"CHECKPOINT_RESULT"}, 31, f"{name}[2]")
        run = object.__new__(runner.ScenarioRun)
        run.step = 0
        run.run_id = "defaults"
        run.command_and_wait = mock.Mock()
        run.execute_step({key: value for key, value in VALID_OPERATION_STEPS["pose"].items() if key != "timeout_ms"}, 2)
        run.command_and_wait.assert_called_once_with("voxytest pose defaults 1 minecraft:overworld 0.0 100.0 0.0 90.0 -45.0 15000",
                                                    {"POSE_REACHED", "POSE_FAILED"}, 25, "pose[2]")

    def test_zoom_controls_are_narrow_and_dispatched(self) -> None:
        for name in ("zoom_in", "zoom_out", "zoom_max"):
            runner.validate_scenario({"steps": [{"op": name}]})
            with self.assertRaises(runner.ScenarioError):
                runner.validate_scenario({"steps": [{"op": name, "command": "arbitrary"}]})
            run = object.__new__(runner.ScenarioRun)
            run.step = 7
            run.run_id = "zoom-test"
            run.command_and_wait = mock.Mock()
            run.execute_step({"op": name}, 0)
            run.command_and_wait.assert_called_once_with(
                f"voxytest {name} zoom-test 8", {"CHECKPOINT_RESULT"}, 120, f"{name}[0]")

    def test_rejects_executable_or_unknown_operations(self) -> None:
        with self.assertRaises(runner.ScenarioError):
            runner.validate_scenario({"steps": [{"op": "python", "code": "pass"}]})
        with self.assertRaises(runner.ScenarioError):
            runner.validate_scenario({"steps": [{"op": "checkpoint", "shell": "true"}]})

    def test_shader_controls_are_narrow(self) -> None:
        for name in ("shader_reload", "shader_reload_all_changed", "shaders_on", "shaders_off"):
            runner.validate_scenario({"steps": [{"op": name}]})
        runner.validate_scenario({"steps": [{"op": "shader_option", "option": "TAA", "value": "true"}]})
        for option in ("../config", "TAA;stop", "", "TAA\nstop"):
            with self.assertRaises(runner.ScenarioError):
                runner.validate_scenario({"steps": [{"op": "shader_option", "option": option, "value": "true"}]})

    def test_accepts_every_declared_scenario(self) -> None:
        paths = {path.name: path for path in Path("tools/scenarios").glob("*.json")}
        self.assertEqual(set(SCENARIO_HASHES), set(paths))
        for name, path in paths.items():
            scenario, digest = runner.canonical_scenario(path)
            self.assertTrue(scenario["steps"])
            self.assertEqual(SCENARIO_HASHES[name], digest)


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
