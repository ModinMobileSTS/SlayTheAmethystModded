from __future__ import annotations

import argparse
import sys

from .commands import build_debug, package_cloud_function, prepare_release, release_build, repo_root
from . import doctor


def add_release_args(parser: argparse.ArgumentParser, *, fast_variant: bool = False) -> None:
    parser.add_argument("--store-file", "-StoreFile", default="")
    parser.add_argument("--key-alias", "-KeyAlias", default="")
    if fast_variant:
        parser.add_argument("--run-lint-check", "-RunLintCheck", action="store_true")
    else:
        parser.add_argument("--skip-lint-check", "-SkipLintCheck", action="store_true")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SlayTheAmethyst build entrypoint.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    debug = subparsers.add_parser("debug")
    debug.add_argument("--application-id", "-ApplicationId", default="io.stamethyst.debug")

    for name in ("release", "release-fast", "release-full"):
        add_release_args(subparsers.add_parser(name))
    for name in ("fast-release", "fast-release-slim", "fast-release-full"):
        add_release_args(subparsers.add_parser(name), fast_variant=True)

    prepare = subparsers.add_parser("prepare-release")
    prepare.add_argument("--store-file", "-StoreFile", default="")
    prepare.add_argument("--key-alias", "-KeyAlias", default="")
    prepare.add_argument("--skip-local-check", "-SkipLocalCheck", action="store_true")

    package = subparsers.add_parser("package-cloud-function")
    package.add_argument("--source-dir", "-SourceDir", default=str(repo_root() / "cloud-function"))
    package.add_argument("--output-zip", "-OutputZip", default=str(repo_root() / "artifacts" / "cloud-function-scf.zip"))

    doctor_parser = subparsers.add_parser("doctor", help="Check the local build environment.")
    doctor_parser.add_argument("--verbose", "-Verbose", action="store_true")
    doctor_parser.add_argument("--no-color", "-NoColor", action="store_true")
    doctor_parser.add_argument("--json", "-Json", action="store_true")
    doctor_parser.add_argument("--no-network", "-NoNetwork", action="store_true", help="Skip the network reachability check.")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = create_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "debug":
            build_debug(args.application_id)
        elif args.command in {"release", "release-fast"}:
            release_build(
                store_file=args.store_file,
                key_alias=args.key_alias,
                skip_lint_check=args.skip_lint_check,
                skip_native_cache_cleanup=False,
                gradle_tasks=[":app:assembleRelease"],
                output_dirs=["app/build/outputs/apk/release"],
                display_name="Slim release build",
                fast=False,
            )
        elif args.command == "release-full":
            release_build(
                store_file=args.store_file,
                key_alias=args.key_alias,
                skip_lint_check=args.skip_lint_check,
                skip_native_cache_cleanup=False,
                gradle_tasks=[":app:assembleFullRelease"],
                output_dirs=["app/build/outputs/apk/fullRelease"],
                display_name="Full release build",
                fast=False,
            )
        elif args.command in {"fast-release", "fast-release-slim"}:
            release_build(
                store_file=args.store_file,
                key_alias=args.key_alias,
                skip_lint_check=not args.run_lint_check,
                skip_native_cache_cleanup=True,
                gradle_tasks=[":app:assembleFastSlimRelease"],
                output_dirs=["app/build/outputs/apk/fastSlimRelease"],
                display_name="Fast slim release build",
                fast=True,
            )
        elif args.command == "fast-release-full":
            release_build(
                store_file=args.store_file,
                key_alias=args.key_alias,
                skip_lint_check=not args.run_lint_check,
                skip_native_cache_cleanup=True,
                gradle_tasks=[":app:assembleFastFullRelease"],
                output_dirs=["app/build/outputs/apk/fastFullRelease"],
                display_name="Fast full release build",
                fast=True,
            )
        elif args.command == "prepare-release":
            prepare_release(args.store_file, args.key_alias, args.skip_local_check)
        elif args.command == "package-cloud-function":
            package_cloud_function(args.source_dir, args.output_zip)
        elif args.command == "doctor":
            return doctor.run(
                verbose=args.verbose,
                no_color=args.no_color,
                as_json=args.json,
                no_network=args.no_network,
            )
        return 0
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
