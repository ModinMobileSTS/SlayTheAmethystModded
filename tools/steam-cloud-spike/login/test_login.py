"""Offline acceptance tests. No Steam account, network, or cloud mutation is used."""
from __future__ import annotations

import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "login.py"
spec = importlib.util.spec_from_file_location("steam_login", SCRIPT)
login = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = login
spec.loader.exec_module(login)
login.load_dependencies()

STEAM_ID = 76561198000000001


def token(steam_id=STEAM_ID, aud=None, exp=None):
    claims = {"sub": str(steam_id), "aud": aud or ["client", "derive"], "exp": exp or time.time() + 3600}
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=")
    return f"header.{payload}.signature"


class FakeTerminal:
    def __init__(self, lines=(), secrets=(" password with spaces ",)):
        self.lines, self.secrets = iter(lines), iter(secrets)
        self.messages = []

    def line(self, prompt):
        return next(self.lines)

    def secret(self, prompt):
        return next(self.secrets)

    def say(self, message):
        self.messages.append(message)


class FakeApi:
    def __init__(self, guards=(1,), polls=None, submit_results=()):
        self.calls = []
        self.guards = guards
        self.polls = iter(polls or [{"refresh_token": token(), "account_name": "test_account", "new_guard_data": "guard-secret"}])
        self.submit_results = iter(submit_results)
        self.key = login.rsa.generate_private_key(public_exponent=65537, key_size=2048)

    def call(self, method, message, timeout):
        self.calls.append((method, message, timeout))
        if method == "GetPasswordRSAPublicKey":
            numbers = self.key.public_key().public_numbers()
            return {"publickey_mod": format(numbers.n, "x"), "publickey_exp": format(numbers.e, "x"), "timestamp": "123"}
        if method == "BeginAuthSessionViaCredentials":
            return {"client_id": "123", "steamid": str(STEAM_ID), "request_id": "AQID", "interval": 1,
                    "allowed_confirmations": [{"confirmation_type": kind} for kind in self.guards]}
        if method == "UpdateAuthSessionWithSteamGuardCode":
            outcome = next(self.submit_results, {})
        elif method == "PollAuthSessionStatus":
            outcome = next(self.polls)
        else:
            raise AssertionError("Unexpected API")
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


class FlowTests(unittest.TestCase):
    def run_login(self, api=None, terminal=None, **kwargs):
        api = api or FakeApi()
        terminal = terminal or FakeTerminal()
        session = login.InteractiveLogin(api, terminal, 300, sleep=lambda _: None, **kwargs).run("test_account")
        return session, api, terminal

    def test_password_is_rsa_encrypted_and_whitespace_preserved(self):
        session, api, ui = self.run_login()
        begin = api.calls[1][1]
        decrypted = api.key.decrypt(base64.b64decode(begin.encrypted_password), login.padding.PKCS1v15())
        self.assertEqual(b" password with spaces ", decrypted)
        self.assertEqual(1, begin.platform_type)
        self.assertEqual(1, begin.device_details.platform_type)
        self.assertEqual(1, begin.persistence)
        self.assertTrue(begin.remember_login)
        self.assertNotIn(session.refresh_token, repr(session))
        self.assertNotIn(session.guard_data, repr(session))
        self.assertNotIn(" password with spaces ", "\n".join(ui.messages))
        self.assertNotIn(session.refresh_token, "\n".join(ui.messages))

    def test_username_prompt(self):
        session = login.InteractiveLogin(FakeApi(), FakeTerminal(lines=["test_account"]), 300).run(None)
        self.assertEqual("test_account", session.account_name)

    def test_device_approval_is_preferred_and_does_not_submit_code(self):
        session, api, ui = self.run_login(FakeApi(guards=[3, 4]), FakeTerminal(lines=[""]))
        self.assertFalse(any(name == "UpdateAuthSessionWithSteamGuardCode" for name, _, _ in api.calls))
        self.assertTrue(any("手机应用" in message for message in ui.messages))
        self.assertEqual(STEAM_ID, session.steam_id)

    def test_choose_code_instead_of_device_approval(self):
        _, api, _ = self.run_login(FakeApi(guards=[4, 3]), FakeTerminal(lines=["0", "bad", "2"], secrets=["pw", "CODE"]))
        submitted = next(message for name, message, _ in api.calls if name == "UpdateAuthSessionWithSteamGuardCode")
        self.assertEqual(3, submitted.code_type)
        self.assertEqual("CODE", submitted.code)

    def test_email_and_device_codes(self):
        for kind in (2, 3):
            with self.subTest(kind=kind):
                _, api, _ = self.run_login(FakeApi(guards=[kind]), FakeTerminal(secrets=["pw", " CODE "]))
                message = api.calls[2][1]
                self.assertEqual(STEAM_ID, message.steamid)
                self.assertEqual(kind, message.code_type)
                self.assertEqual("CODE", message.code)
                self.assertEqual(123, message.client_id)

    def test_email_confirmation_needs_no_code(self):
        _, api, ui = self.run_login(FakeApi(guards=[5]))
        self.assertEqual(3, len(api.calls))
        self.assertTrue(any("邮件" in message for message in ui.messages))

    def test_wrong_and_expired_code_can_be_retried(self):
        for result in (65, 88, 93):
            with self.subTest(result=result):
                _, api, _ = self.run_login(FakeApi(guards=[3], submit_results=[login.SteamError(result), {}]),
                                           FakeTerminal(secrets=["pw", "wrong", "right"]))
                submissions = [message.code for name, message, _ in api.calls if name == "UpdateAuthSessionWithSteamGuardCode"]
                self.assertEqual(["wrong", "right"], submissions)

    def test_three_code_failures_stop(self):
        with self.assertRaises(login.SteamError):
            self.run_login(FakeApi(guards=[3], submit_results=[login.SteamError(88)] * 3),
                           FakeTerminal(secrets=["pw", "a", "b", "c"]))

    def test_rate_limit_is_not_retried(self):
        api = FakeApi(guards=[3], submit_results=[login.SteamError(84)])
        with self.assertRaises(login.SteamError):
            self.run_login(api, FakeTerminal(secrets=["pw", "code"]))
        self.assertEqual(3, len(api.calls))

    def test_unsupported_guard_does_not_poll(self):
        for guards in ([], [0], [6]):
            with self.subTest(guards=guards), self.assertRaises(login.LoginError):
                self.run_login(FakeApi(guards=guards))

    def test_poll_client_id_rotation_and_request_bytes(self):
        _, api, _ = self.run_login(FakeApi(polls=[{"new_client_id": "456"}, {"refresh_token": token()}]))
        polls = [message for name, message, _ in api.calls if name == "PollAuthSessionStatus"]
        self.assertEqual([123, 456], [m.client_id for m in polls])
        self.assertTrue(all(m.request_id == b"\x01\x02\x03" for m in polls))

    def test_mismatched_account_token_is_rejected(self):
        with self.assertRaises(login.LoginError):
            self.run_login(FakeApi(polls=[{"refresh_token": token(STEAM_ID + 1)}]))

    def test_expired_web_and_malformed_tokens_rejected(self):
        for value in ("secret", token(aud=["web"]), token(exp=time.time() - 1)):
            with self.subTest(value=value), self.assertRaises(login.LoginError):
                login.token_steam_id(value)

    def test_timeout_stops_polling(self):
        ticks = iter([0, 0, 0, 301])
        with self.assertRaisesRegex(login.LoginError, "超时"):
            self.run_login(clock=lambda: next(ticks))


class HttpTests(unittest.TestCase):
    def response(self, body=None, result="1", status=200):
        response = Mock()
        response.status_code = status
        response.headers = {"x-eresult": result}
        response.json.return_value = body if body is not None else {"response": {}}
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        return response

    def call(self, response, method="GetPasswordRSAPublicKey"):
        with patch.object(login.requests, "Session") as factory:
            http = factory.return_value
            http.request.return_value = response
            api = login.SteamApi(no_proxy=True)
            try:
                result = api.call(method, login.proto.GetPasswordRSAPublicKeyRequest(account_name="test"), 17)
                return result, http
            finally:
                api.close()

    def test_get_sends_protobuf_and_requests_json(self):
        _, http = self.call(self.response())
        args, kwargs = http.request.call_args
        self.assertEqual("GET", args[0])
        self.assertTrue(args[1].startswith("https://api.steampowered.com/"))
        message = login.proto.GetPasswordRSAPublicKeyRequest.FromString(base64.b64decode(kwargs["params"]["input_protobuf_encoded"]))
        self.assertEqual("test", message.account_name)
        self.assertEqual("json", kwargs["params"]["format"])
        self.assertEqual(17, kwargs["timeout"])
        self.assertFalse(kwargs["allow_redirects"])
        self.assertNotIn("verify", kwargs)
        http.close.assert_called_once()

    def test_post_sends_multipart(self):
        _, http = self.call(self.response(), "BeginAuthSessionViaCredentials")
        args, kwargs = http.request.call_args
        self.assertEqual("POST", args[0])
        self.assertIn("input_protobuf_encoded", kwargs["files"])
        self.assertNotIn("input_protobuf_encoded", kwargs["params"])

    def test_error_header_is_handled_without_server_message(self):
        with self.assertRaises(login.SteamError) as error:
            self.call(self.response({"response": {"error": "secret-from-server"}}, result="5"))
        self.assertNotIn("secret-from-server", str(error.exception))
        self.assertEqual(5, error.exception.result)

    def test_http_errors_and_redirects_are_not_followed(self):
        for status in (302, 400, 429, 500):
            with self.subTest(status=status), self.assertRaises(login.LoginError):
                self.call(self.response(status=status))

    def test_malformed_response_is_rejected(self):
        for body in ([], "raw-secret", {"response": []}):
            with self.subTest(body=body), self.assertRaises(login.LoginError):
                self.call(self.response(body))

    def test_network_exception_is_redacted(self):
        with patch.object(login.requests, "Session") as factory:
            factory.return_value.request.side_effect = login.requests.RequestException("proxy-password request-secret")
            api = login.SteamApi()
            with self.assertRaises(login.LoginError) as error:
                api.call("GetPasswordRSAPublicKey", login.proto.GetPasswordRSAPublicKeyRequest(), 1)
            self.assertNotIn("proxy-password", str(error.exception))
            self.assertNotIn("request-secret", str(error.exception))

    def test_explicit_proxy_and_no_proxy_override_environment(self):
        env = {"STEAM_PROXY_URL": "http://env:1", "HTTPS_PROXY": "http://other:2"}
        with patch.dict(os.environ, env), patch.object(login.requests, "Session"):
            api = login.SteamApi(proxy="http://explicit:3")
            self.assertFalse(api.http.trust_env)
            self.assertEqual("http://explicit:3", api.http.proxies["https"])
            api = login.SteamApi(no_proxy=True)
            self.assertFalse(api.http.trust_env)
            self.assertNotEqual({"https": "http://env:1"}, api.http.proxies)
            api = login.SteamApi()
            self.assertFalse(api.http.trust_env)
            self.assertEqual("http://env:1", api.http.proxies["https"])


class StorageAndCliTests(unittest.TestCase):
    def setUp(self):
        temporary_root = login.ROOT / "agent-tmp"
        temporary_root.mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=temporary_root)
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "session.env"
        self.session = login.DesktopSession("test", STEAM_ID, token(), "guard-secret")

    def test_session_format_and_private_permissions(self):
        self.path.write_text("old-session")
        self.path.chmod(0o644)
        login.save_session(self.path, self.session)
        values = dict(line.split("=", 1) for line in self.path.read_text().splitlines() if not line.startswith("#"))
        self.assertEqual(self.session.refresh_token, values["STEAM_REFRESH_TOKEN"])
        self.assertEqual(str(STEAM_ID), values["STEAM_STEAM_ID64"])
        self.assertNotIn("STEAM_PASSWORD", values)
        self.assertNotIn("STEAM_ACCESS_TOKEN", values)
        if os.name == "posix":
            self.assertEqual(0o600, stat.S_IMODE(self.path.stat().st_mode))
        self.assertEqual([self.path], list(self.path.parent.iterdir()))

    def test_temporary_is_private_before_secret_write(self):
        original = os.fdopen

        def checked(descriptor, *args, **kwargs):
            if os.name == "posix":
                self.assertEqual(0o600, stat.S_IMODE(os.fstat(descriptor).st_mode))
            return original(descriptor, *args, **kwargs)

        with patch.object(login.os, "fdopen", side_effect=checked):
            login.save_session(self.path, self.session)

    def test_failed_replace_keeps_old_session_and_cleans_temporary(self):
        self.path.write_text("old-session")
        with patch.object(login.os, "replace", side_effect=OSError("disk error")):
            with self.assertRaises(OSError):
                login.save_session(self.path, self.session)
        self.assertEqual("old-session", self.path.read_text())
        self.assertEqual([self.path], list(self.path.parent.iterdir()))

    def test_env_injection_cannot_touch_existing_session(self):
        self.path.write_text("old-session")
        self.session.account_name = "test\nSTEAM_PASSWORD=bad"
        with self.assertRaises(login.LoginError):
            login.save_session(self.path, self.session)
        self.assertEqual("old-session", self.path.read_text())

    def test_getpass_never_falls_back_to_echo(self):
        def fail(prompt):
            import warnings
            warnings.warn("echo fallback", login.getpass.GetPassWarning)

        with patch.object(login.getpass, "getpass", side_effect=fail), self.assertRaises(login.LoginError):
            login.Terminal().secret("Password: ")

    def test_non_terminal_does_not_authenticate(self):
        with patch.object(login.sys.stdin, "isatty", return_value=False), patch.object(login, "SteamApi") as api:
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, login.main([]))
            api.assert_not_called()

    def test_existing_session_requires_confirmation(self):
        self.path.write_text("old-session")
        with patch.object(login.sys.stdin, "isatty", return_value=True), patch.object(login, "Terminal", return_value=FakeTerminal(lines=["n"])):
            with patch.object(login, "SteamApi") as api:
                self.assertEqual(0, login.main(["--output", str(self.path)]))
                api.assert_not_called()
        self.assertEqual("old-session", self.path.read_text())

    def test_success_saves_only_after_authentication_and_closes(self):
        with patch.object(login.sys.stdin, "isatty", return_value=True), patch.object(login, "Terminal", return_value=FakeTerminal()):
            with patch.object(login, "SteamApi") as api, patch.object(login.InteractiveLogin, "run", return_value=self.session):
                self.assertEqual(0, login.main(["--output", str(self.path), "--username", "test"]))
                api.return_value.close.assert_called_once()
        self.assertIn(self.session.refresh_token, self.path.read_text())

    def test_auth_failure_cancellation_and_unexpected_errors_preserve_old_file(self):
        self.path.write_text("old-session")
        for failure in (login.SteamError(5), KeyboardInterrupt(), EOFError(), ValueError("raw-secret")):
            with self.subTest(failure=type(failure).__name__), patch.object(login.sys.stdin, "isatty", return_value=True):
                with patch.object(login, "SteamApi") as api, patch.object(login.InteractiveLogin, "run", side_effect=failure):
                    stderr = io.StringIO()
                    with contextlib.redirect_stderr(stderr):
                        self.assertIn(login.main(["--output", str(self.path), "--overwrite"]), (1, 130))
                    self.assertNotIn("raw-secret", stderr.getvalue())
                    api.return_value.close.assert_called_once()
            self.assertEqual("old-session", self.path.read_text())

    def test_default_output_is_independent_of_current_directory(self):
        self.assertEqual(login.ROOT / "agent-tmp/steam-desktop-session.env", login.parse_args([]).output)

    def test_unsupported_secret_arguments_are_not_echoed(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            login.parse_args(["--password", "do-not-echo-this"])
        self.assertNotIn("do-not-echo-this", stderr.getvalue())

    def test_invalid_timeout_is_rejected(self):
        for value in ("0", "-1", "nan", "inf"):
            with self.subTest(value=value), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                login.parse_args(["--timeout", value])


if __name__ == "__main__":
    unittest.main()
