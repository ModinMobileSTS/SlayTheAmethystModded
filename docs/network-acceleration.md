# HTTP acceleration transport

GitHub and Steam HTTP requests use `RoutedHttpTransport`, an explicit OkHttp
`Call.Factory`. The transport owns the complete logical request, rather than an
application interceptor issuing an unrelated second call.

## Responsibilities

- `RoutedHttpTransport`: Call lifecycle, sync/async execution, cancellation, total
  deadline (including discovery, retries, redirects and response-body reads),
  request preparation and route-isolated clients.
- `WattRouteExecutor`: logical-origin cookies, redirect credential policy,
  candidate selection, response validation and safe retry decisions.
- `WattToolkitGithubRouteResolver`: existing bundled-hop/best-path selection,
  route discovery, probes, cache and node health.
- `RouteDiscoveryScope`: binds foreground discovery requests and probe tasks to
  the owning Call. Independent background cache refreshes are not owned by an
  individual request.

Business code depends on `Call.Factory`, not `OkHttpClient`. It continues to build
requests using original GitHub/Steam URLs. `withHttpOptions` derives transport
options without dropping routing; `preparingHttpRequests` runs logical request
preparation before either a forwarded or official attempt. Do not install a
business-language/authentication interceptor after a routing interceptor: there
is no routing interceptor anymore.

## Lifecycle and retry rules

- `Call.cancel()` cancels the current official/forward call and foreground
  discovery, without requiring special request tags.
- `Call.timeout()` and configured `callTimeout` cover the entire operation, not
  a fresh timeout for each candidate. A zero call timeout retains OkHttp's
  unlimited-total-duration meaning; connect/read/write timeouts still apply.
- The lifecycle stays active until the response body is exhausted or closed.
  Always close responses, including unused/error responses.
- Derived routed transports share the cancellation group; workshop account
  revocation uses `cancelAllHttpCalls` to stop requests and body reads.
- Existing resource/Steam CDN controls remain supported for caller-owned races
  and shared multi-request budgets, but they are no longer required for normal
  cancellation.
- Only GET/HEAD/OPTIONS are automatically eligible for candidate replay.
  Read-only POST queries explicitly tag `HttpRetrySafety.READ_ONLY`.
  The Steam adapter also tags the exact published-file-details query endpoint
  for requests constructed by the standalone download core; it never promotes
  mutations or overrides an explicit `NEVER` policy.
  Mutations are not replayed on IO failure by default. One-shot and duplex
  request bodies cannot opt into replay. Same-origin 307/308 redirects also
  reject one-shot/duplex bodies instead of attempting a second send.
- A candidate client disables implicit connection retries and automatic
  redirects: the logical executor owns those decisions.

Cookies are loaded/saved against the original domain on both routes. Redirects
use the existing secure-origin policy; crossing an origin removes sensitive
headers and does not reattach session cookies on later redirect hops. HTTPS
remains mandatory except for the existing public SteamPipe `/depot/` exception.

## Route isolation

Each candidate uses a frozen DNS mapping and its own connection pool. Two routes
with equal fake SNI names (for example the Steam community and store Akamai
front) cannot overwrite each other's DNS mapping or reuse the other route's
connection. TLS exceptions are scoped to the selected route, not another cached
route in a shared resolver. The existing upstream-declared certificate bypasses
are retained, not expanded.

## GitHub coverage

`WattToolkitAcceleratedHttp.createClientPair` is the common entry point. The
accelerated member is the routed transport; official/proxy-mirror requests keep
their existing source-selection policy. Coverage includes:

- Launcher and SlingBreak release checks and asset downloads.
- ModTheSpire and Arthas component/resource downloads.
- External resource-pack downloads, Range/chunk downloads and link probes.
- Native-library metadata and library downloads.
- Mod suggestions, AI patch catalogue indexes and patch JARs.
- Cloud-control content when hosted on GitHub.
- Feedback issue/comment reads, GitHub avatars and Markdown images.

GitHub web, API, raw/codeload and `*.githubusercontent.com` redirect targets are
selected per logical hop, so release-asset and avatar hosts keep acceleration.
Unrelated upload relays and non-GitHub URLs are not routed through GitHub nodes.
External browser links and build-time dependency downloads are outside this
application transport.

Steam HTTP callers use the same executor, including workshop details and CDN
downloads. CM WebSockets retain a separate explicit factory; the HTTP transport
does not attempt to implement WebSocket upgrades.

## Regression tests

`RoutedHttpTransportTest` covers ordinary cancellation, deadline/body handling,
derived transport cancellation, timeout inheritance, foreground discovery,
remaining budgets across node fallback, logical cookies/preparation, mutation
and one-shot replay restrictions, async callbacks and cloning, all GitHub host
families, and `{origin}` SNI rules for wildcard user-content hosts.

The existing GitHub route/redirect tests, Steam route/cookie tests, workshop
network matrix, catalogue fixtures and resource-probe tests now exercise the
explicit transport. The `RoutingTestFixture` builder is test-only configuration,
not an interceptor or an alternative implementation.

The resource-store unit fixtures generate their own ZIPs, so when including
`backend.resources.*` in a local test run, pass `-PresourcePack.sha256=` to that
Gradle invocation. Otherwise a configured production archive hash rejects these
synthetic ZIPs before the tested storage operations. This override is only for
unit tests: do not use it to build release APKs or change the repository's hash.

Device/internet acceptance tests remain opt-in; offline regression tests do not
establish the current availability of a third-party acceleration node.
