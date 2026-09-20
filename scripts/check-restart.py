#!/usr/bin/env python3
"""Verify an actual ground-process crash against a local Docker Compose deployment.

Creates one simulated observation and kills only the ground container. Refuses to
run if a procedure is already active. Run from the project root after startup.
"""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

BASE = "http://127.0.0.1:8081/api"
ROOT = Path(__file__).resolve().parent.parent


def call(path, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=3) as response:
        return json.load(response)


def wait_for(predicate, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            state = call("/state")
            if predicate(state):
                return state
        except (OSError, ValueError):
            pass
        time.sleep(0.25)
    raise AssertionError("Expected state was not observed before timeout")


initial = call("/state")
assert not initial["run"] or initial["run"]["status"] in ("COMPLETED", "FAILED", "ABORTED"), "Existing active procedure: refusing to interrupt it"
call("/scenario", {"mode": "NONE"})
initial = wait_for(lambda s: s["fresh"] and s["telemetry"]["fault"] == "NONE")
observations = initial["telemetry"]["observations"]
run = call("/runs", {"requestId": str(uuid.uuid4())})
wait_for(lambda s: any(c["kind"] == "CAPTURE" and c["status"] == "ACCEPTED" for c in s["commands"]))
subprocess.run([str(ROOT / "scripts/compose.sh"), "kill", "-s", "SIGKILL", "ground"], cwd=ROOT, check=True)
subprocess.run([str(ROOT / "scripts/compose.sh"), "up", "-d", "ground"], cwd=ROOT, check=True)
recovered = wait_for(lambda s: s["connected"] and s["fresh"] and s["run"]["id"] == run["id"])
assert recovered["run"]["status"] == "PAUSED"
assert len(recovered["commands"]) == 2, "Recovery dispatched a new command"
capture = next(c for c in recovered["commands"] if c["kind"] == "CAPTURE")
if capture["status"] != "COMPLETED":
    call(f'/runs/{run["id"]}/reconcile', {})
wait_for(lambda s: any(c["kind"] == "CAPTURE" and c["status"] == "COMPLETED" for c in s["commands"]))
call(f'/runs/{run["id"]}/resume', {})
finished = wait_for(lambda s: s["run"]["status"] == "COMPLETED")
assert finished["telemetry"]["observations"] == observations + 1, "Capture was duplicated"
assert len(finished["commands"]) == 3
print(json.dumps({"result": "PASS", "scenario": "ground process SIGKILL during collection", "runId": run["id"], "observationsAdded": 1, "commands": 3}, indent=2))
