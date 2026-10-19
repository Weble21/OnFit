"""Start a private local model and run the Java evaluation through the production HTTP client."""
import os
import json
import shutil
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
AI = ROOT / "ai-service"
BACKEND = ROOT / "backend"
with socket.socket() as candidate:
    candidate.bind(("127.0.0.1", 0))
    port = candidate.getsockname()[1]

env = os.environ.copy()
env.setdefault("HF_HOME", str(AI / ".cache" / "hf"))
env["HF_HUB_OFFLINE"] = "1"
env["AI_SERVICE_TOKEN"] = secrets.token_urlsafe(32)
env["ONFIT_AI_TOKEN"] = env["AI_SERVICE_TOKEN"]
env["ONFIT_AI_URL"] = f"http://127.0.0.1:{port}/v1/semantic-score"
server = subprocess.Popen(
    [sys.executable, "-m", "uvicorn", "app:app", "--host", "127.0.0.1", "--port", str(port), "--no-access-log"],
    cwd=AI, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0,
)
try:
    for _ in range(120):
        if server.poll() is not None:
            raise RuntimeError("AI service stopped during startup; check model cache and pinned revision")
        try:
            with urlopen(f"http://127.0.0.1:{port}/health", timeout=1) as response:
                if response.status == 200:
                    break
        except OSError:
            time.sleep(.5)
    else:
        raise RuntimeError("AI service did not become ready within 60 seconds")
    command = [str(BACKEND / "gradlew.bat")] if sys.platform == "win32" else ["bash", "gradlew"]
    baseline = json.loads((BACKEND / "src/test/resources/evaluation/baseline-v1.json").read_text(encoding="utf-8"))
    report_dir = BACKEND / "build/reports/semantic-evaluation"
    for include_descriptions, label in [(False, "privacy-default"), (True, "synthetic-descriptions")]:
        env["ONFIT_AI_INCLUDE_PROJECT_DESCRIPTIONS"] = str(include_descriptions).lower()
        subprocess.run(command + ["semanticEvaluation", "--console=plain"], cwd=BACKEND, env=env, check=True)
        actual = json.loads((report_dir / "semantic-v1.json").read_text(encoding="utf-8"))
        scenario = report_dir / label
        scenario.mkdir(parents=True, exist_ok=True)
        for name in ("semantic-v1.json", "semantic-v1.md"):
            shutil.copy2(report_dir / name, scenario / name)
        improvements = regressions = 0
        for old_profile, new_profile in zip(baseline["profiles"], actual["profiles"], strict=True):
            assert old_profile["profile"] == new_profile["profile"]
            assert len(new_profile["rows"]) == 40
            for row in new_profile["rows"]:
                assert 0 <= row["score"]["semanticScore"] <= 100
            if old_profile["profile"] == "backend-aliases":
                continue  # normalization control, not an independent preference sample
            for old, new in zip(old_profile["judgments"], new_profile["judgments"], strict=True):
                improvements += int(not old["agrees"] and new["agrees"])
                regressions += int(old["agrees"] and not new["agrees"])
        if improvements < 1 or regressions > 0:
            raise RuntimeError(f"{label} semantic quality gate failed: {improvements} improvements, {regressions} regressions")
        print(f"{label}: {improvements} improved judgment(s), {regressions} regressions")
finally:
    server.terminate()
    try:
        server.wait(timeout=10)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait()
