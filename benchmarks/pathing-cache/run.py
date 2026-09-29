import argparse
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "622977066159bd47ac9708fb4842dc3a87f2b63c"
parser = argparse.ArgumentParser()
parser.add_argument("--baseline", type=Path, required=True)
parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
parser.add_argument("--forks", type=int, default=4)
parser.add_argument("--warmup", type=int, default=20)
parser.add_argument("--samples", type=int, default=30)
args = parser.parse_args()
if args.java_home is None:
    parser.error("set JAVA_HOME to JDK 21 or pass --java-home")
baseline = args.baseline.resolve()
if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=baseline, text=True).strip() != BASELINE:
    parser.error("baseline must be checked out at " + BASELINE)
subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "src", "build.gradle", "gradle.properties"], cwd=baseline, check=True)
output = ROOT / "build/pathing-cache"
output.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(args.java_home))
suffix = ".exe" if os.name == "nt" else ""
java = args.java_home / "bin" / ("java" + suffix)
javac = args.java_home / "bin" / ("javac" + suffix)
version = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True).stderr
if 'version "21.' not in version:
    parser.error("this benchmark requires JDK 21")
for name, repo in [("baseline", baseline), ("candidate", ROOT)]:
    gradle = repo / ("gradlew.bat" if os.name == "nt" else "gradlew")
    with (output / (name + "-build.log")).open("w") as log:
        subprocess.run([str(gradle), "pathingCacheClasspath", "-I", str(ROOT / "benchmarks/pathing-cache/classpath.gradle"),
                        "-Pavailable_loaders=", "-q"], cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
acp = baseline / "build/pathing-cache/runtime-classpath.txt"
bcp = output / "runtime-classpath.txt"
classes = output / "harness"
classes.mkdir(exist_ok=True)
sources = sorted((ROOT / "benchmarks/pathing-cache/java").rglob("*.java"))
subprocess.run([str(javac), "-proc:none", "--release", "21", "-cp", bcp.read_text(), "-d", str(classes), *map(str, sources)], check=True)
subprocess.run([str(java), "-cp", str(classes) + os.pathsep + bcp.read_text(), "baritone.pathing.calc.CacheChecks"], cwd=ROOT, check=True)
classpath = str(classes) + os.pathsep + acp.read_text()
metrics = {}
for fork in range(args.forks):
    # alternate which cache size goes first between independent JVM forks
    for size in ([65536, 16384] if fork % 2 == 0 else [16384, 65536]):
        path = output / f"paired-{size}-{fork}.log"
        print(f"fork {fork + 1}/{args.forks}, size {size}", flush=True)
        with path.open("w") as log:
            subprocess.run([str(java), "-Xms512m", "-Xmx1g", "-XX:+UseG1GC", "-cp", classpath,
                            "baritone.performance.PairedBench", str(acp), str(bcp), str(size),
                            str(args.warmup), str(args.samples)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
        rows = [line.split(",") for line in path.read_text().splitlines() if line.startswith("PAIR,")]
        if len(rows) != 6 * args.samples:
            raise RuntimeError("missing benchmark samples: " + str(path))
        for row in rows:
            key = f"{size}/{row[2]}"
            metrics.setdefault(key, []).append([fork, *map(float, row[4:6]), *map(int, row[6:10])])
summary = {"baseline": BASELINE, "candidate": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
           "candidate_dirty": bool(subprocess.check_output(["git", "diff", "HEAD", "--", "src"], cwd=ROOT)),
           "platform": platform.platform(), "java": version, "forks": args.forks, "warmup": args.warmup, "pairs_per_case_per_fork": args.samples,
           "rows": {}}
for key, rows in metrics.items():
    medians = [[statistics.median(r[column] for r in rows if r[0] == fork) for fork in range(args.forks)] for column in range(1, 7)]
    before, after, bbytes, abytes, bmisses, amisses = map(statistics.median, medians)
    reductions = [100 * (1 - a / b) for b, a in zip(medians[0], medians[1])]
    summary["rows"][key] = {"baseline_ms": before, "candidate_ms": after, "time_reduction_percent": 100 * (1 - after / before),
                           "baseline_bytes": bbytes, "candidate_bytes": abytes, "baseline_misses": bmisses, "candidate_misses": amisses,
                           "fork_reduction_percent": reductions, "pairs_faster": sum(r[2] < r[1] for r in rows), "pairs": len(rows)}
    print(f"{key}: {before:.3f} -> {after:.3f} ms, {bbytes:.0f} -> {abytes:.0f} bytes; {min(reductions):.1f}%..{max(reductions):.1f}% across forks")
(output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
