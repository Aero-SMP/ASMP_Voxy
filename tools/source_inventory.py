"""Record maintained source and the unchanged frozen baseline with identical rules."""

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROOTS = ("client", "server", "shared", "updater", "terrain", "rust-server", "tools")
EXTENSIONS = {
    ".java",
    ".rs",
    ".comp",
    ".fsh",
    ".glsl",
    ".gradle",
    ".ps1",
    ".py",
    ".sh",
    ".vsh",
}
EXCLUDED = {".git", ".gradle", "__pycache__", "build", "cache", "target"}
CORE = {".java", ".rs", ".comp", ".fsh", ".glsl", ".vsh"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory():
    paths = set()
    directories = set()
    for name in ROOTS:
        root = ROOT / name
        if not root.exists():
            continue
        directories.add(name)
        for path in root.rglob("*"):
            relative = path.relative_to(ROOT)
            if EXCLUDED.intersection(relative.parts):
                continue
            if path.is_dir():
                directories.add(str(relative))
            elif path.is_file() and path.suffix in EXTENSIONS:
                paths.add(path)
    paths.update(path for path in ROOT.glob("*.gradle") if path.is_file())
    files = []
    for path in sorted(paths):
        relative = path.relative_to(ROOT)
        core = relative.parts[0] != "tools" and path.suffix in CORE
        files.append(
            {
                "path": str(relative),
                "sha256": digest(path),
                "lines": len(path.read_text(encoding="utf-8").splitlines()),
                "scope": (
                    "core"
                    if core
                    else "maintainedTool" if relative.parts[0] == "tools" else "build"
                ),
            }
        )

    baseline_path = ROOT / "project_audit/baseline_inventory.json"
    baseline = json.loads(baseline_path.read_text())
    archive = ROOT / ".verification/original-rewrite-baseline.zip"
    archive_hash = digest(archive)
    expected = "650c66f03fd4d04a138e10ebbdcc5ab7df79017cc872ba4185cca49ca9f6f5b6"
    if archive_hash != expected:
        raise ValueError("Frozen original rewrite archive changed")
    counts = {
        "ownedLines": sum(file["lines"] for file in files),
        "ownedFiles": len(files),
        "folders": len(directories),
        "coreLines": sum(file["lines"] for file in files if file["scope"] == "core"),
        "coreFiles": sum(file["scope"] == "core" for file in files),
    }
    gates = {}
    for name, limit in (("ownedLines", 10000), ("ownedFiles", 50)):
        gates[name] = {
            "current": counts[name],
            "limit": limit,
            "status": "PASS" if counts[name] <= limit else "FAIL",
        }
    result = {
        "capturedAtUTC": datetime.now(timezone.utc).isoformat(),
        **counts,
        "goal": {"path": "goal.txt", "sha256": digest(ROOT / "goal.txt")},
        "baseline": {
            "path": str(baseline_path.relative_to(ROOT)),
            "sha256": digest(baseline_path),
            "counts": baseline.get("counts", baseline),
            "archivePath": str(archive.relative_to(ROOT)),
            "archiveSHA256": archive_hash,
            "archiveVerifiedUnchanged": True,
        },
        "rules": {
            "maintainedRoots": ROOTS,
            "sourceExtensions": sorted(EXTENSIONS),
            "excludedGeneratedComponents": sorted(EXCLUDED),
            "lineCounting": "UTF-8 splitlines(), including blank lines and comments",
            "folders": "All maintained-root folders, including empty folders",
            "liveTooling": "Reused custom live operators and SSH helper source under tools/live are included",
            "historicalEvidence": "Frozen source snapshots and one-time migration records are not maintained source",
        },
        "gates": gates,
        "overallSourceSizeStatus": (
            "PASS"
            if all(gate["status"] == "PASS" for gate in gates.values())
            else "FAIL"
        ),
        "supersededCriteria": "The user replaced all 30%-of-baseline size gates with 10000 lines and 50 files; no folder cap",
        "files": files,
        "directories": sorted(directories),
        "liveAcceptance": "Not inferred from source size, compilation or staged artifacts",
    }
    destination = ROOT / "project_audit/consolidated_source_inventory.json"
    destination.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(counts | {"sizeStatus": result["overallSourceSizeStatus"]}))


if __name__ == "__main__":
    inventory()
