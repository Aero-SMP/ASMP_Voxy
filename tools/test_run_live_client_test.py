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


class _ComparisonResult:
    def __init__(self, events, label, truth):
        self.events, self.label, self.truth = events, label, truth

    def __bool__(self):
        self.events.append((self.label, "bool"))
        if isinstance(self.truth, Exception):
            raise self.truth
        return self.truth

    def __repr__(self):
        return self.label


class _ComparisonOperand:
    def __init__(self, events, label, outcome):
        self.events, self.label, self.outcome = events, label, outcome

    def _apply(self, method, other):
        self.events.append((self.label, method, getattr(other, "label", other)))
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome

    def __eq__(self, other): return self._apply("eq", other)
    def __ne__(self, other): return self._apply("ne", other)
    def __lt__(self, other): return self._apply("lt", other)
    def __le__(self, other): return self._apply("le", other)
    def __gt__(self, other): return self._apply("gt", other)
    def __ge__(self, other): return self._apply("ge", other)

    def __repr__(self):
        return self.label


class _DerivedComparisonOperand(_ComparisonOperand):
    pass


class ComparisonTest(unittest.TestCase):
    # Independent operation and method order; do not derive these from dispatch.
    operations = ("==", "!=", "<", "<=", ">", ">=")
    methods = ("eq", "ne", "lt", "le", "gt", "ge")
    reflected = ("eq", "ne", "gt", "ge", "lt", "le")

    def make_run(self, snapshots=()):
        run = object.__new__(runner.ScenarioRun)
        run.step, run.run_id = 7, "comparison-test"
        run.checkpoints = {"before": {"active": 2}, "after": {"active": 3}}
        run.reader = mock.Mock(events=[])
        run.report = runner.RunReport(run.run_id, False, snapshots=list(snapshots))
        run.command_and_wait = mock.Mock()
        return run

    def test_scalar_comparisons_and_keyword_contract(self):
        less = (False, True, True, True, False, False)
        equal = (True, False, False, True, False, True)
        cases = [(2, 3, less), (3, 2, (False, True, False, False, True, True)),
                 (3, 3, equal), (True, 1, equal), (False, 0, equal),
                 (2**100, 2**100 + 1, less),
                 (math.nan, math.nan, (False, True, False, False, False, False)),
                 (math.inf, math.inf, equal), (-math.inf, math.inf, less),
                 ([1], [2], less), ((1,), (1,), equal), ("a", "b", less),
                 ({1}, {1, 2}, less)]
        self.assertEqual({"==", "!=", "<", "<=", ">", ">="}, runner.COMPARISONS)
        for actual, expected, results in cases:
            for operation, result in zip(self.operations, results):
                with self.subTest(actual=actual, expected=expected, operation=operation):
                    self.assertIs(result, runner.compare(actual=actual, operator=operation, expected=expected))

    def test_scalar_comparison_errors_preserve_messages(self):
        for actual, expected, equal in ((1, "1", False), (None, 0, False),
                                       (1j, 2j, False), ({}, {}, True), ({1}, [1], False)):
            self.assertIs(equal, runner.compare(actual, "==", expected))
            self.assertIs(not equal, runner.compare(actual, "!=", expected))
            for operation in self.operations[2:]:
                with self.subTest(actual=actual, expected=expected, operation=operation):
                    with self.assertRaises(TypeError) as caught:
                        runner.compare(actual, operation, expected)
                    self.assertEqual(f"'{operation}' not supported between instances of "
                                     f"'{type(actual).__name__}' and '{type(expected).__name__}'", str(caught.exception))

    def test_rich_comparisons_return_identity_without_boolean_coercion(self):
        for operation, method in zip(self.operations, self.methods):
            with self.subTest(operation=operation):
                events = []
                result = _ComparisonResult(events, "result", AssertionError("unexpected coercion"))
                actual = _ComparisonOperand(events, "left", result)
                expected = _ComparisonOperand(events, "right", AssertionError("unexpected reflection"))
                self.assertIs(result, runner.compare(actual, operation, expected))
                self.assertEqual([("left", method, "right")], events)

    def test_reflected_subclass_and_not_implemented_order(self):
        for operation, method, reflected in zip(self.operations, self.methods, self.reflected):
            for subclass in (False, True):
                with self.subTest(operation=operation, subclass=subclass):
                    events = []
                    result = object()
                    actual = _ComparisonOperand(events, "left", NotImplemented)
                    right_type = _DerivedComparisonOperand if subclass else _ComparisonOperand
                    expected = right_type(events, "right", result)
                    self.assertIs(result, runner.compare(actual, operation, expected))
                    wanted = [("right", reflected, "left")] if subclass else [
                        ("left", method, "right"), ("right", reflected, "left")]
                    self.assertEqual(wanted, events)
            events = []
            actual = _ComparisonOperand(events, "left", NotImplemented)
            expected = _ComparisonOperand(events, "right", NotImplemented)
            if operation in ("==", "!="):
                self.assertIs(operation == "!=", runner.compare(actual, operation, expected))
            else:
                with self.assertRaises(TypeError) as caught:
                    runner.compare(actual, operation, expected)
                self.assertEqual(f"'{operation}' not supported between instances of "
                                 "'_ComparisonOperand' and '_ComparisonOperand'", str(caught.exception))
            self.assertEqual([("left", method, "right"), ("right", reflected, "left")], events)

    def test_lookup_errors_precede_comparison_and_selected_errors_propagate(self):
        for operation in ("unsupported", None, 1, [], {}, set()):
            events = []
            actual = _ComparisonOperand(events, "left", AssertionError("operand evaluated"))
            error = TypeError if isinstance(operation, (list, dict, set)) else KeyError
            with self.assertRaises(error) as caught:
                runner.compare(actual, operation, object())
            message = f"unhashable type: '{type(operation).__name__}'" if error is TypeError else repr(operation)
            self.assertEqual(message, str(caught.exception))
            self.assertEqual([], events)
        for operation, method in zip(self.operations, self.methods):
            events = []
            failure = RuntimeError("selected operand failure")
            actual = _ComparisonOperand(events, "left", failure)
            with self.assertRaises(RuntimeError) as caught:
                runner.compare(actual, operation, "right")
            self.assertIs(failure, caught.exception)
            self.assertEqual([("left", method, "right")], events)

    def test_assert_caller_retains_rich_result_and_failure_order(self):
        for truth in (True, False, RuntimeError("truth failure")):
            with self.subTest(truth=truth):
                events = []
                result = _ComparisonResult(events, "result", truth)
                actual = _ComparisonOperand(events, "actual", result)
                run = self.make_run([{"active": actual}])
                operation = {"op": "assert", "field": "active", "comparison": "<", "value": 4}
                if truth is True:
                    run.execute_step(operation, 2)
                else:
                    error = RuntimeError if isinstance(truth, Exception) else runner.RunFailure
                    with self.assertRaises(error) as caught:
                        run.execute_step(operation, 2)
                    self.assertEqual("truth failure" if isinstance(truth, Exception) else "assert[2] failed: actual",
                                     str(caught.exception))
                    if error is runner.RunFailure: self.assertEqual("ASSERTION", caught.exception.category)
                self.assertIs(actual, run.report.assertions[0]["actual"])
                self.assertIs(result, run.report.assertions[0]["passed"])
                self.assertEqual([("actual", "lt", 4), ("result", "bool")], events)
                run.command_and_wait.assert_not_called()
                self.assertEqual(7, run.step)
        events = []
        actual = _ComparisonOperand(events, "actual", RuntimeError("comparison failure"))
        run = self.make_run([{"active": actual}])
        with self.assertRaisesRegex(RuntimeError, "^comparison failure$"):
            run.assert_step({"field": "active", "comparison": "<", "value": 4}, 2)
        self.assertEqual([], run.report.assertions)
        self.assertEqual([("actual", "lt", 4)], events)

    def test_assert_caller_delta_and_missing_values(self):
        run = self.make_run()
        run.assert_step({"mode": "delta", "field": "active", "from": "before", "to": "after",
                         "comparison": "==", "value": 1}, 3)
        self.assertEqual([{"index": 3, "mode": "delta", "actual": 1, "passed": True}], run.report.assertions)
        for snapshots, message, appended in (([], "assert[3] has no snapshot", False),
                                              ([{}], "assert[3] failed: None", True)):
            run = self.make_run(snapshots)
            with self.assertRaises(runner.RunFailure) as caught:
                run.assert_step({"field": "active", "comparison": "unsupported", "value": object()}, 3)
            self.assertEqual("ASSERTION", caught.exception.category)
            self.assertEqual(message, str(caught.exception))
            self.assertEqual(1 if appended else 0, len(run.report.assertions))

    def test_wait_caller_missing_false_then_true_uses_fake_endpoints(self):
        events = []
        cold = _ComparisonOperand(events, "cold", _ComparisonResult(events, "false", False))
        ready = _ComparisonOperand(events, "ready", _ComparisonResult(events, "true", True))
        run = self.make_run()
        run.command_and_wait.side_effect = [{"result": {"snapshot": {"active": value}}}
                                           for value in (cold, None, ready)]
        operation = {"op": "wait_until", "field": "active", "comparison": ">=", "value": 3,
                     "timeout_ms": 1000, "cadence_ms": 100}
        with mock.patch.object(runner.time, "monotonic", side_effect=(0, 0, 0.1, 0.2)), \
                mock.patch.object(runner.time, "sleep") as sleep:
            run.execute_step(operation, 4)
        self.assertEqual([("cold", "ge", 3), ("false", "bool"),
                          ("ready", "ge", 3), ("true", "bool")], events)
        self.assertEqual([mock.call("voxytest checkpoint comparison-test " + str(step),
                                    {"CHECKPOINT_RESULT"}, 30, "wait_until[4]")
                          for step in (8, 9, 10)], run.command_and_wait.call_args_list)
        self.assertEqual([mock.call(0.1), mock.call(0.1)], sleep.call_args_list)
        self.assertEqual(10, run.step)
        self.assertEqual([], run.report.assertions)

    def test_wait_caller_timeout_and_comparison_failure_preserve_order(self):
        for outcome in (False, RuntimeError("comparison failure")):
            events = []
            actual = _ComparisonOperand(events, "cold", outcome)
            run = self.make_run()
            run.command_and_wait.return_value = {"result": {"snapshot": {"active": actual}}}
            with mock.patch.object(runner.time, "monotonic", side_effect=(0, 0, 1)), \
                    mock.patch.object(runner.time, "sleep") as sleep:
                error = RuntimeError if isinstance(outcome, Exception) else runner.RunFailure
                with self.assertRaises(error) as caught:
                    run.wait_until({"field": "active", "comparison": ">=", "value": 3,
                                    "timeout_ms": 1000}, 4)
            self.assertEqual("comparison failure" if isinstance(outcome, Exception) else "wait_until[4] ended with cold",
                             str(caught.exception))
            self.assertEqual([("cold", "ge", 3)], events)
            run.command_and_wait.assert_called_once_with("voxytest checkpoint comparison-test 8",
                                                        {"CHECKPOINT_RESULT"}, 30, "wait_until[4]")
            if isinstance(outcome, Exception): sleep.assert_not_called()
            else:
                sleep.assert_called_once_with(0.25)
                self.assertEqual("ASSERTION", caught.exception.category)
            self.assertEqual(8, run.step)


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
