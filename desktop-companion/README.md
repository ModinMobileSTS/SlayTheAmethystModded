# Slay the Amethyst Online Desktop Companion

Windows-oriented Rust system tray companion for Slay the Amethyst EasyTier
rooms. It uses the same `online-service` Room API and writes the credential-free
runtime state consumed by the bundled runtime-compat mod.

The companion discovers the Room API from the public cloud-control document used
by the Android launcher. Server URLs, player identity, and room credentials are
not edited in a local settings file.

Room sessions identify this desktop client as version `1.5.7-dev1`, using the
same semantic version format required by the Room API compatibility gate.

Right-click the tray icon to refresh rooms, create a room, or select a room.
The tray menu and connection notifications are localized in Chinese. A
successful connection opens a system dialog and copies the Together in Spire
address `host:33455` to the clipboard. The tray menu has no main window.

Only one companion instance can run at a time. A second launch shows a Chinese
notification and exits without starting another EasyTier worker.

## Development

Install a current Rust MSVC toolchain, then run from this directory:

```powershell
cargo run --release
```

Build a distributable executable with:

```powershell
cargo build --release
```

The executable is at `target\release\slay-the-amethyst-online.exe`.

## Automatic Configuration

At startup the companion fetches:

```text
https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/Resource/cloud-control.json
```

It reads the `easyTier.enabled` and `easyTier.roomApiBaseUrl` fields. The
service operator can change the Room API endpoint without rebuilding or asking
users to edit local files. The player name comes from the Windows account, and
the player ID is a stable hash of the local computer and account.

The release executable embeds the official EasyTier Windows x64 runtime files:
`easytier-core.exe`, `Packet.dll`, `wintun.dll`, and `WinDivert64.sys`. On first
startup it extracts these files to
`%APPDATA%\SlayTheAmethystOnline\runtime\easytier-core.exe` and starts it from
there. Users do not need to download or configure separate EasyTier files.

The companion requests Windows administrator permission at launch. EasyTier
needs elevation to add its firewall allowlist entry and create the Wintun/TUN
adapter. Approve the UAC prompt before joining a room; without elevation the
Room API session can start, but the virtual network adapter cannot be created.

Password-protected rooms are listed with a lock marker. Selecting one opens a
native password prompt; the password is held in memory only for the join
request and is not written to runtime state or disk. The `创建房间` menu item
opens prompts for room ID, optional description, and optional password. Room
creation uses the server's existing first-session auto-create flow and connects
the owner immediately after creation.

Runtime files are stored in `%APPDATA%\SlayTheAmethystOnline\runtime`:

- `connection-state.json`: credential-free state passed to the game JVM.
- `easytier.toml`: per-session EasyTier configuration. It contains the room
  network secret and is deleted when the companion disconnects.
- `easytier.log`: EasyTier child-process output.

Room API session and owner bearer tokens remain memory-only. They are never
written to disk or `connection-state.json`.

## Required Setup

1. Start an `online-service` instance with EasyTier room support enabled.
2. Start the companion, right-click its notification-area icon, refresh rooms,
   and select a room.

The companion bundles a fixed official EasyTier core version for a one-file
desktop deployment. Updating EasyTier requires rebuilding the companion with a
new reviewed upstream binary. The extracted runtime binary can be replaced for
local testing, but the companion restores its embedded version when the file
size changes.

## EasyTier Notice

EasyTier is an independent project: <https://github.com/EasyTier/EasyTier>.
The bundled upstream release is `v2.6.4`, licensed under LGPL-3.0. The source
archive is:

<https://github.com/EasyTier/EasyTier/releases/download/v2.6.4/easytier-windows-x86_64-v2.6.4.zip>

The embedded `easytier-core.exe` SHA-256 is
`da7eb2d24b5416f3d3407636949e964a0750e3f9dc53a828cb6799a57ead445d`.
The repository retains the upstream source location and license information
and keeps the extracted EasyTier files as replaceable separate runtime files.
