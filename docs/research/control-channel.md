# Control channel, protocol, MCP projection and observability

Research note, 2026-10-01. Topic: how an in-process test agent running inside a Java 8 target JVM talks to an
external Java 25 / JUnit 5 controller driven by Claude, covering transport, serialization and schemas, MCP
layering, observability and security.

## Summary

Recommended: a zero-dependency, language-neutral control channel. The agent (compiled `--release 8`, dependencies
shaded and relocated, loaded in an isolated class loader) embeds JDK 8's built-in `com.sun.net.httpserver`, bound
to 127.0.0.1 with a per-session bearer token. It exposes `POST /rpc` (JSON-RPC 2.0 request/response and batch, for
primitive invocation), `GET /events` (Server-Sent Events with monotonic `id:` sequence numbers and an agent-side
ring buffer so `Last-Event-ID` resume works), `GET /schema` (primitive catalog with JSON Schema 2020-12
input/output schemas and MCP-style annotations) and `POST /shutdown` (kill switch). This POST + SSE shape matches
MCP's Streamable HTTP binding, so the Java 25 controller maps it one-to-one onto an MCP server (MCP Java SDK 2.0.1,
Java 17+, spec 2025-11-25) that Claude Code or the Claude Agent SDK consume over stdio or HTTP as
`mcp__<server>__<tool>`. A second, pluggable transport (newline-delimited JSON-RPC over TCP or junixsocket,
optionally agent-initiated "dial-out") is reserved for firewalled Windows target hosts; the MCP spec itself
recommends stdio framing for custom TCP/Unix-socket transports. JMX/RMI, gRPC-java, Javalin, Jolokia 2, Undertow
2.3+, Jetty 10+, NanoHTTPD and Jetty 9.4 are rejected on Java 8 compatibility, end-of-life, weight or
language-neutrality grounds.

Serialization is JSON everywhere (Gson 2.14 shaded in the agent; Jackson 2.x/3.x in the controller), with the
schema catalog as the single source of truth for MCP tools, an OpenAPI 3.1 document and generated TypeScript/Python
client stubs for the "code execution with MCP" pattern. Events reach the model through correlated tool results, a
`wait_for_event` long-poll tool and an `events_query` tool (optionally Claude Code Channels, a research preview, for
unattended sessions) rather than raw push, because MCP 2026-07-28 removed GET streams and resumability and no Claude
client lists support for the Tasks extension. Observability is a JSONL event log keyed by W3C `traceparent` (which
MCP reserves in `_meta`), with OpenTelemetry SDK 1.66 / javaagent 2.31.1 (both Java 8+) writing OTLP-JSON lines to
files, JFR available on Temurin 8u262+, and the OTel test and MCP semantic conventions for span naming. Security:
loopback-only binding, Origin rejection, token auth, read-only vs mutating primitive flags, confirm-gated
destructive tools, a kill switch that removes transformers, a separate never-shipped artifact gated by an explicit
enable flag, and closing any all-interfaces JDWP listener on the target. JEP 451 means JDK 21+ targets need
`-javaagent` or `-XX:+EnableDynamicAgentLoading`. Open questions when this note was written: cross-session attach
on Windows, a JDK 21 attacher against a JDK 8 target, and which Byteman line serves Java 8 (the last two were later
exercised experimentally; see the cross-references under F29 and F36).

**Reading this note.** Ids are stable and intentionally non-contiguous. Confidence values are those of the original
research (`verified`, `likely`, `uncertain`); `medium (not re-verified on a neutral target)` marks a claim kept
without its original evidence. Spike ids (`BS1`, `BS2`, ...) refer to the base spike list. `ATTACH` and `JAVAAGENT`
are the framework's load modes for dynamic attach and launch-time `-javaagent`.

## Findings

### F1

JDK 8 ships `com.sun.net.httpserver.HttpServer` and `HttpsServer` (create and bind to an `InetSocketAddress`,
`HttpContext`/`HttpHandler`, `setExecutor`). `sendResponseHeaders(code, 0)` selects chunked transfer encoding and
the body stream stays open until closed, which is sufficient for SSE.

Evidence: <https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpServer.html>;
<https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpExchange.html>
(a zero response length selects chunked encoding and allows an arbitrary amount of data).

Confidence: verified.

### F2

If `setExecutor` is not called, the JDK `HttpServer` runs every handler on the single thread created by `start()`.
A long-lived SSE handler would then block all other requests, so a thread-pool executor is mandatory.
`stop(delay)` blocks until exchanges finish or the delay elapses.

Evidence: <https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpServer.html>
(`setExecutor`: without an executor, the default implementation uses the thread created by `start()`).

Confidence: verified.

### F3

JMX remote via `jmxremote.port` exposes an RMI registry, while the ports on which the RMIServer and RMIConnection
objects are exported are chosen by the RMI stack unless the connector is built programmatically with explicit
ports: the classic two-port firewall problem. `authenticate` and `ssl` default to true. The local, attach-based
connector needs no password. JMX is not language-neutral.

Evidence: <https://docs.oracle.com/javase/8/docs/technotes/guides/management/agent.html> (RMIServer and
RMIConnection export ports are chosen by the RMI stack).

Confidence: verified.

### F4

Jolokia 2.x (an HTTP/JSON bridge for JMX) requires JDK 11+ at runtime and listens on port 8778 by default, so it
cannot run inside a Java 8 target JVM; the 1.x line would be needed.

Evidence: <https://jolokia.org/agent/jvm.html> (runtime dependency: JDK 11 or later).

Confidence: verified.

### F5

gRPC-java 1.84.0 still supports Java 8, but its maintainers tie continued Java 8 support to Temurin's November 2026
window and plan to keep only a maintenance branch after dropping Java 8 from master. It also pulls protobuf, Netty
and HTTP/2 into the agent.

Evidence: <https://github.com/grpc/grpc-java> (supports Java 8 and later);
<https://groups.google.com/g/grpc-io/c/-XK6Kd_19YQ> (a branch is kept alive for Java 8 after master drops it).

Confidence: verified.

### F6

Java-WebSocket (`org.java-websocket`) 1.6.0 requires only API level 1.7, so it runs on Java 8 and is a viable small
WebSocket server/client if a true single-socket bidirectional transport is ever needed.

Evidence: <https://github.com/TooTallNate/Java-WebSocket> (release notes: 1.6.0 requires API level 1.7).

Confidence: likely.

### F7

junixsocket 2.10.1 provides AF_UNIX sockets on Java 8+ (it needs Java 17 only to build). Native JDK Unix-domain
sockets exist only from Java 16.

Evidence: <https://github.com/kohlschutter/junixsocket>; <https://kohlschutter.github.io/junixsocket/compatibility.html>.

Confidence: verified.

### F8

Netty 4.2.0.Final (April 2025) raised its minimum to Java 8 (io_uring needs 9+). Undertow 2.3 dropped JDK 8 (2.2.x
is the javax/Java 8 line). Jetty 9.4 is the last Java 8 line and is end-of-life with no public releases since
January 2026. NanoHTTPD's last release is 2.3.1 (2016) and it is reported abandoned with an unpatched
vulnerability. Javalin needs Java 11+.

Evidence: <https://netty.io/news/2025/04/03/4-2-0.html>; <https://groups.google.com/g/undertow-dev/c/Bn3c8G3zqpE>;
<https://www.eclipse.org/lists/jetty-announce/msg00168.html>; <https://libraries.io/maven/org.nanohttpd:nanohttpd>;
<https://github.com/gocd/gocd/issues/10428>.

Confidence: likely.

### F9

Gson 2.14.0 is current and Java 8+ (Java 7 was dropped in 2.12.0). Jackson 2.x stays on a Java 8 baseline (2.22
latest; 2.21 and 2.18 are LTS). Jackson 3.x (3.0 GA on 3 October 2025, 3.2 current) requires Java 17, so Jackson 3
is controller-only.

Evidence: <https://github.com/google/gson/releases>; <https://github.com/FasterXML/jackson/wiki/Jackson-Releases>;
<https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md> (baseline JDK raised to 17
from 8 in Jackson 2.x).

Confidence: verified.

### F10

A target application's class path may already contain common JSON, HTTP or AOP libraries (for example Gson,
org.json, Jackson, Spring, the AspectJ weaver). Any JSON or HTTP library the agent uses must therefore be relocated
(shaded) and loaded in an isolated class loader to avoid version clashes. The OpenTelemetry Java agent documents
this isolation pattern: agent classes are renamed to `.classdata` and bootstrap APIs are relocated under
`io/opentelemetry/javaagent/shaded/`.

Evidence: <https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/contributing/javaagent-structure.md>.

Confidence: verified for the isolation pattern; medium (not re-verified on a neutral target) for the class-path
observation.

### F11

JSON-RPC 2.0 is transport-agnostic. It defines id-less notifications (the server must not reply), batch arrays,
and reserved error codes -32768 to -32000 (-32700 parse error, -32600 invalid request, -32601 method not found,
-32602 invalid params). MCP uses the same envelope, so an agent speaking JSON-RPC maps one-to-one onto MCP
`tools/call`.

Evidence: <https://www.jsonrpc.org/specification>; <https://modelcontextprotocol.io/specification/2026-07-28/basic/index>
(all MCP messages must follow JSON-RPC 2.0).

Confidence: verified.

### F12

SSE (`text/event-stream`, UTF-8) supports `event`, `data`, `id` and `retry` fields. Clients reconnect
automatically and send `Last-Event-ID`, so a server with a buffered event log can resume. This is the agent's event
channel, independent of MCP's own transport rules.

Evidence: <https://html.spec.whatwg.org/multipage/server-sent-events.html>.

Confidence: verified.

### F13

The current MCP specification revision is 2026-07-28. It is stateless (no `initialize` handshake, no
`Mcp-Session-Id`); every request carries `_meta.io.modelcontextprotocol/protocolVersion` and client capabilities;
it adds a `server/discover` RPC; `subscriptions/listen` replaces GET streams and `resources/subscribe`; SSE
resumability (`Last-Event-ID`) is removed; Tasks moved to an extension; Roots, Sampling and Logging are deprecated.

Evidence: <https://modelcontextprotocol.io/specification/2026-07-28/changelog>;
<https://modelcontextprotocol.io/specification/latest> (schema/2026-07-28).

Confidence: verified.

### F14

MCP Streamable HTTP uses a single POST endpoint; each request is answered by JSON or by a request-scoped SSE
stream. Servers must validate `Origin` (403 on invalid), should bind only to 127.0.0.1 when local, and should
authenticate. Keep-alive SSE comment lines are recommended; closing the stream means cancellation. The spec also
says custom transports over TCP or Unix sockets should reuse stdio's newline-delimited JSON-RPC framing.

Evidence: <https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http>;
<https://modelcontextprotocol.io/specification/2026-07-28/basic/transports>;
<https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio>.

Confidence: verified.

### F15

MCP tool definitions have `name`, `title`, `description`, `inputSchema` (JSON Schema 2020-12 by default; must be an
object), optional `outputSchema` and `structuredContent`, `annotations` (`readOnlyHint`, `destructiveHint`,
`idempotentHint`, `openWorldHint`; these are untrusted hints) and `icons`. Tool names should be 1-128 characters
of `[A-Za-z0-9_.-]`. Execution errors go in `isError: true` results (so models can self-correct); protocol errors
are JSON-RPC errors. `tools/list` should be deterministic for prompt-cache hits. Cross-call state must be an
explicit handle argument.

Evidence: <https://modelcontextprotocol.io/specification/2026-07-28/server/tools>;
<https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/>.

Confidence: verified.

### F16

MCP reserves the `traceparent`, `tracestate` and `baggage` keys in `_meta` for W3C trace-context propagation. The
OpenTelemetry GenAI MCP conventions name spans `{mcp.method.name} {target}` (for example `tools/call get-weather`)
with attributes `mcp.method.name`, `gen_ai.tool.name` and `mcp.session.id`. Together these give a standard way to
correlate controller tool calls with agent spans.

Evidence: <https://modelcontextprotocol.io/specification/2026-07-28/basic/index#_meta>;
<https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/mcp.md>.

Confidence: verified.

### F17

MCP Tasks is now an extension (`io.modelcontextprotocol/tasks`): the server returns `resultType: "task"` with
`taskId`, `ttlMs` and `pollIntervalMs`; the client polls `tasks/get`; statuses are working, input_required,
completed, failed and cancelled. The official client matrix lists no Claude client (or any client) for Tasks, so
long-running scenario steps should not depend on it yet.

Evidence: <https://modelcontextprotocol.io/extensions/tasks/overview>;
<https://modelcontextprotocol.io/extensions/client-matrix>.

Confidence: verified.

### F18

The MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`) v2.0.1 was released on 2026-08-19 (2.0.0 GA on 2026-06-11
tracks the 2025-11-25 MCP specification; 1.1.4 and 0.18.4 maintenance backports shipped the same day). It requires
Java 17+. Transports: stdio and Streamable HTTP (JDK `HttpClient` / Spring WebClient clients; Jakarta Servlet,
Spring WebFlux/WebMVC servers); HTTP+SSE is deprecated. It uses Jackson for JSON and Reactor for async. Spring AI
MCP starters build on it.

Evidence: <https://api.github.com/repos/modelcontextprotocol/java-sdk/releases>;
<https://github.com/modelcontextprotocol/java-sdk> (Java 17+ badge);
<https://github.com/modelcontextprotocol/java-sdk/blob/main/MIGRATION-2.0.md> (default protocol versions advertised:
2024-11-05 through 2025-11-25).

Confidence: verified.

### F19

Claude Code consumes MCP servers via stdio (`claude mcp add <name> -- cmd`), HTTP (recommended for remote), SSE
(deprecated; automatic fallback from HTTP since v2.1.265) and WebSocket (`type: "ws"`, JSON config only, header
auth only). A project `.mcp.json` can be version-controlled. Tool names are `mcp__<server>__<tool>`. It supports
`list_changed` and `@` resource mentions. MCP output over 25,000 tokens (`MAX_MCP_OUTPUT_TOKENS`) is spilled to a
file, with a per-tool override via `_meta.anthropic/maxResultSizeChars`. A per-server `timeout` in `.mcp.json`
overrides `MCP_TOOL_TIMEOUT`. The idle timeout is 5 minutes for HTTP/SSE/WebSocket and 30 minutes for stdio unless
progress notifications arrive.

Evidence: <https://code.claude.com/docs/en/mcp>.

Confidence: verified.

### F20

The Claude Agent SDK (TypeScript and Python) takes the same `mcpServers` configuration (stdio command/args/env, or
`type: "http"` / `"sse"` with headers), or `.mcp.json` via `settingSources`, plus in-process SDK MCP servers. MCP
tools must be allowed explicitly, for example `allowedTools: ["mcp__<server>__*"]` (`acceptEdits` does not
auto-approve MCP tools). Tool search is on by default to defer tool definitions. The same 25k-token output cap
applies.

Evidence: <https://code.claude.com/docs/en/agent-sdk/mcp>.

Confidence: verified.

### F21

Claude Code Channels (research preview) let an MCP server push events into a running session: the server declares
`capabilities.experimental['claude/channel']` and emits `notifications/claude/channel` with `{content, meta}`.
Channels are stdio only. Events arrive as `<channel source="...">` tags, are queued and batched per turn, and are
not acknowledged. Custom channels need `--dangerously-load-development-channels server:<name>` and organisation
enablement. A server negotiating protocol 2026-07-28 on the v2 runtime cannot deliver channel messages, so
`MCP_PROTOCOL_NEGOTIATION` must stay unset/legacy.

Evidence: <https://code.claude.com/docs/en/channels-reference>; <https://code.claude.com/docs/en/mcp> (channels /
2026-07-28 limitation).

Confidence: verified.

### F22

Anthropic's tool-design guidance (11 September 2025): consolidate workflows into fewer, higher-level tools;
namespace with service prefixes (prefix vs suffix effects vary by model, so evaluate); return semantic identifiers,
not UUIDs; add a concise/detailed `response_format` and pagination/truncation defaults; write descriptions like
docstrings for a junior developer (at least 3-4 sentences); use `input_examples` for complex schemas; evaluate with
realistic tasks. Anthropic API tool names must match `^[a-zA-Z0-9_-]{1,128}$`: dots are not allowed there even
though MCP permits them.

Evidence: <https://www.anthropic.com/engineering/writing-tools-for-agents>;
<https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools>;
<https://www.anthropic.com/engineering/building-effective-agents> (agent-computer interface, poka-yoke; 19 December
2024).

Confidence: verified.

### F23

Anthropic's "Code execution with MCP" (4 November 2025) and "Advanced tool use" (24 November 2025) report that
presenting tools as a code API with progressive disclosure cut a 150k-token workflow to 2k (98.7%), that the Tool
Search Tool cuts tool-definition tokens by about 85% (worth it above roughly 10k tokens of definitions or with many
MCP servers), and that programmatic tool calling cut tokens by 37%. This supports a two-layer design: MCP tools for
interactive use plus generated TypeScript/Python client stubs for scripted scenarios. "Effective context
engineering" (29 September 2025) recommends minimal, non-overlapping tool sets and just-in-time retrieval via
lightweight identifiers.

Evidence: <https://www.anthropic.com/engineering/code-execution-with-mcp>;
<https://www.anthropic.com/engineering/advanced-tool-use>;
<https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents>.

Confidence: verified.

### F24

OpenTelemetry Java API/SDK 1.66.0 supports Java 8+ with zero transitive dependencies; the javaagent 2.31.1 can be
attached to any Java 8+ application; no announced plan to drop Java 8 was found. The `logging-otlp` exporters
(`OtlpJsonLogging{Signal}Exporter`) and the experimental `experimental-otlp/stdout` exporter emit OTLP JSON
suitable for JSONL files. The OTLP File Exporter spec (JSON Lines, `.jsonl`, one TracesData/MetricsData/LogsData
object per line) is still in "Development" status.

Evidence: <https://opentelemetry.io/docs/languages/java/intro/>;
<https://github.com/open-telemetry/opentelemetry-java-instrumentation>;
<https://opentelemetry.io/docs/languages/java/configuration/>;
<https://opentelemetry.io/docs/specs/otel/protocol/file-exporter/>.

Confidence: verified.

### F25

The OpenTelemetry semantic conventions define test attributes `test.case.name`, `test.case.result.status`
(pass/fail) and `test.suite.run.status` (success/failure/skipped/aborted/timed_out/in_progress) under the CI/CD
conventions, usable for JUnit-level spans in the controller.

Evidence: <https://opentelemetry.io/docs/specs/semconv/registry/attributes/test/>.

Confidence: likely.

### F26

JDK Flight Recorder was backported to OpenJDK 8u262, including the `jdk.jfr` event API, and is included in
Temurin 8. `jcmd` offers `JFR.start` / `JFR.dump` but must run on the same machine with the same effective user and
group as the target JVM.

Evidence: <https://developers.redhat.com/blog/2020/08/25/get-started-with-jdk-flight-recorder-in-openjdk-8u>;
<https://adoptium.net/temurin/migration>; <https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html>
(same effective user and group identifiers as the JVM).

Confidence: verified.

### F27

`VirtualMachine.loadAgent` passes a JAR path that is interpreted on the target VM's filesystem, adds the JAR to the
system class path and invokes `agentmain` of its `Agent-Class` manifest entry. The agent JAR must therefore be
present on the target host and compiled for the target's bytecode level (Java 8 bytecode for a Java 8 target).
After loading, the attacher can read discovery data via `vm.getSystemProperties()` / `getAgentProperties()`; the
JDK's own local JMX connector publishes its address this way.

Evidence: <https://docs.oracle.com/javase/8/docs/jdk/api/attach/spec/com/sun/tools/attach/VirtualMachine.html>;
<https://docs.oracle.com/javase/8/docs/technotes/guides/management/agent.html> (Example 2-4, `CONNECTOR_ADDRESS`
via `getAgentProperties`).

Confidence: verified.

### F28

On Windows, HotSpot dynamic attach injects a thread into the target (`CreateRemoteThread`). Microsoft documents
that before Windows 8 `CreateRemoteThread` fails across Terminal Services sessions, and cross-logon-session attach
(for example from an SSH or service session into the interactive desktop session running the target) is reported
as problematic. Launch-time `-javaagent` (`JAVAAGENT` mode) is the safe path on Windows target hosts.

Evidence: <https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createremotethread>
(fails if the target process is in a different session from the caller);
<https://learn.microsoft.com/fr-fr/archive/blogs/oanapl/attach-debugger-across-different-logon-sessions>.

Confidence: uncertain.

### F29

A JDK 21 `jcmd` or attacher can generally see and attach to older-JDK targets (vendor documentation says `jcmd`
still works when the container runs an older JDK), but JDK 21 to JDK 8 `loadAgent` had not been verified when this
note was written. Needs spike BS1.

Evidence: <https://docs.bell-sw.com/liberica-jdk/latest/debugging/use-jcmd/>.

Confidence: uncertain.

Cross-reference: `attach-instrumentation.md` F1 and F3 later report, from experiments on a toy target, that JDK 10+
attach clients do load the agent into a JDK 8 target but then throw `AgentLoadException`, because JDK 8 replies
with a bare `0`. BS1 covers the full matrix.

### F30

JEP 451 (JDK 21) prints `WARNING: A Java agent has been loaded dynamically` on attach-time loading unless
`-XX:+EnableDynamicAgentLoading` is set, exempts `-javaagent`, `-agentlib` and `Launcher-Agent-Class`, and states
that dynamic loading will be disallowed by default in a future release. This matters for any target that runs on,
or is moving to, JDK 21 or later.

Evidence: <https://openjdk.org/jeps/451>.

Confidence: verified.

### F31

The Attach API can be disabled with `-XX:+DisableAttachMechanism` (which also disables `jcmd`, `jstack` and
`jmap`), and JDK 9+ disallows self-attach unless `-Djdk.attach.allowAttachSelf` is set. These are the production
hardening knobs.

Evidence: <https://bugs.openjdk.org/browse/JDK-8180425>; <https://access.redhat.com/solutions/1607883>.

Confidence: likely.

### F32

Exposed JDWP ports are exploited in the wild within hours: JDWP has no authentication and allows arbitrary code
execution, and Wiz counted about 6,000 scanning IPs in 90 days. A JDWP listener configured as
`server=y,address=<port>` with no host part binds all interfaces on JDK 8, which is a larger exposure than a
loopback-only control agent; it should be bound to 127.0.0.1 or removed outside development.

Evidence: <https://www.wiz.io/blog/exposed-jdwp-exploited-in-the-wild>.

Confidence: verified for in-the-wild exploitation; medium (not re-verified on a neutral target) for the JDK 8
all-interfaces default.

### F33

SSH local port forwarding (`ssh -N -L 127.0.0.1:port:127.0.0.1:port host`) binds the local end to localhost unless
`GatewayPorts` or a bind address says otherwise. Windows 10 1809+ and Server 2019+ offer OpenSSH Server as an
optional feature, and Server 2025 installs it by default. Loopback-only agents therefore remain reachable from a
remote controller on both Linux/Xvfb hosts and Windows target hosts.

Evidence: <https://man.openbsd.org/ssh#L>;
<https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse>.

Confidence: verified.

### F34

Java 21's `java.net.http.HttpClient` delivers response bodies incrementally via `BodyHandlers.ofLines()` /
`ofInputStream()`, which is enough to consume the agent's SSE stream without extra libraries. It also has a
built-in WebSocket client.

Evidence: <https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html>.

Confidence: verified.

### F35

victools `jsonschema-generator` 5.x generates JSON Schema draft 6, 7, 2019-09 and 2020-12 from Java classes
(optionally honouring Jackson annotations), so the controller can derive MCP `inputSchema`/`outputSchema` from
primitive request/response types instead of hand-writing them.

Evidence: <https://github.com/victools/jsonschema-generator>.

Confidence: likely.

### F36

Byteman's agent listener defaults to `localhost:9091` (javaagent options `listener:true,port:,address:`;
`bminstall -h/-p`). The downloads page states that 4.x releases work on all JDK 9+ runtimes while 3.x (3.0.22) is
maintained for JDK 8 and earlier runtimes, so Byteman on a Java 8 target means the 3.x line, and its listener
should be disabled or pinned to 127.0.0.1 so there is one control port.

Evidence: <https://github.com/bytemanproject/byteman/blob/main/docs/asciidoc/src/main/asciidoc/chapters/Using-Byteman.adoc>;
<https://byteman.jboss.org/downloads.html>.

Confidence: verified.

Cross-reference: `attach-instrumentation.md` F18 and F19 report Byteman 4.0.27 (Java 6 class files) installed and
firing rules on a Temurin 8u492 target, so the "3.x for Java 8" reading of the downloads page is superseded and the
design pins 4.0.27. The listener advice in this item stands.

### F37

Playwright's `connectOverCDP` takes a CDP URL such as `http://localhost:9222` (Chromium only, and lower fidelity
than the Playwright protocol). JxBrowser can expose Chromium's remote-debugging port, so the controller holds two
independent channels to the target host (agent RPC/SSE and CDP), and both must be tunnelled and loopback-protected
the same way.

Evidence: <https://playwright.dev/java/docs/api/class-browsertype>.

Confidence: verified for the Playwright behaviour; medium (not re-verified on a neutral target) for the JxBrowser
remote-debugging option.

### F38

When the target application's engine thread blocks while an application-level modal prompt is open, or pulls
scripted input from a queue, control-channel invocations must be asynchronous with timeouts and must never run
application work on the HTTP handler thread or the AWT event dispatch thread; otherwise the channel hangs together
with the application.

Evidence: the original observation is not reproducible from public sources. Generic background: a modal
`java.awt.Dialog` does not return from `setVisible(true)` until it is hidden
(<https://docs.oracle.com/javase/8/docs/api/java/awt/Dialog.html>). The sample lending desk's engine thread, which
blocks on an input queue, exists to exercise this case.

Confidence: medium (not re-verified on a neutral target).

## Recommendations

### R0

Transport, agent side (Java 8). Embed `com.sun.net.httpserver.HttpServer` (no third-party server), bind to
`InetAddress.getLoopbackAddress()` on an ephemeral port by default, call `setExecutor` with a fixed or cached
daemon pool, and use HTTP/1.1 keep-alive.

| Endpoint | Purpose |
|---|---|
| `POST /rpc` | JSON-RPC 2.0, single or batch |
| `GET /events?since=<seq>&topics=...` | SSE; `id:` = monotonic sequence, `event:` = topic, heartbeat comment every 15 s, honours `Last-Event-ID` from an in-memory ring buffer of the last N events |
| `GET /schema` | primitive catalog |
| `GET /health` | liveness |
| `POST /shutdown` | kill switch |

Publish the bound address and the token-file path as system properties (for example `skillroy.agent.address`) so
the attacher reads them via `VirtualMachine.getSystemProperties()` instead of guessing ports. This also handles a
target that is restarted by a supervisor and re-grabs ports.

### R1

Make the transport a small SPI (`Transport { start(dispatcher); stop() }`). HTTP+SSE is the first implementation.
A second "stream" implementation is newline-delimited JSON-RPC over TCP or junixsocket AF_UNIX (exactly the framing
MCP recommends for custom transports), supporting both listen and agent-initiated dial-out (`connect=host:port`
agent argument) for firewalled Windows target hosts. Do not implement WebSocket or gRPC unless a concrete need
appears; if WebSocket is ever needed, Java-WebSocket 1.6.0 is the Java 8-safe choice.

### R2

Remote reach: default to SSH local port forwarding (`ssh -N -L 127.0.0.1:P:127.0.0.1:P <target-host>`) for
Linux/Xvfb hosts and for Windows target hosts with OpenSSH Server. Only as a documented exception, allow
`--bind <lan-ip>` with `HttpsServer` (JDK 8 built-in TLS) plus token. Never bind 0.0.0.0 by default.

### R3

Agent loading policy: dynamic attach (`ATTACH` mode, `VirtualMachine.loadAgent`) for Linux and development;
`-javaagent` (`JAVAAGENT` mode) via the target's launch configuration or `JAVA_TOOL_OPTIONS` for Windows target
hosts and for JDK 21+ targets (JEP 451 warnings and future default-deny). Ship a tiny Java 8 attacher CLI that can
run on the target host's own JDK 8 (`tools.jar`) over SSH, so cross-version attach from the controller's JDK 25 to
JDK 8 is not a hard dependency. Verify cross-version attach anyway in spike BS1.

### R4

Serialization: JSON only. Agent: Gson 2.14.0 shaded and relocated (or a hand-rolled minimal JSON writer/reader)
inside an isolated agent class loader (OpenTelemetry-style `.classdata` renaming, or a child-first loader whose
parent is the application loader, used for application types only). Controller (Java 25): Jackson 2.22 or 3.x. Wire
types: strings, numbers, booleans, objects and arrays only. Binary data (screenshots, printed documents) travels as
base64 or as `resource_link` file paths written to the shared run directory.

### R5

The schema catalog is the single source of truth. Each primitive declares:

- `name` and `description` (3-4+ sentences, including when and when not to use it);
- `inputSchema` / `outputSchema` (JSON Schema 2020-12, generated with victools from the Java request/response
  records on the controller side, or hand-written YAML checked into the repo);
- `annotations` `{readOnly, destructive, idempotent, openWorld: false}`;
- `timeoutMs` and a `mutating` flag.

Derive from it: the MCP tool list, an OpenAPI 3.1 document, Java/TypeScript/Python client stubs (for the "code
execution with MCP" scripted-scenario path), and the human documentation.

### R6

MCP layering: build the MCP server in the Java 25 controller (`skillroy-mcp`) with MCP Java SDK 2.0.1, using stdio
for Claude Code and the Agent SDK by default and Streamable HTTP when the controller runs on another host. Claude
Code configuration: a project `.mcp.json` such as
`{"skillroy": {"command": "java", "args": ["-jar", "skillroy-mcp.jar", "--target", "..."], "timeout": 600000}}`.
Agent SDK: `allowedTools: ["mcp__skillroy__*"]`. Keep the MCP server on the 2025-11-25 line (what the Java SDK
implements) and leave `MCP_PROTOCOL_NEGOTIATION` at legacy if Channels are used.

### R7

Tool surface for the model: two layers.

| Layer | Content | Exposure |
|---|---|---|
| Domain workflow tools, `<domain>_*` (contributed by the domain pack) | consolidated workflows such as sign-on, starting a transaction, waiting for an application state, asserting a prompt, finding test data that satisfies a condition, environment profile | default |
| Low-level primitives, `jvm_*` / `swing_*` / `js_*` | one tool per primitive | `defer_loading` / tool-search only |

Names use only `[a-z0-9_]` (no dots) to satisfy both MCP and the Anthropic API regex. Every tool returns semantic
identifiers (user code plus role name, item code plus description, state name) and supports
`response_format: concise|detailed`, `limit`, and truncation with explicit "truncated" markers. Set
`_meta.anthropic/maxResultSizeChars` only on tools that legitimately return large payloads.

### R8

Expose read-only context as MCP resources, for example `skillroy://state/current`, `skillroy://events/recent`,
`skillroy://schema/primitives`, `skillroy://env/profile`, `skillroy://run/<id>/events.jsonl`, plus domain resources
such as `<domain>://txn/current`, with `listChanged` and resource subscriptions where the client supports them. Keep
tools for actions and resources for context, following MCP's split between application-driven and model-controlled
primitives.

### R9

Event model. Every agent event is `{seq, ts, type, source, correlationId, traceparent, payload}`. The controller
mints a `correlationId` and a W3C `traceparent` per tool call and passes them in the JSON-RPC params `_meta`; the
agent stamps them on every event emitted while that primitive is active (plus "async" events referencing the last
active `correlationId`). Deliver events to the model via:

1. the tool result's `events` array (events captured within the call window);
2. a `wait_for_event(filter, timeoutMs)` long-poll tool;
3. an `events_query(since_seq, filter, limit)` tool.

Optionally add a Claude Code Channel (`notifications/claude/channel`, research preview, stdio) for unattended
"watch the target and react" sessions, but do not make the design depend on it. Do not rely on MCP Tasks yet. Bound
every tool at or below the per-server timeout and emit `notifications/progress` so Claude Code's 5-minute idle
timer resets.

### R10

Observability. The controller appends `run.jsonl` (one JSON object per controller action, agent event, database
check and assertion; field names aligned with the OTel `test.*` and MCP semantic-convention attribute names). The
agent appends `agent-events.jsonl` locally, which survives channel drops and is shipped back over SSH/scp at the end
of the run.

- Phase 1 OTel: shade the OTel API and SDK 1.66.0 into the agent, propagate `traceparent` from the MCP server's
  spans (`tools/call <tool>`) into agent spans, and export via `experimental-otlp/stdout` / `logging-otlp` to
  OTLP-JSONL files alongside `run.jsonl`.
- Phase 2 (optional, after measuring startup cost): run the official OTel javaagent 2.31.1 in the target JVM with
  only the instrumentations for the target's side effects enabled (for example JDBC, messaging and HTTP clients,
  via `otel.instrumentation.*.enabled`) so database effects join the same trace.

Use `jcmd <pid> JFR.start` (Temurin 8u262+) or `jdk.jfr.Recording` from the agent for crash and hang post-mortems.

### R11

Security and safety.

- Token: 32+ random bytes generated by the attacher, written to a 0600 file and passed as an agent argument.
  Require `Authorization: Bearer` on every request, including `/events`.
- Reject any request carrying a browser `Origin` header (DNS-rebinding defence, mirroring MCP). Rate-limit `/rpc`.
- Mark primitives mutating or destructive and support `--mode read-only`. Destructive primitives require
  `confirm: true` plus the `destructiveHint: true` annotation so Claude Code prompts.
- Kill switch: `POST /shutdown` plus a `skillroy.agent.disable` flag-file watcher, which runs `removeTransformer` /
  `retransformClasses` back to the original bytes, deregisters every listener the agent added to the application,
  calls `stop(1)` and interrupts daemon threads.
- Never in production: a separate artifact that is never on the target application's class path; `agentmain`
  refuses to start unless `-Dskillroy.agent.enabled=true` (or an environment variable) is present, logs a loud
  banner, and refuses when a production marker is detected (`ProductionMarker` SPI; the predicate is
  target-specific). Recommend that production JVMs run with `-XX:+DisableAttachMechanism` and without `-Xrunjdwp`
  (or with `address=127.0.0.1:<port>` in development only).

### R12

Byteman: if used on a Java 8 target, pin to the 3.0.x line (3.0.22) and start it with `listener:false` (drive rule
submission through the skillroy agent's single port) or at least `address:127.0.0.1`; on Java 9+ targets switch to
4.0.27. See the cross-reference under F36: later experiments ran 4.0.27 on a Java 8 target and the design pins
4.0.27; the listener advice stands.

## Constraints

### C0

The agent artifact must be compiled with `--release 8` (class file 52) and have zero unshaded third-party classes
visible to the target application's class loader. Everything it needs must either be in JDK 8
(`com.sun.net.httpserver`, `java.util.concurrent`) or be relocated.

### C1

The agent must never block an application thread (for example an engine or state thread), the AWT EDT, or an HTTP
handler thread on application work. All primitives run asynchronously with explicit timeouts, and modal dialogs
must be detectable and dismissable via primitives.

### C2

The HTTP server must call `setExecutor` with a pool; the default JDK `HttpServer` executor is single-threaded and
SSE would starve `/rpc`.

### C3

The control port binds to 127.0.0.1 only by default; remote access goes through SSH port forwarding; a token is
required on every request; browser `Origin` headers are rejected.

### C4

Attach requires the same host and the same effective uid/gid as the target JVM (Linux). On Windows, cross-session
attach is unreliable, so plan launch-time `-javaagent` loading for Windows target hosts.

### C5

The MCP server requires Java 17+ (MCP Java SDK), which is fine for the Java 25 controller and impossible inside the
Java 8 agent. The agent therefore speaks plain JSON-RPC/SSE, not MCP.

### C6

MCP Java SDK 2.0.1 implements protocol revisions up to 2025-11-25. The newest spec (2026-07-28) is stateless with
different transport rules: pin the SDK and avoid depending on 2026-07-28-only features (and on Tasks, whose client
support is unlisted).

### C7

Tool names: `[a-z0-9_]` only, 64 characters or fewer preferred (MCP allows 128 and dots; the Anthropic API regex
forbids dots). Claude Code prefixes them as `mcp__<server>__<tool>`.

### C8

Tool results should stay well under Claude Code's 25,000-token MCP output cap (and the 10k warning threshold); large
payloads go to files or resources and are referenced by path or URI.

### C9

The Java 8 library ecosystem is contracting (Jetty 9.4 end-of-life; Undertow 2.3+, Jolokia 2 and Javalin need Java
11+; gRPC Java 8 support branch-only after about November 2026). Prefer JDK built-ins in the agent to avoid forced
rewrites.

### C10

Byteman on a Java 8 target means the 3.0.x line; Byteman 4.x targets JDK 9+. (Superseded for the version line; see
the cross-reference under F36.)

### C11

Forward compatibility with JDK 21+ targets: JEP 451 dynamic-agent warnings and the future default-deny mean
`-javaagent` or `-XX:+EnableDynamicAgentLoading` must be part of the target's launch contract.

### C12

The OTLP file exporter format is still in "Development" status; treat the JSONL layout as internal and version it.

## Risks

### Risk0

MCP protocol churn: 2026-07-28 removed sessions, GET streams and SSE resumability and deprecated Logging and
Sampling; Claude Code runs dual runtimes selected by `MCP_PROTOCOL_NEGOTIATION`; the Java SDK lags at 2025-11-25.
Version drift between the framework's MCP server, Claude Code and the SDK could silently break tool discovery or
channels. Mitigation: pin versions and run the MCP conformance suite in CI.

### Risk1

Attach on a Windows target host may fail across logon sessions (`CreateRemoteThread` session isolation), and JDK 21
to JDK 8 attach was unverified (needs spike BS1; see the cross-reference under F29). If both fail, the only path is
launch-time `-javaagent`, which requires changing the target's launch configuration.

### Risk2

The JDK `HttpServer` under long-lived SSE connections, keep-alive and idle timeouts (`sun.net.httpserver.*`
properties) is lightly documented and needs a soak test: hours of events, controller reconnects, `Last-Event-ID`
resume. Needs spike BS2.

### Risk3

Class-loader and shading mistakes (JSON, AOP or framework libraries already on the target's class path) can crash
the target application or the agent. Isolation must be tested against a real target class path that carries such
libraries. Needs spike BS2.

### Risk4

Event volume (every JavaScript evaluation, peripheral call, printer line) can overwhelm the SSE channel and the
model's context. This needs topic filters, ring-buffer backpressure with explicit gap events, and concise default
response formats.

### Risk5

Claude Code Channels are a research preview requiring `--dangerously-load-development-channels` and organisation
enablement; building core flows on them risks breakage. Keep polling tools as the baseline.

### Risk6

Security regression: on a target that already runs an all-interfaces JDWP listener (`server=y,address=<port>` on
JDK 8), adding a loopback agent gives that host two code-execution ports. If the agent is ever bound to a LAN
address without TLS and token, it becomes a remote-code-execution vector equivalent to JDWP.

### Risk7

Adding the OTel javaagent to a Java 8 Swing JVM doubles the number of Byte Buddy instances and adds startup latency;
measure before adopting Phase 2 instrumentation (R10).

### Risk8

The Java 8 library end-of-life timeline (Temurin 8 support ends November 2026; gRPC and Jetty already retreating)
may force the agent to stay on JDK built-ins only, limiting future transport options for as long as Java 8 targets
must be supported.

### Risk9

Tool-surface bloat: if all low-level primitives are exposed as MCP tools, definitions can exceed 10k tokens and
reduce tool-selection accuracy. Tool search / `defer_loading` and consolidated domain workflow tools are needed from
day one.
