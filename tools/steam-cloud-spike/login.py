#!/usr/bin/env python3
"""Standalone interactive Steam login. Never invokes the JVM login tools."""
from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass, field
import getpass
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import time
import warnings
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "agent-tmp/steam-desktop-session.env"
sys.path.insert(0, str(Path(__file__).resolve().parent / "login"))


class LoginError(Exception):
    """Only locally authored, secret-free messages may be displayed."""


class SteamError(LoginError):
    def __init__(self, result: int):
        self.result = result
        descriptions = {
            5: "账号或密码不正确。",
            15: "Steam 拒绝了这次登录。",
            63: "Steam Guard 验证失败。",
            65: "验证码不正确，请重新输入。",
            84: "Steam 请求限流，请稍后再试，不要连续登录。",
            88: "验证码不正确，请重新输入。",
            93: "验证码已过期，请输入新的验证码。",
        }
        super().__init__(descriptions.get(result, "Steam 认证请求失败。") + f" (EResult={result})")


@dataclass
class DesktopSession:
    account_name: str
    steam_id: int
    refresh_token: str = field(repr=False)
    guard_data: str = field(default="", repr=False)

    def to_env(self) -> str:
        values = {
            "STEAM_ACCOUNT_NAME": self.account_name,
            "STEAM_STEAM_ID64": str(self.steam_id),
            "STEAM_REFRESH_TOKEN": self.refresh_token,
        }
        if self.guard_data:
            values["STEAM_GUARD_DATA"] = self.guard_data
        if not self.account_name or not self.refresh_token or self.steam_id <= 0:
            raise LoginError("Steam 未返回完整的登录会话。")
        if any(not isinstance(v, str) or any(c in v for c in "\r\n\x00") for v in values.values()):
            raise LoginError("Steam 会话字段格式无效，未保存文件。")
        return "# Sensitive Steam credentials. Do not commit or share this file.\n" + "".join(
            f"{key}={value}\n" for key, value in values.items()
        )


def save_session(path: Path, session: DesktopSession) -> None:
    """Write mode 0600 before any secret bytes, then atomically replace the old session."""
    contents = session.to_env()
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class Terminal:
    def line(self, prompt: str) -> str:
        return input(prompt).strip()

    def secret(self, prompt: str) -> str:
        # getpass normally falls back to echoing stdin. Fail closed instead.
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            try:
                value = getpass.getpass(prompt)
            except getpass.GetPassWarning:
                raise LoginError("无法隐藏输入，请在真实终端中运行登录脚本。") from None
        if not value:
            raise LoginError("输入不能为空。")
        return value

    def say(self, message: str) -> None:
        print(message, flush=True)


def load_dependencies() -> None:
    global requests, rsa, padding, proto
    try:
        import requests
        from cryptography.hazmat.primitives.asymmetric import rsa, padding
        import steam_auth_pb2 as proto
    except (ImportError, RuntimeError):
        raise LoginError(
            "登录依赖缺失或不兼容。请先运行：\n"
            "python3 -m pip install -r tools/steam-cloud-spike/login/requirements.txt"
        ) from None


class SteamApi:
    BASE = "https://api.steampowered.com/IAuthenticationService"

    def __init__(self, proxy: str | None = None, no_proxy: bool = False):
        self.http = requests.Session()
        # Explicit proxy selection must also override environment/NO_PROXY.
        self.http.trust_env = not (no_proxy or proxy)
        if proxy:
            parsed = urlsplit(proxy)
            if parsed.scheme not in ("http", "https") or not parsed.hostname:
                self.http.close()
                raise LoginError("代理必须是有效的 http:// 或 https:// 地址。")
            self.http.proxies = {"http": proxy, "https": proxy}
        elif not no_proxy and os.environ.get("STEAM_PROXY_URL"):
            proxy = os.environ["STEAM_PROXY_URL"]
            parsed = urlsplit(proxy)
            if parsed.scheme not in ("http", "https") or not parsed.hostname:
                self.http.close()
                raise LoginError("STEAM_PROXY_URL 必须是有效的 HTTP(S) 代理。")
            self.http.trust_env = False
            self.http.proxies = {"http": proxy, "https": proxy}
        self.http.headers.update({"User-Agent": "STS-Steam-Login/1.0", "Accept": "application/json"})

    def close(self) -> None:
        self.http.close()

    def call(self, method: str, message, timeout: float) -> dict:
        encoded = base64.b64encode(message.SerializeToString()).decode("ascii")
        is_get = method == "GetPasswordRSAPublicKey"
        options = {"params": {"format": "json"}, "timeout": timeout, "allow_redirects": False}
        if is_get:
            options["params"]["input_protobuf_encoded"] = encoded
        else:
            options["files"] = {"input_protobuf_encoded": (None, encoded)}
        try:
            response = self.http.request("GET" if is_get else "POST", f"{self.BASE}/{method}/v1/", **options)
        except requests.RequestException:
            # Exception text can contain proxy credentials or request bodies.
            raise LoginError("无法连接 Steam；请检查网络和代理后重试。") from None
        with response:
            if response.status_code == 429:
                raise SteamError(84)
            if response.status_code != 200:
                raise LoginError(f"Steam HTTPS 请求失败 (HTTP {response.status_code})。")
            try:
                body = response.json()
                result = int(response.headers.get("x-eresult", body.get("eresult", 1)))
                payload = body.get("response", {})
            except (ValueError, TypeError, AttributeError):
                raise LoginError("Steam 返回了无效的认证响应。") from None
            if result != 1:
                raise SteamError(result)
            if not isinstance(payload, dict):
                raise LoginError("Steam 返回了无效的认证响应。")
            return payload


def positive_id(value) -> int:
    try:
        number = int(value)
        if not 0 < number < 2**64:
            raise ValueError
        return number
    except (ValueError, TypeError, OverflowError):
        raise LoginError("Steam 返回了无效的会话标识。") from None


def token_steam_id(token: str) -> int:
    # Decode only for consistency checking, not as local signature verification.
    # The token itself is received from Steam over verified HTTPS.
    try:
        payload = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        if "client" not in claims.get("aud", []) or float(claims["exp"]) <= time.time():
            raise ValueError
        return positive_id(claims["sub"])
    except (ValueError, TypeError, KeyError, IndexError, AttributeError):
        raise LoginError("Steam 返回的 token 无效或不能用于桌面 CM 登录。") from None


class InteractiveLogin:
    GUARDS = {4: "在 Steam 手机应用中确认（推荐）", 3: "输入手机令牌验证码", 2: "输入邮箱验证码", 5: "通过邮件确认"}

    def __init__(self, api, terminal, timeout: float, clock=time.monotonic, sleep=time.sleep):
        self.api, self.terminal = api, terminal
        self.clock, self.sleep = clock, sleep
        self.timeout = timeout

    def call(self, method, message):
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise LoginError("登录等待超时；旧会话文件未修改，请重新运行。")
        return self.api.call(method, message, min(30, remaining))

    def run(self, username: str | None) -> DesktopSession:
        username = username or self.terminal.line("Steam 账号（不是昵称）: ")
        if not username or any(c in username for c in "\r\n\x00"):
            raise LoginError("请输入有效的 Steam 账号。")
        password = self.terminal.secret("Steam 密码（隐藏输入）: ")
        self.deadline = self.clock() + self.timeout
        self.terminal.say("正在安全连接 Steam…")
        key = self.call("GetPasswordRSAPublicKey", proto.GetPasswordRSAPublicKeyRequest(account_name=username))
        try:
            public = rsa.RSAPublicNumbers(int(key["publickey_exp"], 16), int(key["publickey_mod"], 16)).public_key()
            encrypted = base64.b64encode(public.encrypt(password.encode("utf-8"), padding.PKCS1v15())).decode("ascii")
            timestamp = positive_id(key["timestamp"])
        except (KeyError, ValueError, TypeError):
            raise LoginError("无法使用 Steam 返回的 RSA 公钥加密密码。") from None
        finally:
            del password
        begin = self.call("BeginAuthSessionViaCredentials", proto.BeginAuthSessionViaCredentialsRequest(
            device_friendly_name="STS Python Desktop Login", account_name=username,
            encrypted_password=encrypted, encryption_timestamp=timestamp,
            remember_login=True, platform_type=1, persistence=1, website_id="Unknown",
            device_details=proto.DeviceDetails(device_friendly_name="STS Python Desktop Login", platform_type=1, gaming_device_type=1),
        ))
        client_id = positive_id(begin.get("client_id"))
        steam_id = positive_id(begin.get("steamid"))
        try:
            request_id = base64.b64decode(begin["request_id"], validate=True)
            interval = float(begin.get("interval", 2))
            if not request_id or not math.isfinite(interval):
                raise ValueError
            interval = min(30, max(1, interval))
            guards = {int(item["confirmation_type"]) for item in begin.get("allowed_confirmations", [])}
        except (KeyError, TypeError, ValueError):
            raise LoginError("Steam 返回了无效的登录挑战。") from None
        self.complete_guard(client_id, steam_id, guards)
        self.terminal.say("等待 Steam 完成认证…（Ctrl+C 可取消，旧会话保持不变）")
        while True:
            result = self.call("PollAuthSessionStatus", proto.PollAuthSessionStatusRequest(client_id=client_id, request_id=request_id))
            token = result.get("refresh_token")
            if token:
                if token_steam_id(token) != steam_id:
                    raise LoginError("Steam 会话账号不一致，未保存文件。")
                session = DesktopSession(result.get("account_name") or username, steam_id, token, result.get("new_guard_data") or "")
                session.to_env()  # Reject invalid fields before reporting success.
                return session
            if result.get("new_client_id"):
                client_id = positive_id(result["new_client_id"])
            self.sleep(min(interval, max(0, self.deadline - self.clock())))

    def complete_guard(self, client_id: int, steam_id: int, guards: set[int]) -> None:
        if 1 in guards:
            return
        choices = [kind for kind in self.GUARDS if kind in guards]
        if not choices:
            raise LoginError("Steam 未提供可用的验证方式；请检查账号的 Steam Guard 设置。")
        selected = choices[0]
        if len(choices) > 1:
            for number, kind in enumerate(choices, 1):
                self.terminal.say(f"  {number}. {self.GUARDS[kind]}")
            while True:
                answer = self.terminal.line("选择验证方式 [1]: ") or "1"
                if answer.isdigit() and 1 <= int(answer) <= len(choices):
                    selected = choices[int(answer) - 1]
                    break
                self.terminal.say("请输入列表中的编号。")
        if selected in (4, 5):
            self.terminal.say("请在 Steam 手机应用中确认这次登录。" if selected == 4 else "请打开 Steam 验证邮件并确认这次登录。")
            return
        for attempt in range(3):
            code = self.terminal.secret("Steam 手机令牌验证码: " if selected == 3 else "Steam 邮箱验证码: ").strip()
            if not code:
                raise LoginError("验证码不能为空。")
            try:
                self.call("UpdateAuthSessionWithSteamGuardCode", proto.UpdateAuthSessionWithSteamGuardCodeRequest(
                    client_id=client_id, steamid=steam_id, code=code, code_type=selected,
                ))
                return
            except SteamError as error:
                if error.result not in (65, 88, 93) or attempt == 2:
                    raise
                self.terminal.say(str(error))


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="独立 Python Steam 交互登录；不会读取旧 token 或修改云存档。")
    parser.add_argument("--username", help="Steam 账号；不指定则交互输入")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="本机会话文件（默认仓库 agent-tmp 下）")
    parser.add_argument("--overwrite", action="store_true", help="明确允许替换已有会话，否则交互确认")
    parser.add_argument("--timeout", type=float, default=300, help="认证/验证码等待秒数（默认 300）")
    proxy = parser.add_mutually_exclusive_group()
    proxy.add_argument("--proxy-url", help="HTTP(S) 代理；不在日志中输出地址")
    proxy.add_argument("--no-proxy", action="store_true", help="忽略所有代理环境变量，强制直连")
    args, unknown = parser.parse_known_args(argv)
    if unknown:
        # Do not echo unsupported --password/--token arguments and their values.
        parser.error("存在不支持的参数；密码和验证码只能交互输入，请查看 --help")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout 必须是大于零的有限秒数")
    return args


def main(argv=None) -> int:
    args = parse_args(argv)
    terminal = Terminal()
    api = None
    try:
        if not sys.stdin.isatty():
            raise LoginError("请在交互终端中运行此脚本；不接受管道中的密码或验证码。")
        load_dependencies()
        # Keep the final component unresolved so symlinks are never followed.
        output = args.output.expanduser().absolute()
        if output.is_symlink():
            raise LoginError("会话输出不能是符号链接，请选择普通文件路径。")
        if output.exists() and not args.overwrite:
            if terminal.line("已有本机会话。重新登录成功后替换它？[y/N]: ").lower() != "y":
                terminal.say("已取消；现有会话未修改。")
                return 0
        api = SteamApi(args.proxy_url, args.no_proxy)
        session = InteractiveLogin(api, terminal, args.timeout).run(args.username)
        save_session(output, session)
        terminal.say(f"登录成功。会话已保存到：{output}")
        terminal.say("密码未保存；token 不会回显。现在可以运行 Steam Cloud 读取测试。")
        if os.name == "nt":
            terminal.say("Windows：请确保该目录的 ACL 仅允许当前用户访问。")
        return 0
    except (KeyboardInterrupt, EOFError):
        print("\n已取消；现有会话未修改。", file=sys.stderr)
        return 130
    except LoginError as error:
        print(f"登录失败：{error}", file=sys.stderr)
        return 1
    except OSError:
        print("无法安全保存会话文件；请检查目录权限和可用空间。", file=sys.stderr)
        return 1
    except Exception:
        # Never dump raw responses, exception values or tracebacks from an auth flow.
        print("登录遇到异常，未输出任何凭据信息；请检查依赖和网络后重试。", file=sys.stderr)
        return 1
    finally:
        if api is not None:
            api.close()


if __name__ == "__main__":
    raise SystemExit(main())
