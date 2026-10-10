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

package ai.skillroy.testjava.scenario.observe;

import ai.skillroy.testjava.data.SettingsFiles;
import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.data.yaml.YamlException;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The telemetry settings of a CLI invocation or a JUnit stream (phase6-contracts.md 10.3, decisions D140, D141).
 * Strings, paths, a map and a long only, so that {@code skillroy-scenario} names no OpenTelemetry type.
 *
 * <p>Built by {@link #load}, in order of precedence, from the options a caller passes (the CLI's {@code --telemetry},
 * {@code --telemetry-endpoint}, {@code --telemetry-attributes}, {@code --telemetry-file} and its run output's
 * {@code telemetry/}, under the names of the system properties), the system properties ({@value #PROP_EXPORTER},
 * {@value #PROP_ENDPOINT}, {@value #PROP_ATTRIBUTES}, {@value #PROP_OUT}, {@value #PROP_SERVICE_NAME},
 * {@value #PROP_TIMEOUT_MS}, {@value #PROP_FILE}), the file {@code telemetry.yaml} ({@value #PROP_FILE}, else beside
 * the credentials file, else none; strict, holding no secret) and the defaults:
 *
 * <pre>
 * version: 1
 * exporter: none                  # none | otlp | file
 * endpoint: http://127.0.0.1:4318 # http:// to a literal loopback address or localhost only; else https://
 * headers: {Authorization: env:OTLP_TOKEN}   # each value read from the environment variable it names
 * out: null                       # the file exporter's directory (the CLI: &lt;out&gt;/telemetry/)
 * serviceName: skillroy
 * attributes: standard            # standard | minimal
 * timeoutMs: 10000
 * </pre>
 *
 * <p>Nothing is ever read from an {@code OTEL_*} variable (D140). A header's value is read from the environment
 * variable it names when the observer is created, never here: here only whether the variable is set is asked. The
 * rules (each a {@link SettingsException} whose message names the setting, the file and the line, or the variable,
 * never a value): the exporter is one of the three; {@code http://} is accepted only when the endpoint's host is a
 * literal IP address for which {@link InetAddress#isLoopbackAddress()} holds or the literal {@code localhost}, anything
 * else must be {@code https://} (verified against the JDK's default trust store; there is no insecure setting); an
 * endpoint carries no user information; a header is given as {@code env:<VARIABLE>}, never as a literal, the variable
 * must be set, and may not be one the inference settings read ({@code ANTHROPIC_*}: "a telemetry header may not carry
 * the model key"; as built, Phase 6, TL: the whole prefix, the four the contract names and the gateway's base URL
 * among them); {@code file} needs {@code out}; the attribute set is {@code standard} or {@code minimal}; the timeout is
 * 1 to 120000 ms.
 *
 * <p>Phase 6 security fix, pass 2: the file, named or found, is read only when nobody but the current user could have
 * written it ({@code SettingsFiles}, P2-L8-F1), and any entry of its name beside the credentials is judged (a planted
 * file or link is a settings error, never skipped); a header needs an {@code https://} endpoint, the default
 * {@code http://} one included ({@link #checkHeaders}, P2-L8-F2).
 *
 * <p>As built (Phase 6, TL): the reader lives here rather than in {@code skillroy-telemetry}, so that the JUnit glue,
 * which does not depend on that module, reads the file and the properties alike, and so that the CLI decides "none"
 * without loading anything (decision D160 of the build).
 *
 * <p>Immutable (the map is an unmodifiable copy); safe to share between threads.
 *
 * @param exporter {@value #EXPORTER_NONE}, {@value #EXPORTER_OTLP} or {@value #EXPORTER_FILE}
 * @param endpoint the OTLP/HTTP base URL ({@code <endpoint>/v1/traces} is posted to)
 * @param headerVariables by header name, the environment variable whose value the header carries
 * @param serviceName the {@code service.name} resource attribute
 * @param serviceVersion the {@code service.version} resource attribute: the CLI's or the JUnit glue's version
 * @param attributes {@value #ATTRIBUTES_STANDARD} or {@value #ATTRIBUTES_MINIMAL}
 * @param out the file exporter's directory ({@code spans.jsonl} goes there), or null
 * @param timeoutMs the exporter's per-batch timeout and the bound of {@code flush} and {@code close}
 * @param file the settings file that was read, or null
 */
public record TelemetrySettings(String exporter, String endpoint, Map<String, String> headerVariables,
                                String serviceName, String serviceVersion, String attributes, Path out,
                                long timeoutMs, Path file) {

    /** The settings file's name, looked for beside the credentials file. */
    public static final String FILE_NAME = "telemetry.yaml";
    /** The settings file format version this code reads. */
    public static final int VERSION = 1;

    /** No exporter: nothing is created, no exporter class loads, no thread starts. */
    public static final String EXPORTER_NONE = "none";
    /** OTLP/HTTP (protobuf) through the JDK's HTTP client. */
    public static final String EXPORTER_OTLP = "otlp";
    /** The project's own {@code skillroy-spans/1} JSON lines ({@code spans.jsonl}). */
    public static final String EXPORTER_FILE = "file";

    /** The attribute allow-list of phase6-contracts.md 10.4. */
    public static final String ATTRIBUTES_STANDARD = "standard";
    /** The allow-list less {@code TelemetryAttributes.MINIMAL_DROPS} (phase6-contracts.md 10.3). */
    public static final String ATTRIBUTES_MINIMAL = "minimal";

    /** The default endpoint: the OTLP/HTTP port on the loopback address. */
    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:4318";
    /** The default service name. */
    public static final String DEFAULT_SERVICE_NAME = "skillroy";
    /** The default timeout. */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;
    /** The largest timeout. */
    public static final long MAX_TIMEOUT_MS = 120_000L;
    /** The service version when none is known. */
    public static final String DEVELOPMENT_BUILD = "(development build)";

    /** System property and option: the exporter. */
    public static final String PROP_EXPORTER = "skillroy.telemetry";
    /** System property and option: the endpoint. */
    public static final String PROP_ENDPOINT = "skillroy.telemetry.endpoint";
    /** System property and option: the attribute set. */
    public static final String PROP_ATTRIBUTES = "skillroy.telemetry.attributes";
    /** System property and option: the file exporter's directory. */
    public static final String PROP_OUT = "skillroy.telemetry.out";
    /** System property: the service name. */
    public static final String PROP_SERVICE_NAME = "skillroy.telemetry.serviceName";
    /** System property: the timeout. */
    public static final String PROP_TIMEOUT_MS = "skillroy.telemetry.timeoutMs";
    /** System property and option: the settings file. */
    public static final String PROP_FILE = "skillroy.telemetry.file";

    /** The keys {@link #load} accepts as options. */
    public static final Set<String> OPTION_KEYS = Set.of(PROP_EXPORTER, PROP_ENDPOINT, PROP_ATTRIBUTES, PROP_OUT,
            PROP_SERVICE_NAME, PROP_TIMEOUT_MS, PROP_FILE);

    /**
     * The prefix of the environment variables a header may not name: the ones the inference settings and the model
     * gateway read ({@code ANTHROPIC_API_KEY}, {@code ANTHROPIC_AUTH_TOKEN}, {@code ANTHROPIC_CUSTOM_HEADERS},
     * {@code ANTHROPIC_WORKSPACE_ID}, {@code ANTHROPIC_BASE_URL}, and any later one).
     */
    public static final String MODEL_VARIABLE_PREFIX = "ANTHROPIC_";

    private static final int MAX_FILE_BYTES = 1 << 16;
    private static final List<String> EXPORTERS = List.of(EXPORTER_NONE, EXPORTER_OTLP, EXPORTER_FILE);
    private static final List<String> ATTRIBUTE_SETS = List.of(ATTRIBUTES_STANDARD, ATTRIBUTES_MINIMAL);
    private static final List<String> TOP_KEYS = List.of("version", "exporter", "endpoint", "headers", "out",
            "serviceName", "attributes", "timeoutMs");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}");
    private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final int MAX_HEADERS = 16;

    private static final TelemetrySettings NONE = new TelemetrySettings(EXPORTER_NONE, DEFAULT_ENDPOINT, Map.of(),
            DEFAULT_SERVICE_NAME, DEVELOPMENT_BUILD, ATTRIBUTES_STANDARD, null, DEFAULT_TIMEOUT_MS, null);

    /**
     * Fills the defaults of null components and copies the map. Validation is {@link #load}'s.
     *
     * @throws NullPointerException if the map holds a null key or value
     */
    public TelemetrySettings {
        exporter = exporter == null || exporter.isBlank() ? EXPORTER_NONE : exporter.strip();
        endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.strip();
        Map<String, String> copy = new LinkedHashMap<>();
        if (headerVariables != null) {
            headerVariables.forEach((name, variable) -> copy.put(Objects.requireNonNull(name, "header name"),
                    Objects.requireNonNull(variable, "header variable")));
        }
        headerVariables = Collections.unmodifiableMap(copy);
        serviceName = serviceName == null || serviceName.isBlank() ? DEFAULT_SERVICE_NAME : serviceName.strip();
        serviceVersion = serviceVersion == null || serviceVersion.isBlank() ? DEVELOPMENT_BUILD : serviceVersion;
        attributes = attributes == null || attributes.isBlank() ? ATTRIBUTES_STANDARD : attributes.strip();
        timeoutMs = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    /** No exporter, every other setting at its default. */
    public static TelemetrySettings none() {
        return NONE;
    }

    /** True unless the exporter is {@code none}. */
    public boolean enabled() {
        return !EXPORTER_NONE.equals(exporter);
    }

    /** True for the {@code minimal} attribute set. */
    public boolean minimal() {
        return ATTRIBUTES_MINIMAL.equals(attributes);
    }

    /** {@code <endpoint>/v1/traces}: where OTLP/HTTP posts the spans. */
    public String tracesUrl() {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base + "/v1/traces";
    }

    /** {@code host:port} of the endpoint (for the summary line: never its path, user information or headers). */
    public String endpointHostPort() {
        try {
            URI uri = new URI(endpoint);
            int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            return uri.getHost() + ":" + port;
        } catch (URISyntaxException e) {
            return "?";
        }
    }

    /**
     * The settings the JUnit glue uses: the system properties, the file and the defaults, with the environment.
     *
     * @param credentialsDir where {@code telemetry.yaml} is looked for unless {@value #PROP_FILE} names a file; may be
     *     null
     * @param serviceVersion the glue's version, or null
     * @throws SettingsException as {@link #load(Path, Map, String, UnaryOperator, UnaryOperator)}
     */
    public static TelemetrySettings fromSystemProperties(Path credentialsDir, String serviceVersion)
            throws SettingsException {
        return load(credentialsDir, Map.of(), serviceVersion, System::getProperty, System::getenv);
    }

    /**
     * The settings of an invocation: the options over the system properties over the file over the defaults.
     *
     * @throws SettingsException as {@link #load(Path, Map, String, UnaryOperator, UnaryOperator)}
     */
    public static TelemetrySettings load(Path credentialsDir, Map<String, String> options, String serviceVersion)
            throws SettingsException {
        return load(credentialsDir, options, serviceVersion, System::getProperty, System::getenv);
    }

    /**
     * The settings of an invocation, with the lookups given (tests pass their own; production code passes
     * {@code System::getProperty} and {@code System::getenv}).
     *
     * @param credentialsDir where {@code telemetry.yaml} is looked for when no file is named; may be null
     * @param options values under the names of the system properties ({@link #OPTION_KEYS}), which win over the
     *     properties; null is none
     * @param serviceVersion the {@code service.version} to report; null is {@value #DEVELOPMENT_BUILD}
     * @param properties the system properties
     * @param environment the environment; only {@code environment.apply(name) != null} is asked, for the variables
     *     the headers name
     * @throws SettingsException when a named file is missing, the file is unreadable, malformed or against the
     *     grammar, or a setting breaks a rule above; the message never holds a value
     * @throws IllegalArgumentException for an option key that is not one of {@link #OPTION_KEYS}
     */
    public static TelemetrySettings load(Path credentialsDir, Map<String, String> options, String serviceVersion,
                                         UnaryOperator<String> properties, UnaryOperator<String> environment)
            throws SettingsException {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        Map<String, String> given = options == null ? Map.of() : options;
        for (String key : given.keySet()) {
            if (!OPTION_KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown telemetry option " + key + "; options: "
                        + new TreeSet<>(OPTION_KEYS));
            }
        }
        UnaryOperator<String> lookup = key -> given.containsKey(key) ? given.get(key) : properties.apply(key);
        Builder b = new Builder();
        String fileText = blankToNull(lookup.apply(PROP_FILE));
        Path file = null;
        if (fileText != null) {
            file = path(fileText.strip(), PROP_FILE);
            if (!Files.isRegularFile(file)) {
                throw new SettingsException("telemetry settings file " + file + (Files.exists(file)
                        ? " is not a regular file" : " does not exist") + " (" + PROP_FILE + ")");
            }
        } else if (credentialsDir != null) {
            Path beside = credentialsDir.resolve(FILE_NAME);
            // P2-L8-F1: any entry of that name is read under the owner rule, so that a planted file or link is
            // refused, never silently followed or skipped.
            if (Files.exists(beside, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(beside)) {
                file = beside;
            }
        }
        if (file != null) {
            new FileReader(file).read(b);
        }
        String exporterText = blankToNull(lookup.apply(PROP_EXPORTER));
        if (exporterText != null) {
            String e = exporterText.strip().toLowerCase(Locale.ROOT);
            if (!EXPORTERS.contains(e)) {
                throw new SettingsException(PROP_EXPORTER + " must be none, otlp or file");
            }
            b.exporter = e;
        }
        String endpointText = blankToNull(lookup.apply(PROP_ENDPOINT));
        if (endpointText != null) {
            b.endpoint = endpointText.strip();
            b.endpointWhere = PROP_ENDPOINT;
        }
        String attributesText = blankToNull(lookup.apply(PROP_ATTRIBUTES));
        if (attributesText != null) {
            String a = attributesText.strip().toLowerCase(Locale.ROOT);
            if (!ATTRIBUTE_SETS.contains(a)) {
                throw new SettingsException(PROP_ATTRIBUTES + " must be standard or minimal");
            }
            b.attributes = a;
        }
        String outText = blankToNull(lookup.apply(PROP_OUT));
        if (outText != null) {
            b.out = path(outText.strip(), PROP_OUT);
        }
        String serviceText = blankToNull(lookup.apply(PROP_SERVICE_NAME));
        if (serviceText != null) {
            if (!SERVICE_NAME.matcher(serviceText.strip()).matches()) {
                throw new SettingsException(PROP_SERVICE_NAME + " must be letters, digits, '.', '_' and '-', at most"
                        + " 100");
            }
            b.serviceName = serviceText.strip();
        }
        String timeoutText = blankToNull(lookup.apply(PROP_TIMEOUT_MS));
        if (timeoutText != null) {
            Long ms = parseLong(timeoutText.strip());
            if (ms == null || ms < 1 || ms > MAX_TIMEOUT_MS) {
                throw new SettingsException(PROP_TIMEOUT_MS + " must be a whole number of milliseconds from 1 to "
                        + MAX_TIMEOUT_MS);
            }
            b.timeoutMs = ms;
        }
        String exporter = b.exporter == null ? EXPORTER_NONE : b.exporter;
        if (EXPORTER_NONE.equals(exporter)) {
            return new TelemetrySettings(EXPORTER_NONE, null, Map.of(), b.serviceName, serviceVersion, b.attributes,
                    b.out, b.timeoutMs, file);
        }
        String endpoint = b.endpoint == null ? DEFAULT_ENDPOINT : b.endpoint;
        if (EXPORTER_OTLP.equals(exporter)) {
            String where = b.endpointWhere == null ? "the default endpoint" : b.endpointWhere;
            checkEndpoint(endpoint, where);
            checkHeaders(endpoint, b.headers, where);
            for (Map.Entry<String, String> header : b.headers.entrySet()) {
                if (environment.apply(header.getValue()) == null) {
                    throw new SettingsException("the environment variable " + header.getValue() + " that a"
                            + " telemetry header names is not set");
                }
            }
        }
        if (EXPORTER_FILE.equals(exporter) && b.out == null) {
            throw new SettingsException("the file exporter needs a directory: --out (the CLI writes <out>/telemetry/),"
                    + " " + PROP_OUT + " or out: in " + FILE_NAME);
        }
        return new TelemetrySettings(exporter, endpoint, b.headers, b.serviceName, serviceVersion, b.attributes,
                b.out, b.timeoutMs, file);
    }

    /**
     * The endpoint rule (D141): {@code https://} anywhere, {@code http://} only to a literal loopback address or
     * {@code localhost}; no user information.
     *
     * @param where the option, property or file line the endpoint came from, for the message
     * @throws SettingsException when the endpoint breaks the rule
     */
    public static void checkEndpoint(String endpoint, String where) throws SettingsException {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            throw new SettingsException(where + ": the telemetry endpoint is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!scheme.equals("http") && !scheme.equals("https") || host == null || host.isEmpty()) {
            throw new SettingsException(where + ": the telemetry endpoint must be an http:// or https:// URL with a"
                    + " host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new SettingsException(where + ": the telemetry endpoint may not carry user information; give a"
                    + " header through an environment variable (headers: {Name: env:VARIABLE} in " + FILE_NAME + ")");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new SettingsException(where + ": the telemetry endpoint may not carry a query or a fragment");
        }
        if (scheme.equals("http") && !isLoopback(host)) {
            throw new SettingsException(where + ": an http:// telemetry endpoint must be a literal loopback address"
                    + " (127.0.0.0/8, [::1]) or localhost; use https:// for any other host");
        }
    }

    /**
     * The header rule (Phase 6 security fix, pass 2, P2-L8-F2): a header is sent only to an {@code https://}
     * endpoint. Over {@code http://}, which the endpoint rule allows on loopback only, a header would reach whatever
     * holds that port in clear (another user who bound it while the collector was down, or on a host that runs none),
     * and the default endpoint is such a one, so an operator who gives a backend's token and forgets the endpoint
     * would send it there. A loopback collector without TLS takes no header.
     *
     * @param where the option, property or file line the endpoint came from, or "the default endpoint"
     * @throws SettingsException when a header is given with an {@code http://} endpoint; the message names the rule,
     *     never a header's name or value
     */
    public static void checkHeaders(String endpoint, Map<String, String> headers, String where)
            throws SettingsException {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        String scheme;
        try {
            URI uri = new URI(endpoint);
            scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            scheme = "";
        }
        if (!scheme.equals("https")) {
            throw new SettingsException(where + ": a telemetry header needs an https:// endpoint"
                    + (where.equals("the default endpoint") ? " (the default endpoint " + DEFAULT_ENDPOINT
                    + " is not one; name your collector's endpoint)" : "")
                    + "; over http:// it would reach whatever holds that port in clear (a loopback collector"
                    + " without TLS takes no header)");
        }
    }

    /** True for a literal loopback IP address ({@code 127.0.0.0/8}, {@code ::1}) or the literal {@code localhost}. */
    static boolean isLoopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            // A literal only: ofLiteral never asks a name service.
            return InetAddress.ofLiteral(h).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path path(String text, String where) throws SettingsException {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new SettingsException(where + " is not a valid path");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** What the file and the properties set, over the defaults. */
    private static final class Builder {

        String exporter;
        String endpoint;
        String endpointWhere;
        final Map<String, String> headers = new LinkedHashMap<>();
        Path out;
        String serviceName = DEFAULT_SERVICE_NAME;
        String attributes = ATTRIBUTES_STANDARD;
        long timeoutMs = DEFAULT_TIMEOUT_MS;
    }

    /**
     * Reads {@code telemetry.yaml} against its grammar; messages name the file, the line and the fixed keys of the
     * grammar, never a value and never the text of a key the grammar does not fix, as the inference settings do.
     */
    private static final class FileReader {

        private final Path file;
        private final String name;
        private StrictYaml.Doc doc;

        FileReader(Path file) {
            this.file = file;
            this.name = file.toString();
        }

        void read(Builder b) throws SettingsException {
            byte[] bytes;
            try {
                // Phase 6 security fix, pass 2, P2-L8-F1: read only a file nobody but the current user could have
                // written (SettingsFiles), since the file alone changes what the command sends and where.
                bytes = SettingsFiles.read(file, MAX_FILE_BYTES);
            } catch (SettingsFiles.RefusedException e) {
                throw new SettingsException("telemetry settings file " + name + " cannot be used: " + e.getMessage()
                        + " (a settings file must be a regular file of the current user that nobody else may write"
                        + " to, e.g. chmod go-w; fix it or remove it)");
            } catch (IOException e) {
                throw new SettingsException("telemetry settings file " + name + " cannot be read ("
                        + e.getClass().getName() + ")");
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw new SettingsException(name + ": a telemetry settings file is at most 64 KiB");
            }
            try {
                doc = StrictYaml.read(bytes);
            } catch (YamlException e) {
                throw new SettingsException(where(e.line()) + (e.quotesInput()
                        ? "not valid YAML (the reader's message is withheld because it can quote a value; check the"
                                + " syntax near this line)"
                        : e.reason()));
            }
            if (!(doc.root() instanceof Map<?, ?>)) {
                throw new SettingsException(where(doc.lineOf(doc.root())) + "a telemetry settings file must be a YAML"
                        + " mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) doc.root();
            for (String key : root.keySet()) {
                if (!TOP_KEYS.contains(key)) {
                    throw error(root, key, "a top-level key is not known; allowed keys: "
                            + String.join(", ", TOP_KEYS));
                }
            }
            if (!(root.get("version") instanceof Long n) || n != VERSION) {
                throw root.containsKey("version") ? error(root, "version", "'version' must be 1; this version reads"
                        + " telemetry settings of version 1 only")
                        : new SettingsException(where(doc.lineOf(root)) + "'version' is required (version: 1)");
            }
            Object exporter = root.get("exporter");
            if (exporter != null) {
                if (!(exporter instanceof String e) || !EXPORTERS.contains(e)) {
                    throw error(root, "exporter", "exporter must be none, otlp or file");
                }
                b.exporter = e;
            }
            Object endpoint = root.get("endpoint");
            if (endpoint != null) {
                if (!(endpoint instanceof String e) || e.isBlank()) {
                    throw error(root, "endpoint", "endpoint must be a URL, written as a text");
                }
                b.endpoint = e.strip();
                b.endpointWhere = where(doc.keyLine(root, "endpoint")) + "endpoint";
            }
            Object headers = root.get("headers");
            if (headers != null) {
                if (!(headers instanceof Map<?, ?> map)) {
                    throw error(root, "headers", "headers must be a mapping of header names to env:<VARIABLE>");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                if (typed.size() > MAX_HEADERS) {
                    throw error(root, "headers", "at most " + MAX_HEADERS + " headers");
                }
                for (Map.Entry<String, Object> header : typed.entrySet()) {
                    String key = header.getKey();
                    if (!HEADER_NAME.matcher(key).matches()) {
                        throw error(typed, key, "headers: a header name must be an HTTP token of at most 64"
                                + " characters");
                    }
                    if (!(header.getValue() instanceof String value) || !value.startsWith("env:")) {
                        throw error(typed, key, "headers: a header value is read from an environment variable"
                                + " (env:<VARIABLE>), never written in the file");
                    }
                    String variable = value.substring("env:".length()).strip();
                    if (!VARIABLE.matcher(variable).matches()) {
                        throw error(typed, key, "headers: env: must name an environment variable");
                    }
                    if (variable.toUpperCase(Locale.ROOT).startsWith(MODEL_VARIABLE_PREFIX)) {
                        throw error(typed, key, "headers: a telemetry header may not carry the model key (no "
                                + MODEL_VARIABLE_PREFIX + "* variable)");
                    }
                    b.headers.put(key, variable);
                }
            }
            Object out = root.get("out");
            if (out != null) {
                if (!(out instanceof String o) || o.isBlank()) {
                    throw error(root, "out", "out must be a directory path, a non-empty text");
                }
                try {
                    Path p = Path.of(o.strip());
                    Path dir = file.toAbsolutePath().getParent();
                    b.out = p.isAbsolute() || dir == null ? p : dir.resolve(p).normalize();
                } catch (InvalidPathException e) {
                    throw error(root, "out", "out is not a valid path");
                }
            }
            Object service = root.get("serviceName");
            if (service != null) {
                if (!(service instanceof String s) || !SERVICE_NAME.matcher(s.strip()).matches()) {
                    throw error(root, "serviceName", "serviceName must be letters, digits, '.', '_' and '-', at most"
                            + " 100");
                }
                b.serviceName = s.strip();
            }
            Object attributes = root.get("attributes");
            if (attributes != null) {
                if (!(attributes instanceof String a) || !ATTRIBUTE_SETS.contains(a)) {
                    throw error(root, "attributes", "attributes must be standard or minimal");
                }
                b.attributes = a;
            }
            Object timeout = root.get("timeoutMs");
            if (timeout != null) {
                if (!(timeout instanceof Long ms) || ms < 1 || ms > MAX_TIMEOUT_MS) {
                    throw error(root, "timeoutMs", "timeoutMs must be a whole number of milliseconds from 1 to "
                            + MAX_TIMEOUT_MS);
                }
                b.timeoutMs = ms;
            }
        }

        private String where(int line) {
            return name + (line > 0 ? ":" + line : "") + ": ";
        }

        private SettingsException error(Map<String, Object> map, String key, String reason) {
            return new SettingsException(where(doc.keyLine(map, key)) + reason);
        }
    }
}
