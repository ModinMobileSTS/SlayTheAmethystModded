# LLM settings HTTP diagnostics

An empty HTTP 400 alone cannot distinguish an application error from a reverse proxy, routing or
device-specific failure. In particular, browser access without the configured API key is not an
equivalent authenticated API request. A storage consistency fix does not prove the cause of 400.

Failed model-list and model-test requests now include `LLM HTTP diagnostics v1` in their existing
error text. The capture observes the real request; it does not send extra probes, change protocol
selection, bypass proxies, alter TLS/hostname verification, or persist network logs.
Both error texts are selectable so users can copy diagnostics instead of transcribing a screenshot.

## Captured fields

- App version, Android release/API, configured URL without user info/query/fragment.
  Paths containing command/JSON syntax or encoded whitespace are omitted completely, including
  unrecognized credential fragments that do not match the currently configured key.
- Key character count, internal whitespace/non-ASCII counts and whether it begins with `Bearer `.
  The key itself, prefixes, suffixes and hashes are not emitted.
- Actual last network request method/URL, timestamp, exchange attempt number, connected IP/port,
  proxy type, negotiated HTTP protocol and TLS version when a network exchange is observed.
- Response status, declared body size, and only these response headers: `Server`, `Via`,
  `Content-Type`, `Content-Length`, `Date`, `X-Request-Id`, `X-Oneapi-Request-Id`,
  `X-New-Api-Version`, `CF-Ray`. Values are bounded after credential redaction.
- Original provider error text/HTTP status and an explicit indication when the HTTP error message
  is empty. Error display remains bounded to 4096 characters.

No authorization value, cookies, arbitrary headers or request body are captured. The original SDK
exception type and cause remain unchanged. Diagnostics are attached only to failed settings probes,
not ordinary agent requests, and the capture is removed on completion/cancellation. URLs and server
headers can still identify a private service, so review diagnostics before posting publicly.

If failure occurs before an HTTP network exchange (for example, connection/TLS/proxy setup), the
diagnostic says `HTTP exchange: not observed`; it does not invent a peer or protocol. Unknown body
length is reported as `unknown`, not zero.

## Interpreting a user report

Match the displayed timestamp/path/request ID to server access/error logs. Compare the actual peer
and HTTP/TLS protocol rather than assuming the browser uses the same network path. Check key/header
length and whitespace without requesting the key. An Android version is context, not evidence that
HTTP/2 or TLS is broken; protocol fallbacks and security bypasses should not be applied speculatively.

Offline MockWebServer tests cover empty 400, redirects/final URLs, allowlist and secret redaction,
unchanged successful requests, capture lifetime/cancellation, and both model protocols. JVM tests
do not validate Android 12's networking implementation or reproduce the user's live failure.

## Invalid Base URL containing a copied command

A user diagnostic identified a URL whose path contained `-H`, encoded spaces/quotes and JSON body
syntax. OkHttp accepted this as a path and appended `/models`; the resulting direct HTTPS request
received an empty 400. HTTP/2 and TLS 1.3 had negotiated successfully. This establishes a malformed
request target for that attempt, not Android 12 incompatibility or a stale-store diagnosis. It does
not establish how the command text entered the field or explain earlier unobserved requests.

Settings validation and the catalog/chat/streaming model request boundaries now reject such Base
URLs, including percent-encoded command text from stored configuration. The original input is not
included in the validation exception. Valid provider prefixes, custom ports, localhost and IPv6
remain supported. No contaminated setting is silently rewritten; the user must replace it with the
provider's plain API Base URL. The screen disables fetch/test while the URL is invalid.

Older diagnostic builds printed URL paths after stripping only user info/query/fragment and known
key patterns. Malformed paths could therefore expose an unrelated credential fragment. Do not
publish those raw diagnostics; revoke/rotate any credentials that may have been exposed. Current
diagnostics omit abnormal paths, including URLs echoed in exception messages. Tests use fabricated
credentials only; actual user-provided secrets must not be copied into tests or documentation.
