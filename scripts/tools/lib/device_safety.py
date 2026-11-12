"""Fail closed before package operations; discovery is not device authorization."""


def require_explicit_device(serial: str | None) -> str:
    value = (serial or "").strip()
    if (
        not value
        or value.lower() in {"auto", "default", "all", "*"}
        or value.startswith("-")
        or any(char.isspace() for char in value)
    ):
        raise RuntimeError(
            "An explicit device serial is required for app installation/uninstallation. "
            "Automatic device selection is not authorization; pass the user-approved target serial."
        )
    return value
