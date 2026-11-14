"""Explicit authorized-device runner; workspace local_adb only, never installs APKs."""
import argparse
import itertools
import json
from pathlib import Path
import random
from statistics import median
import subprocess
import time
import uuid

ENGINES = ["native-xdelta3-deflate6", "native-hdiff-single-deflate6",
           "native-bsdiff43-deflate6", "jvm-bsdiff43-deflate6", "replace-deflate6",
           "native-hdiff-stream256-raw", "native-xdelta-stream256-raw",
           "native-hdiff-stream1024-raw", "native-xdelta-stream1024-raw",
           "native-xdelta-stream1024-src32m-raw", "native-xdelta-stream1024-src4m-raw",
           "native-xdelta-stream1024-src8m-raw", "native-hdiff-stream256-deflate6",
           "native-xdelta-stream1024-src8m-deflate6"]
ROOT = Path(__file__).resolve().parents[2]


def schedule(engines, sizes, fixtures, repeats, seed, real_fixtures=None):
    items = [(e, n, f, r) for e, n, f in itertools.product(engines, sizes, fixtures)
             for r in range(repeats)]
    # Real fixture is independent of requested synthetic size: include it once/engine/repeat.
    if real_fixtures is None:
        real_fixtures=["real-readme"]
    items += [(e, 0, f, r) for e in engines for f in real_fixtures for r in range(repeats)]
    random.Random(seed).shuffle(items)
    return items


def parse_row(text):
    if "INSTRUMENTATION_CODE: -1" not in text or "Delta phase PASS" not in text:
        raise ValueError("Instrumentation failed: " + text[-2000:])
    prefix = "INSTRUMENTATION_RESULT: row="
    rows = [json.loads(line[len(prefix):]) for line in text.splitlines() if line.startswith(prefix)]
    if len(rows) != 1 or rows[0].get("schema") != 2 or rows[0].get("success") is not True:
        raise ValueError("Missing or invalid phase observation")
    return rows[0]


def summarize(rows):
    groups = {}
    for row in rows:
        key = (row["engine"],row["fixture"],row["baseBytes"],row["phase"])
        groups.setdefault(key,[]).append(row)
    output = []
    for (engine, fixture, size, phase), values in sorted(groups.items()):
        for field in ("baseSha256","targetSha256","patchBytes","fingerprint","nativePins","harnessVersion"):
            if len({v[field] for v in values}) != 1:
                raise ValueError("Mixed inputs/configurations: " + field)
        output.append(dict(engine=engine,fixture=fixture,baseBytes=size,phase=phase,samples=len(values),
            patchBytes=values[0]["patchBytes"],medianMs=median(v["elapsedNs"] for v in values)/1e6,
            minMs=min(v["elapsedNs"] for v in values)/1e6,maxMs=max(v["elapsedNs"] for v in values)/1e6,
            sampledRssPeakKb=max(v["sampledRssPeakKb"] for v in values),
            maxRssIncreaseKb=max(v["sampledRssPeakKb"]-v["baselineRssKb"] for v in values),
            maxBatteryTempDeciC=max(v["batteryTempAfterDeciC"] for v in values),
            maxThermalStatus=max(v["thermalAfter"] for v in values)))
    return output


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--engines", nargs="+", choices=ENGINES, default=ENGINES[:5])
    parser.add_argument("--sizes", nargs="*", type=int, default=[1048576,4194304])
    parser.add_argument("--fixtures", nargs="*", default=["insert-middle","delete-middle","move-quarter","sparse"])
    parser.add_argument("--repeats",type=int,default=3)
    parser.add_argument("--real-fixtures",nargs="*",default=["real-readme"])
    parser.add_argument("--seed",type=int,default=20261114)
    parser.add_argument("--max-battery-temp",type=int,default=400,help="deci-C, safety gate not CPU temperature")
    return parser


def main():
    parser=argument_parser()
    args=parser.parse_args()
    if args.repeats<1 or args.repeats>10 or any(n<1024 or n>67108864 for n in args.sizes):
        parser.error("invalid repeats/size")
    args.output.mkdir(parents=True, exist_ok=False)
    def adb(command,timeout=120):
        return subprocess.run(["python3","-m","scripts.tools.local_adb","adb","--",*command],
            cwd=ROOT,text=True,capture_output=True,timeout=timeout,check=True).stdout
    model=adb(["shell","getprop","ro.product.model"]).strip()
    if model!=args.model:
        raise RuntimeError("Device model mismatch: " + model)
    jobs=schedule(args.engines,args.sizes,args.fixtures,args.repeats,args.seed,args.real_fixtures)
    (args.output/"schedule.json").write_text(json.dumps(jobs,indent=2))
    rows=[]
    failures=[]
    for index,(engine,size,fixture,repeat) in enumerate(jobs):
        # Poll only synchronous device state, not background jobs. Never alter charging/thermal settings.
        ready=False
        for attempt in range(20):
            battery=adb(["shell","dumpsys","battery"])
            (args.output/f"{index:04}-battery-{attempt}.txt").write_text(battery)
            temps=[int(line.split(":",1)[1]) for line in battery.splitlines() if line.strip().startswith("temperature:")]
            if temps and temps[0]<args.max_battery_temp:
                ready=True;break
            time.sleep(15)
        if not ready:
            raise RuntimeError("Battery temperature unavailable/high; batch stopped")
        token="batch-"+uuid.uuid4().hex
        pair=[]
        for phase in ("prepare","generate","apply"):
            command=["shell","am","instrument","-w","-r","-e","token",token,"-e","engine",engine,
                "-e","phase",phase,"-e","size",str(size),"-e","fixture",fixture,
                "io.stamethyst.deltabench/.PhaseInstrumentation"]
            text=adb(command)
            (args.output/f"{index:04}-{phase}.log").write_text(text)
            if "INSTRUMENTATION_CODE: -1" not in text or "Delta phase PASS" not in text:
                failures.append(dict(engine=engine,fixture=fixture,size=size,repeat=repeat,
                    phase=phase,log=f"{index:04}-{phase}.log"))
                (args.output/"failures.json").write_text(json.dumps(failures,indent=2))
                print(index+1,"/",len(jobs),engine,fixture,size,"FAILED",phase,flush=True)
                break
            if phase=="prepare":
                if "INSTRUMENTATION_CODE: -1" not in text or "Delta phase PASS" not in text:
                    raise RuntimeError("Prepare failed: "+text)
                continue
            row=parse_row(text)
            if row["engine"]!=engine or row["phase"]!=phase or row["token"]!=token:
                raise ValueError("Phase identity mismatch")
            if row["thermalBefore"]>=3 or row["thermalAfter"]>=3:
                raise RuntimeError("Severe thermal throttling: batch stopped; logs preserved")
            row.update(repeat=repeat,order=index,scheduleSeed=args.seed)
            pair.append(row)
        if len(pair)!=2:
            continue
        if pair[1].get("verified") is not True or any(pair[0][k]!=pair[1][k] for k in
                ("baseSha256","targetSha256","patchBytes")):
            raise ValueError("Generation/application identity or verification mismatch")
        rows.extend(pair)
        (args.output/"observations.jsonl").write_text("".join(json.dumps(r)+"\n" for r in rows))
        (args.output/"summary.json").write_text(json.dumps(summarize(rows),indent=2)+"\n")
        print(index+1,"/",len(jobs),engine,fixture,size,"PASS",flush=True)
    print("COMPLETE:",len(rows)//2,"verified pairs;",len(failures),"failed pairs; phase-isolated process-first executions (no warmup)")


if __name__=="__main__":
    main()
