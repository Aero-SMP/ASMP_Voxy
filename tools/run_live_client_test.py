#!/usr/bin/env python3
"""Run repeatable, declarative tests against the real debug Minecraft client."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, field
from typing import Any


VALID_STEPS = {"pose", "hold", "trace", "wait_until", "checkpoint", "screenshot", "assert", "reconnect_quic", "hold_quic", "resume_quic", "shader_reload", "shaders_on", "shaders_off", "shader_reload_all_changed", "shader_option"}
COMPARISONS = {"==", "!=", "<", "<=", ">", ">="}
RESULT_KINDS = {
    "pose": "POSE_REACHED",
    "hold": "CHECKPOINT_RESULT",
    "trace": "CHECKPOINT_RESULT",
    "checkpoint": "CHECKPOINT_RESULT",
    "screenshot": "SCREENSHOT_RESULT",
}


class ScenarioError(ValueError):
    pass


class RunFailure(RuntimeError):
    def __init__(self, category: str, message: str):
        super().__init__(message)
        self.category = category


def canonical_scenario(path: Path) -> tuple[dict[str, Any], str]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise ScenarioError(f"cannot read scenario: {failure}") from failure
    validate_scenario(value)
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return value, hashlib.sha256(canonical).hexdigest()


def validate_scenario(scenario: Any) -> None:
    if not isinstance(scenario, dict) or not isinstance(scenario.get("steps"), list):
        raise ScenarioError("scenario must be an object containing a steps array")
    allowed_top = {"name", "initial_pose", "steps"}
    unknown = set(scenario) - allowed_top
    if unknown:
        raise ScenarioError(f"unsupported scenario fields: {sorted(unknown)}")
    if "name" in scenario and not isinstance(scenario["name"], str):
        raise ScenarioError("scenario name must be a string")
    if "initial_pose" in scenario:
        validate_pose(scenario["initial_pose"], "initial_pose")
        extras = set(scenario["initial_pose"]) - {
            "dimension", "x", "y", "z", "yaw", "pitch", "timeout_ms"
        }
        if extras:
            raise ScenarioError(f"initial_pose has unsupported fields: {sorted(extras)}")
    for index, step in enumerate(scenario["steps"]):
        if not isinstance(step, dict) or step.get("op") not in VALID_STEPS:
            raise ScenarioError(f"step {index} has an unsupported operation")
        operation = step["op"]
        if operation == "pose":
            validate_pose(step, f"step {index}")
        elif operation in {"hold", "trace"}:
            positive_number(step, "duration_ms", index)
            if "cadence_ms" in step:
                positive_number(step, "cadence_ms", index)
        elif operation == "wait_until":
            require_field(step, "field", str, index)
            if step.get("comparison") not in COMPARISONS:
                raise ScenarioError(f"step {index} has an invalid comparison")
            if "value" not in step:
                raise ScenarioError(f"step {index} omits value")
            positive_number(step, "timeout_ms", index)
            if "cadence_ms" in step:
                positive_number(step, "cadence_ms", index)
        elif operation == "checkpoint":
            if "name" in step and not isinstance(step["name"], str):
                raise ScenarioError(f"step {index} checkpoint name must be a string")
        elif operation == "shader_option":
            import re
            if not isinstance(step.get("option"), str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,127}", step["option"]):
                raise ScenarioError(f"step {index} has invalid shader option")
            if not isinstance(step.get("value"), str) or not re.fullmatch(r"[A-Za-z0-9_.+-]{1,128}", step["value"]):
                raise ScenarioError(f"step {index} has invalid shader value")
        elif operation == "assert":
            validate_assertion(step, index)
        allowed = {
            "pose": {"op", "dimension", "x", "y", "z", "yaw", "pitch", "timeout_ms"},
            "hold": {"op", "duration_ms", "cadence_ms"},
            "trace": {"op", "duration_ms", "cadence_ms"},
            "wait_until": {"op", "field", "comparison", "value", "timeout_ms", "cadence_ms"},
            "checkpoint": {"op", "name"},
            "screenshot": {"op"},
            "reconnect_quic": {"op"},
            "hold_quic": {"op"},
            "resume_quic": {"op"},
            "shader_reload": {"op"},
            "shaders_on": {"op"},
            "shaders_off": {"op"},
            "shader_reload_all_changed": {"op"},
            "shader_option": {"op", "option", "value"},
            "assert": {"op", "mode", "field", "comparison", "value", "from", "to", "direction"},
        }[operation]
        extras = set(step) - allowed
        if extras:
            raise ScenarioError(f"step {index} has unsupported fields: {sorted(extras)}")


def validate_pose(pose: Any, where: str) -> None:
    if not isinstance(pose, dict):
        raise ScenarioError(f"{where} must be an object")
    for name in ("dimension",):
        if not isinstance(pose.get(name), str) or not pose[name]:
            raise ScenarioError(f"{where}.{name} must be a nonempty string")
    for name in ("x", "y", "z", "yaw", "pitch"):
        value = pose.get(name)
        if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value):
            raise ScenarioError(f"{where}.{name} must be finite")
    if not -90 <= pose["pitch"] <= 90:
        raise ScenarioError(f"{where}.pitch must be within [-90, 90]")
    if "timeout_ms" in pose and positive_number(pose, "timeout_ms", where) <= 0:
        raise ScenarioError(f"{where}.timeout_ms must be positive")


def validate_assertion(step: dict[str, Any], index: int) -> None:
    mode = step.get("mode", "value")
    if mode == "absence_of_failure":
        return
    if mode == "monotonicity":
        require_field(step, "field", str, index)
        if step.get("direction") not in {"nondecreasing", "nonincreasing"}:
            raise ScenarioError(f"step {index} has invalid monotonic direction")
        return
    if mode in {"value", "delta"}:
        require_field(step, "field", str, index)
        if step.get("comparison") not in COMPARISONS or "value" not in step:
            raise ScenarioError(f"step {index} has incomplete assertion")
        if mode == "delta":
            require_field(step, "from", str, index)
            require_field(step, "to", str, index)
        return
    raise ScenarioError(f"step {index} has unsupported assertion mode")


def positive_number(value: dict[str, Any], name: str, where: Any) -> float:
    item = value.get(name)
    if not isinstance(item, (int, float)) or isinstance(item, bool) or item <= 0:
        raise ScenarioError(f"step {where} requires positive {name}")
    return float(item)


def require_field(value: dict[str, Any], name: str, kind: type, where: Any) -> None:
    if not isinstance(value.get(name), kind):
        raise ScenarioError(f"step {where}.{name} has the wrong type")


class TmuxConsole:
    def __init__(self, target: str, timeout: float = 20.0):
        if not target:
            raise ScenarioError("console target cannot be empty")
        self.target = target
        self.timeout = timeout

    def send(self, command: str) -> None:
        if "\n" in command or "\r" in command:
            raise RunFailure("CONSOLE", "multiline console command rejected")
        result = subprocess.run(
            ["tmux", "send-keys", "-t", self.target, "--", command, "Enter"],
            capture_output=True, text=True, timeout=self.timeout, check=False,
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            raise RunFailure("CONSOLE", f"tmux command failed: {detail}")


class EvidenceReader:
    def __init__(self, directory: Path):
        self.directory = directory
        self.events_path = directory / "events.jsonl"
        self.offset = 0
        self.partial = ""
        self.events: list[dict[str, Any]] = []

    def poll(self) -> list[dict[str, Any]]:
        if not self.events_path.is_file():
            return []
        with self.events_path.open("r", encoding="utf-8") as source:
            source.seek(self.offset)
            chunk = source.read()
            self.offset = source.tell()
        text = self.partial + chunk
        lines = text.split("\n")
        self.partial = lines.pop()
        added = []
        for line in lines:
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError as failure:
                raise RunFailure("EVIDENCE", f"invalid events.jsonl record: {failure}") from failure
            self.events.append(event)
            added.append(event)
        return added

    def wait_result(self, step: int, kinds: set[str], timeout_seconds: float) -> dict[str, Any]:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            for event in self.poll():
                result = event.get("result", {})
                if result.get("stepId") == step and result.get("kind") in kinds:
                    failure = result.get("failure")
                    if result.get("kind") == "RUN_FAILED" or failure not in {None, "NONE"}:
                        raise RunFailure(str(failure or "RUN_FAILED"),
                                         f"client failed step {step}: {failure}")
                    return event
            time.sleep(0.05)
        raise RunFailure("TIMEOUT", f"timed out waiting for step {step}: {sorted(kinds)}")


def compare(actual: Any, operator: str, expected: Any) -> bool:
    return {
        "==": lambda: actual == expected,
        "!=": lambda: actual != expected,
        "<": lambda: actual < expected,
        "<=": lambda: actual <= expected,
        ">": lambda: actual > expected,
        ">=": lambda: actual >= expected,
    }[operator]()


def snapshot_from(event: dict[str, Any]) -> dict[str, Any]:
    snapshot = event.get("result", {}).get("snapshot")
    if not isinstance(snapshot, dict):
        raise RunFailure("EVIDENCE", "result omitted its typed snapshot")
    return snapshot


@dataclass
class RunReport:
    run_id: str
    warmup: bool
    status: str = "FAIL"
    failure: str | None = None
    timings_ms: dict[str, list[float]] = field(default_factory=dict)
    assertions: list[dict[str, Any]] = field(default_factory=list)
    snapshots: list[dict[str, Any]] = field(default_factory=list)


class ScenarioRun:
    def __init__(self, console: TmuxConsole, output: Path, player: str,
                 scenario: dict[str, Any], scenario_hash: str, warmup: bool):
        self.console = console
        self.output = output
        self.player = player
        self.scenario = scenario
        self.scenario_hash = scenario_hash
        self.run_id = str(uuid.uuid4())
        self.directory = output / self.run_id
        self.reader = EvidenceReader(self.directory)
        self.report = RunReport(self.run_id, warmup)
        self.step = 0
        self.checkpoints: dict[str, dict[str, Any]] = {}

    def execute(self) -> RunReport:
        try:
            self.console.send(f"voxytest begin {self.player} {self.run_id} {self.scenario_hash}")
            event = self.reader.wait_result(0, {"CLIENT_READY", "RUN_FAILED"}, 30)
            self.record(event)
            initial = self.scenario.get("initial_pose")
            if initial is not None:
                self.do_pose(initial, "initial_pose")
            for index, operation in enumerate(self.scenario["steps"]):
                self.execute_step(operation, index)
            self.step += 1
            self.command_and_wait(f"voxytest end {self.run_id} {self.step}",
                                  {"RUN_COMPLETE"}, 30, "end")
            self.console.send(f"voxytest finish {self.run_id} PASS")
            self.report.status = "PASS"
        except RunFailure as failure:
            self.report.failure = f"{failure.category}: {failure}"
            self.report.status = "TIMEOUT" if failure.category == "TIMEOUT" else "FAIL"
            try:
                self.console.send(f"voxytest finish {self.run_id} {self.report.status}")
            except RunFailure:
                pass
        self.await_server_result()
        self.publish_runner_result()
        return self.report

    def execute_step(self, operation: dict[str, Any], index: int) -> None:
        kind = operation["op"]
        if kind in {"reconnect_quic", "hold_quic", "resume_quic", "shader_reload", "shaders_on", "shaders_off", "shader_reload_all_changed", "shader_option"}:
            self.step += 1
            option_args = f" {operation['option']} {operation['value']}" if kind == "shader_option" else ""
            self.command_and_wait(f"voxytest {kind} {self.run_id} {self.step}{option_args}",
                                  {"CHECKPOINT_RESULT"}, 120, f"{kind}[{index}]")
        elif kind == "pose":
            self.do_pose(operation, f"pose[{index}]")
        elif kind in {"hold", "trace"}:
            self.step += 1
            duration = int(operation["duration_ms"])
            cadence = int(operation.get("cadence_ms", 250))
            self.command_and_wait(
                f"voxytest trace {self.run_id} {self.step} {duration} {cadence}",
                {"CHECKPOINT_RESULT"}, duration / 1000 + 30, f"{kind}[{index}]",
            )
        elif kind == "wait_until":
            self.wait_until(operation, index)
        elif kind == "checkpoint":
            self.step += 1
            event = self.command_and_wait(
                f"voxytest checkpoint {self.run_id} {self.step}",
                {"CHECKPOINT_RESULT"}, 30, f"checkpoint[{index}]",
            )
            if operation.get("name"):
                self.checkpoints[operation["name"]] = snapshot_from(event)
        elif kind == "screenshot":
            self.step += 1
            self.command_and_wait(
                f"voxytest screenshot {self.run_id} {self.step}",
                {"SCREENSHOT_RESULT"}, 180, f"screenshot[{index}]",
            )
        elif kind == "assert":
            self.assert_step(operation, index)

    def do_pose(self, pose: dict[str, Any], label: str) -> None:
        self.step += 1
        timeout = int(pose.get("timeout_ms", 15_000))
        command = (
            f"voxytest pose {self.run_id} {self.step} {pose['dimension']} "
            f"{pose['x']!r} {pose['y']!r} {pose['z']!r} "
            f"{pose['yaw']!r} {pose['pitch']!r} {timeout}"
        )
        self.command_and_wait(command, {"POSE_REACHED", "POSE_FAILED"},
                              timeout / 1000 + 10, label)

    def wait_until(self, operation: dict[str, Any], index: int) -> None:
        deadline = time.monotonic() + operation["timeout_ms"] / 1000
        cadence = operation.get("cadence_ms", 250) / 1000
        last = None
        while time.monotonic() < deadline:
            self.step += 1
            event = self.command_and_wait(
                f"voxytest checkpoint {self.run_id} {self.step}",
                {"CHECKPOINT_RESULT"}, 30, f"wait_until[{index}]",
            )
            last = snapshot_from(event).get(operation["field"])
            if last is not None and compare(last, operation["comparison"], operation["value"]):
                return
            time.sleep(cadence)
        raise RunFailure("ASSERTION", f"wait_until[{index}] ended with {last!r}")

    def assert_step(self, operation: dict[str, Any], index: int) -> None:
        mode = operation.get("mode", "value")
        passed = False
        actual: Any = None
        if mode == "absence_of_failure":
            failures = [event for event in self.reader.events
                        if event.get("result", {}).get("failure") not in {None, "NONE"}]
            actual = len(failures)
            passed = not failures
        elif mode == "monotonicity":
            values = [snapshot_from(event).get(operation["field"])
                      for event in self.reader.events if "snapshot" in event.get("result", {})]
            values = [value for value in values if value is not None]
            actual = values
            pairs = zip(values, values[1:])
            passed = all(a <= b for a, b in pairs) if operation["direction"] == "nondecreasing" \
                else all(a >= b for a, b in pairs)
        elif mode == "delta":
            before = self.checkpoints.get(operation["from"])
            after = self.checkpoints.get(operation["to"])
            if before is None or after is None:
                raise RunFailure("ASSERTION", f"assert[{index}] names an unknown checkpoint")
            actual = after.get(operation["field"], 0) - before.get(operation["field"], 0)
            passed = compare(actual, operation["comparison"], operation["value"])
        else:
            if not self.report.snapshots:
                raise RunFailure("ASSERTION", f"assert[{index}] has no snapshot")
            actual = self.report.snapshots[-1].get(operation["field"])
            passed = actual is not None and compare(
                actual, operation["comparison"], operation["value"])
        assertion = {"index": index, "mode": mode, "actual": actual, "passed": passed}
        self.report.assertions.append(assertion)
        if not passed:
            raise RunFailure("ASSERTION", f"assert[{index}] failed: {actual!r}")

    def command_and_wait(self, command: str, kinds: set[str], timeout: float,
                         timing: str) -> dict[str, Any]:
        started = time.monotonic_ns()
        self.console.send(command)
        event = self.reader.wait_result(self.step, kinds | {"RUN_FAILED"}, timeout)
        elapsed = (time.monotonic_ns() - started) / 1_000_000
        self.report.timings_ms.setdefault(timing, []).append(elapsed)
        self.record(event)
        return event

    def record(self, event: dict[str, Any]) -> None:
        self.report.snapshots.append(snapshot_from(event))

    def await_server_result(self) -> None:
        deadline = time.monotonic() + 10
        result = self.directory / "result.json"
        while time.monotonic() < deadline:
            if result.is_file():
                return
            time.sleep(0.05)

    def publish_runner_result(self) -> None:
        destination = self.directory / "result.json"
        existing: dict[str, Any] = {}
        if destination.is_file():
            try:
                existing = json.loads(destination.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                existing = {}
        existing.update({
            "status": self.report.status,
            "runnerFailure": self.report.failure,
            "assertions": self.report.assertions,
            "timingsMs": self.report.timings_ms,
            "rawEvidence": str(self.directory),
        })
        atomic_json(destination, existing)


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(0, math.ceil(percentile_value * len(ordered)) - 1)
    return ordered[rank]


def aggregate(reports: list[RunReport], include_warmup: bool = False) -> dict[str, Any]:
    included = [report for report in reports if include_warmup or not report.warmup]
    timings: dict[str, list[float]] = {}
    snapshots = []
    for report in included:
        for name, values in report.timings_ms.items():
            timings.setdefault(name, []).extend(values)
        snapshots.extend(report.snapshots)
    timing_summary = {
        name: {"all": values, "median": statistics.median(values),
               "p95": percentile(values, 0.95), "min": min(values), "max": max(values)}
        for name, values in timings.items() if values
    }
    resource_fields = ("rendererAllocatedBytes", "selectedBytes", "warmBytes", "coldBytes",
                       "networkBytes", "requested", "active")
    resources = {}
    for name in resource_fields:
        values = [snapshot[name] for snapshot in snapshots
                  if isinstance(snapshot.get(name), (int, float))]
        if values:
            resources[name] = {"min": min(values), "max": max(values), "all": values}
    failures: dict[str, int] = {}
    for report in included:
        if report.failure:
            category = report.failure.split(":", 1)[0]
            failures[category] = failures.get(category, 0) + 1
    return {
        "runs": [report.__dict__ for report in reports],
        "includedRuns": len(included),
        "passCount": sum(report.status == "PASS" for report in included),
        "failureCount": sum(report.status != "PASS" for report in included),
        "failureCategories": failures,
        "timingsMs": timing_summary,
        "resources": resources,
    }


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(value, output, indent=2, sort_keys=True)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--player", required=True)
    parser.add_argument("--scenario", type=Path, required=True)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--warmup", type=int, default=0)
    parser.add_argument("--console-target", default="printer_session:Creative.0")
    parser.add_argument("--output", type=Path,
                        default=Path("/home/printer/Desktop/Creative/logs/voxy-tests"))
    parser.add_argument("--include-warmup", action="store_true")
    arguments = parser.parse_args(argv)
    if arguments.repeat <= 0 or arguments.warmup < 0:
        parser.error("--repeat must be positive and --warmup cannot be negative")
    return arguments


def main(argv: list[str] | None = None) -> int:
    arguments = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        scenario, scenario_hash = canonical_scenario(arguments.scenario)
        console = TmuxConsole(arguments.console_target)
        reports = []
        for index in range(arguments.warmup + arguments.repeat):
            run = ScenarioRun(console, arguments.output, arguments.player, scenario,
                              scenario_hash, index < arguments.warmup)
            report = run.execute()
            reports.append(report)
            print(f"{report.run_id}: {report.status}"
                  + (f" ({report.failure})" if report.failure else ""), flush=True)
        summary = aggregate(reports, arguments.include_warmup)
        aggregate_path = arguments.output / (
            f"aggregate-{scenario_hash[:12]}-{int(time.time())}.json")
        atomic_json(aggregate_path, summary)
        print(aggregate_path)
        return 0 if summary["failureCount"] == 0 else 1
    except ScenarioError as failure:
        print(f"scenario error: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
