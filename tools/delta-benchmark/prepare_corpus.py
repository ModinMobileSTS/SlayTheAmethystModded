"""Stage an actual repository README revision pair; no fabricated version updates."""
import hashlib
import json
from pathlib import Path
import subprocess
import argparse
import io
import zipfile

ROOT = Path(__file__).resolve().parents[2]
REVISIONS = ("444ca4fe", "ba538b93")


def unpacked(jar):
    """Deterministic length-framed sorted entry content; not a reconstructible JAR."""
    out = io.BytesIO()
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise ValueError("Duplicate archive entries")
        for name in sorted(names):
            if name.endswith("/"):
                continue
            encoded = name.encode("utf-8")
            data = archive.read(name)
            out.write(len(encoded).to_bytes(4,"big")); out.write(encoded)
            out.write(len(data).to_bytes(8,"big")); out.write(data)
    return out.getvalue()


def stage_jars(base, target, label):
    for mode in ("raw", "entries"):
        folder = ROOT / "tools/delta-benchmark/build/corpus-assets" / ("real-" + label + "-" + mode)
        folder.mkdir(parents=True, exist_ok=True)
        entries=[]
        for role, path in (("base",base),("target",target)):
            original=path.read_bytes()
            data=original if mode=="raw" else unpacked(path)
            if len(data)>16777216:
                raise ValueError("Corpus exceeds 16MiB input cap")
            (folder/(role+".bin")).write_bytes(data)
            entries.append(dict(role=role,artifact=path.name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),
                originalJarSha256=hashlib.sha256(original).hexdigest()))
        (folder/"source.json").write_text(json.dumps(dict(kind="real-maven-version-pair",label=label,
            mode=mode,source="local Gradle Maven artifact cache",license="Apache-2.0: kotlinx.coroutines",
            scope="Raw exact JAR bytes" if mode=="raw" else "Sorted length-framed uncompressed entries; excludes ZIP rebuild costs and metadata",
            entries=entries),indent=2)+"\n")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar-base",type=Path)
    parser.add_argument("--jar-target",type=Path)
    parser.add_argument("--label",default="coroutines-patch")
    args=parser.parse_args()
    if args.jar_base or args.jar_target:
        if not args.jar_base or not args.jar_target or not args.label.replace("-","").isalnum():
            parser.error("Supply both JAR paths and a simple label")
        stage_jars(args.jar_base,args.jar_target,args.label)
        return
    output = ROOT / "tools/delta-benchmark/build/corpus-assets/real-readme"
    output.mkdir(parents=True, exist_ok=True)
    entries = []
    for role, revision in zip(("base", "target"), REVISIONS):
        commit = subprocess.check_output(["git", "rev-parse", revision], cwd=ROOT, text=True).strip()
        data = subprocess.check_output(["git", "show", commit + ":README.md"], cwd=ROOT)
        (output / (role + ".bin")).write_bytes(data)
        entries.append(dict(role=role, commit=commit, path="README.md", bytes=len(data),
                            sha256=hashlib.sha256(data).hexdigest()))
    (output / "source.json").write_text(json.dumps({
        "kind": "real-repository-text-revisions", "source": "local Git repository",
        "scope": "Project README only; not representative of binary/JAR updates", "entries": entries,
    }, indent=2) + "\n")
    print((output / "source.json").read_text())


if __name__ == "__main__":
    main()
