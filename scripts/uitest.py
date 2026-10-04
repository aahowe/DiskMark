#!/usr/bin/env python3
"""UI tests for DiskMark driven by the Android CLI.

Reads the screen with `android layout`, captures screenshots with
`android screen capture`, and injects taps with `adb shell input`.
Elements are found by Compose test tags, which MainActivity exposes as
resource ids.

Usage: uitest.py --device SERIAL [--out DIR] [--bench] [test names...]
"""

import argparse
import json
import re
import subprocess
import sys
import time
import traceback
from pathlib import Path
from xml.sax.saxutils import escape

APP = "com.howe.diskmark"
ACTIVITY = f"{APP}/com.howe.diskmark.MainActivity"


class TestFailure(Exception):
    pass


class Device:
    def __init__(self, serial: str, out: Path):
        self.serial = serial
        self.out = out

    def adb(self, *args: str) -> str:
        return subprocess.run(["adb", "-s", self.serial, *args], check=True, capture_output=True, text=True).stdout

    def elements(self, idle: bool = True) -> list[dict]:
        # --no-idle while the progress bar animates, otherwise layout waits for an idle state that never comes.
        cmd = ["android", "layout", f"--device={self.serial}", "--flat"] + ([] if idle else ["--no-idle"])
        for _ in range(3):
            res = subprocess.run(cmd, capture_output=True, text=True)
            try:
                windows = json.loads(res.stdout)
                return [e for w in windows for e in w.get("content", [])]
            except json.JSONDecodeError:
                time.sleep(1)
        raise TestFailure(f"android layout failed: {res.stderr.strip() or res.stdout[:200]}")

    def find(self, rid: str, idle: bool = True) -> dict | None:
        return next((e for e in self.elements(idle) if e.get("resource-id") == rid), None)

    def text_of(self, rid: str, idle: bool = True, els: list[dict] | None = None) -> str | None:
        """Text of the element itself, or of its first text child (Compose buttons put text on a child node)."""
        els = els if els is not None else self.elements(idle)
        for i, e in enumerate(els):
            if e.get("resource-id") != rid:
                continue
            if "text" in e:
                return e["text"]
            bounds = _bounds(e)
            for child in els[i + 1 :]:
                if "text" in child and _inside(_bounds(child), bounds):
                    return child["text"]
            return ""
        return None

    def launch(self):
        self.adb("shell", "am", "start", "-S", "-W", "-n", ACTIVITY)
        self.wait_text("status", r"Ready", timeout=15)

    def tap(self, rid: str, idle: bool = True, timeout: float = 10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            e = self.find(rid, idle)
            if e:
                x, y = e["center"].strip("[]").split(",")
                self.adb("shell", "input", "tap", x, y)
                time.sleep(0.5)
                return
            time.sleep(0.5)
        raise TestFailure(f"element not found: {rid}")

    def select(self, name: str, value: str):
        self.tap(f"select_{name}")
        self.tap(f"option_{name}_{value}")

    def assert_text(self, rid: str, pattern: str, idle: bool = True, els: list[dict] | None = None):
        text = self.text_of(rid, idle, els)
        if text is None or not re.fullmatch(pattern, text, re.S):
            raise TestFailure(f"{rid}: expected /{pattern}/, got {text!r}")

    def assert_not_text(self, rid: str, pattern: str):
        text = self.text_of(rid)
        if text is None or re.fullmatch(pattern, text, re.S):
            raise TestFailure(f"{rid}: expected not /{pattern}/, got {text!r}")

    def wait_text(self, rid: str, pattern: str, timeout: float):
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            last = self.text_of(rid, idle=False)
            if last is not None and re.fullmatch(pattern, last, re.S):
                return
            time.sleep(1)
        raise TestFailure(f"timeout after {timeout}s waiting for {rid} /{pattern}/, last {last!r}")

    def wait_visible(self, text: str, timeout: float = 5):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(e.get("text") == text for e in self.elements(idle=False)):
                return
            time.sleep(0.5)
        raise TestFailure(f"text not visible after {timeout}s: {text}")

    def screenshot(self, name: str):
        self.out.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["android", "screen", "capture", f"--device={self.serial}", "-o", str(self.out / f"{name}.png")],
            check=True,
            capture_output=True,
        )


def _bounds(e: dict) -> tuple[int, ...]:
    return tuple(int(n) for n in re.findall(r"-?\d+", e["bounds"]))


def _inside(inner: tuple[int, ...], outer: tuple[int, ...]) -> bool:
    return outer[0] <= inner[0] and outer[1] <= inner[1] and inner[2] <= outer[2] and inner[3] <= outer[3]


TESTS = ["seq1m_q8t1", "seq1m_q1t1", "rnd4k_q32t1", "rnd4k_q1t1"]


def quick_settings(d: Device):
    """Shrink the benchmark so automated runs finish quickly."""
    d.select("count", "1")
    d.select("size", "64")
    d.select("duration", "1")
    d.assert_text("select_count", "1×")
    d.assert_text("select_size", "64MiB")
    d.assert_text("select_duration", "1s")


# ---- Tests -------------------------------------------------------------------


def test_launch(d: Device):
    d.launch()
    els = d.elements()  # one snapshot for all static checks
    names = {e.get("text") for e in els}
    for expected in ["DiskMark", "Read (MB/s)", "Write (MB/s)", "SEQ1M", "RND4K", "Q8T1", "Q1T1", "Q32T1"]:
        if expected not in names:
            raise TestFailure(f"text not visible: {expected}")
    d.assert_text("btn_all", "All", els=els)
    for t in TESTS:
        d.assert_text(f"read_{t}", r"0\.00", els=els)
        d.assert_text(f"write_{t}", r"0\.00", els=els)
    d.screenshot("01_launch")


def test_single(d: Device):
    d.launch()
    quick_settings(d)
    d.tap("btn_seq1m_q1t1")
    d.wait_text("status", "Done", timeout=60)
    d.assert_not_text("read_seq1m_q1t1", r"0\.00")
    d.assert_not_text("write_seq1m_q1t1", r"0\.00")
    d.assert_text("read_rnd4k_q1t1", r"0\.00")  # other rows stay untouched
    d.assert_text("device_info", r".*O_DIRECT.*")
    d.screenshot("02_single")


def test_full_run(d: Device):
    d.launch()
    quick_settings(d)
    d.tap("btn_all")
    d.assert_text("btn_all", "Stop", idle=False)
    d.wait_text("status", "Done", timeout=120)
    d.assert_text("btn_all", "All")
    for t in TESTS:
        d.assert_not_text(f"read_{t}", r"0\.00")
        d.assert_not_text(f"write_{t}", r"0\.00")
    d.screenshot("03_full_run_mbs")
    d.select("unit", "IOPS")
    d.wait_visible("Read (IOPS)")
    d.screenshot("03_full_run_iops")


def test_cancel(d: Device):
    d.launch()
    d.tap("btn_all")
    d.wait_text("status", r"SEQ1M Q8T1 Read.*", timeout=60)
    d.tap("btn_all", idle=False)
    d.wait_text("status", "Cancelled", timeout=15)
    d.assert_text("btn_all", "All")
    d.screenshot("04_cancel")


def test_benchmark(d: Device):
    """Full benchmark: 1GiB, 3 passes, 3s each. Results are printed from logcat."""
    d.launch()
    d.select("count", "3")
    d.select("size", "1024")
    d.select("duration", "3")
    d.adb("logcat", "-c")
    d.tap("btn_all")
    d.wait_text("status", "Done", timeout=900)
    d.screenshot("benchmark_mbs")
    log = d.adb("logcat", "-d", "-s", "DiskMark:I")
    for line in log.splitlines():
        if "CONFIG" in line or "RESULT" in line:
            print("    " + re.sub(r"^.*DiskMark\s*:\s*", "", line))


SMOKE = [test_launch, test_single, test_full_run, test_cancel]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", required=True)
    ap.add_argument("--out", default="build/uitest")
    ap.add_argument("--bench", action="store_true", help="also run the full benchmark")
    ap.add_argument("tests", nargs="*", help="test function names to run (default: smoke suite)")
    args = ap.parse_args()

    out = Path(args.out)
    d = Device(args.device, out)
    all_tests = {t.__name__: t for t in SMOKE + [test_benchmark]}
    tests = [all_tests[n] for n in args.tests] if args.tests else SMOKE + ([test_benchmark] if args.bench else [])

    results = []
    for t in tests:
        start = time.time()
        try:
            t(d)
            err = None
            print(f"[PASS] {t.__name__} ({time.time() - start:.0f}s)")
        except Exception as e:  # noqa: BLE001 - report every failure as a test failure
            err = e if isinstance(e, TestFailure) else traceback.format_exc()
            print(f"[FAIL] {t.__name__} ({time.time() - start:.0f}s): {err}")
            try:
                d.screenshot(f"FAIL_{t.__name__}")
            except Exception:  # noqa: BLE001
                pass
        results.append((t.__name__, time.time() - start, err))

    failed = sum(1 for r in results if r[2])
    out.mkdir(parents=True, exist_ok=True)
    cases = "".join(
        f'  <testcase name="{n}" time="{s:.1f}">'
        + (f'<failure message="{escape(str(e), {chr(34): "&quot;"})}"/>' if e else "")
        + "</testcase>\n"
        for n, s, e in results
    )
    (out / "report.xml").write_text(
        f'<testsuite name="DiskMark UI" tests="{len(results)}" failures="{failed}">\n{cases}</testsuite>\n'
    )
    print(f"{len(results) - failed}/{len(results)} passed · report: {out / 'report.xml'} · screenshots: {out}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
