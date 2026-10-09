/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObservers;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.TelemetrySettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import ai.skillroy.testjava.telemetry.ExportStatus;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The telemetry options of {@code run}, {@code session}, {@code mcp}, {@code re-resolve}, {@code triage} and
 * {@code author} (phase6-contracts.md 10.3, decisions D140, D141): {@code --telemetry none|otlp|file},
 * {@code --telemetry-endpoint <url>}, {@code --telemetry-attributes standard|minimal}, {@code --telemetry-file
 * <path>}, turned into {@link TelemetrySettings} under the names of the system properties (with the command's
 * {@code <out>/telemetry/} as the file exporter's directory), and the observer they ask for.
 *
 * <p>Off by default: with {@code none} (or nothing named anywhere) no provider is looked for, no OpenTelemetry class
 * loads and no thread starts. A settings error ({@code http://} to another host than a loopback address, a header given
 * as a literal or naming the model key's variable, a variable that is not set) is a usage error, exit code 64, found
 * before the agent is contacted; its message names the option, the file and line or the variable, never a value. A
 * header's value is read from the environment variable {@code telemetry.yaml} names, by the exporter, and is never
 * written, logged or printed. With telemetry on, the command ends with one line {@code telemetry: otlp <host:port>,
 * trace <id>, <n> batches dropped} and {@code --json} carries {@code telemetry: {exporter, traceId}}.
 *
 * <p>Stateless; safe from any thread.
 */
final class TelemetryArgs {

    static final String TELEMETRY = "--telemetry";
    static final String ENDPOINT = "--telemetry-endpoint";
    static final String ATTRIBUTES = "--telemetry-attributes";
    static final String FILE = "--telemetry-file";

    /** The directory under a command's {@code --out} where the file exporter writes {@code spans.jsonl}. */
    static final String DIRECTORY = "telemetry";

    /** The options, in help order. */
    static final List<OptionSpec> OPTIONS = List.of(
            OptionSpec.value(TELEMETRY, "none|otlp|file", "export the run's spans (default none: nothing loads);\n"
                    + "file writes <out>/telemetry/spans.jsonl"),
            OptionSpec.value(ENDPOINT, "url", "the OTLP/HTTP endpoint (default http://127.0.0.1:4318);\n"
                    + "http:// to a loopback address only, else https://"),
            OptionSpec.value(ATTRIBUTES, "standard|minimal", "the span attribute set (default standard)"),
            OptionSpec.value(FILE, "path", "the telemetry settings (default: telemetry.yaml beside\n"
                    + "the credentials file); headers come from environment variables"));

    /** How long reading the target's facts for the root span may take. */
    private static final long INFO_TIMEOUT_MS = 5_000L;

    private TelemetryArgs() {
    }

    /**
     * The options given on the command line, under the names of the system properties they override.
     *
     * @param outDir the command's run output, whose {@code telemetry/} the file exporter writes to; null for none
     */
    static Map<String, String> options(ParsedArgs args, Path outDir) {
        Map<String, String> options = new LinkedHashMap<>();
        text(args, TELEMETRY, TelemetrySettings.PROP_EXPORTER, options);
        text(args, ENDPOINT, TelemetrySettings.PROP_ENDPOINT, options);
        text(args, ATTRIBUTES, TelemetrySettings.PROP_ATTRIBUTES, options);
        text(args, FILE, TelemetrySettings.PROP_FILE, options);
        if (outDir != null) {
            options.put(TelemetrySettings.PROP_OUT, outDir.resolve(DIRECTORY).toString());
        }
        return options;
    }

    /**
     * The settings of the invocation: the options over the system properties over {@code telemetry.yaml} over the
     * defaults; the service version is the CLI's.
     *
     * @param credentialsDir where {@code telemetry.yaml} is looked for unless {@code --telemetry-file} names it; may be
     *     null
     * @param outDir the command's run output ({@code <out>/telemetry/} for the file exporter); may be null
     * @throws UsageException with the settings' message (exit code 64), never a value
     */
    static TelemetrySettings load(ParsedArgs args, Path credentialsDir, Path outDir) throws UsageException {
        try {
            return TelemetrySettings.load(credentialsDir, options(args, outDir), Main.version());
        } catch (SettingsException e) {
            throw new UsageException("the telemetry settings cannot be used: " + e.getMessage());
        }
    }

    /**
     * The observer the settings ask for: {@link RunObserver#NONE} for {@code none}, without looking for a provider;
     * else the one {@code ServiceLoader} finds on the CLI's class loader.
     *
     * @throws UsageException when the exporter cannot be made (exit code 64)
     */
    static RunObserver open(TelemetrySettings settings, Cli cli) throws UsageException {
        if (!settings.enabled()) {
            return RunObserver.NONE;
        }
        try {
            return RunObservers.open(settings, cli.classLoader());
        } catch (SettingsException e) {
            throw new UsageException("the telemetry settings cannot be used: " + e.getMessage());
        }
    }

    /**
     * The root span's facts: the command and its settings, and the target's from the connection and one bounded
     * {@code agent.info} (best effort; nothing of it with telemetry off).
     *
     * @param target the connected target, or null when none was reached
     * @param environment a one-line message about an environment that could not be used, or null
     */
    static RunStart runStart(RunObserver observer, String command, String suite, String bindMode, String envId,
                             Target target, String environment) {
        if (RunObservers.isNone(observer)) {
            return null;
        }
        RunStart.Target facts = null;
        if (target != null) {
            String javaVersion = null;
            String loadMode = target.endpoint().loadMode() == null ? null : target.endpoint().loadMode().name();
            String agentVersion = null;
            String protocol = null;
            try {
                RpcResult info = target.client().call("agent.info", Map.of(),
                        CallOptions.defaults().withTimeoutMs(INFO_TIMEOUT_MS));
                if (info.value() instanceof Map<?, ?> value) {
                    javaVersion = text(value.get("javaVersion"));
                    loadMode = value.get("loadMode") == null ? loadMode : text(value.get("loadMode"));
                    agentVersion = text(value.get("agentVersion"));
                    protocol = text(value.get("protocol"));
                }
            } catch (AgentRpcException | AgentLostException | RuntimeException e) {
                // Best effort: the root span then names what the connection knows.
            }
            facts = new RunStart.Target(target.endpoint().pid(), javaVersion, loadMode, agentVersion, protocol,
                    target.endpoint().sessionId());
        }
        return new RunStart(command, suite, bindMode, envId, Main.version(), facts, environment);
    }

    /** Tells the observer the invocation started (nothing with telemetry off). */
    static void started(RunObserver observer, RunStart start) {
        if (start != null) {
            RunObservers.safe(observer).runStarted(start);
        }
    }

    /**
     * Ends the export (bounded by the settings' timeout) and prints the summary line on {@code out}; nothing with
     * telemetry off.
     */
    static void finish(RunObserver observer, PrintStream out) {
        if (RunObservers.isNone(observer)) {
            return;
        }
        String traceId = RunObservers.safe(observer).traceId();
        try {
            observer.close();
        } catch (RuntimeException | LinkageError e) {
            // Telemetry never fails a command.
        }
        String line = ExportStatus.summaryLine(observer, traceId);
        if (line != null) {
            out.println(line);
            out.flush();
        }
    }

    /** {@code --json}'s {@code telemetry}: {@code {exporter, traceId}}, or null with telemetry off. */
    static Map<String, Object> json(TelemetrySettings settings, RunObserver observer) {
        if (settings == null || !settings.enabled() || RunObservers.isNone(observer)) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("exporter", settings.exporter());
        json.put("traceId", RunObservers.safe(observer).traceId());
        return json;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean declared(ParsedArgs args, String option) {
        return args.spec().option(option) != null;
    }

    private static void text(ParsedArgs args, String option, String property, Map<String, String> options) {
        if (declared(args, option) && args.value(option) != null) {
            options.put(property, args.value(option));
        }
    }
}
