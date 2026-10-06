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

package ai.skillroy.testjava.data;

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.data.yaml.YamlException;
import ai.skillroy.testjava.protocol.SecretKeys;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The secrets of one environment: {@code credentials.yaml} (phase2-contracts.md 4), the only source of passwords for
 * sign-on steps and of JDBC connections. One file per environment, never committed, read by the controller only,
 * located and loaded once per invocation, before the agent is contacted.
 *
 * <pre>
 * version: 1
 * credentials:                     # optional; realm: [a-z0-9-]+, id: any text (quote codes), field: an identifier
 *   staff:
 *     "100": { password: "..." }   # every field value is a secret: text, or a whole number read as its text
 * jdbc:                            # optional; source id: the id used in data.query
 *   reporting:
 *     url: jdbc:...                # required
 *     user: ...                    # optional
 *     password: "..."              # optional, a secret
 *     driver: org.example.Driver   # optional; found through the context class loader (see JdbcDataChannel)
 *     statementTimeout: 10s        # optional, default 10s, at most 120s
 *     loginTimeout: 10s            # optional, default 10s, at most 120s
 *     properties: { name: value }  # optional extra driver properties; the values are secrets
 * masking:                         # optional (2026-10-06, the owner's decision on phase2-contracts.md 17 Q14)
 *   minLengthInText: 8             # the shortest secret also masked inside longer text; default 8, from 1 to 1000
 * </pre>
 *
 * <p>Read with {@link StrictYaml}, the reader scenarios use: unknown keys, duplicate keys and ambiguous numbers are
 * errors. What counts as a secret: every field value of an entry, a source's {@code password}, every value under
 * {@code properties} except a boolean flag, the password of the user information of a source's {@code url} (S21:
 * {@code scheme://user:password@host} and {@code jdbc:oracle:<driver>:user/password@}), and the value of a
 * {@code key=value} pair inside the url whose key looks secret by {@link SecretKeys} ({@code ?password=...}).
 * {@link #mask()} knows all of them, with the file's threshold ({@link #minLengthInText()}): an environment whose
 * test accounts have short credentials may lower it and accept the false redactions; nothing here refuses a
 * credential for its length. No message, exception or {@link #toString()} contains a value from the file.
 *
 * <p>Immutable; thread-safe.
 */
public final class Credentials {

    /** The file format version this code reads. */
    public static final int VERSION = 1;
    /** File name looked for in {@code <bindingsRoot>/<envId>/}. */
    public static final String FILE_NAME = "credentials.yaml";
    /** System property naming the file. */
    public static final String PROPERTY = "skillroy.credentials";
    /** Environment variable naming the file. */
    public static final String ENV = "SKILLROY_CREDENTIALS";

    static final Duration DEFAULT_STATEMENT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(10);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(120);
    /** A larger file is not a credentials file. */
    static final int MAX_FILE_BYTES = 1 << 20;

    private static final Pattern REALM = Pattern.compile("[a-z0-9-]+");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern ENV_ID = Pattern.compile("[a-z0-9-]+");
    private static final Pattern CLASS_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]{0,8})(ms|s|m)");
    /** {@code scheme://user:password@}: group 2 is the password (S21). */
    private static final Pattern USERINFO = Pattern.compile("(://[^/?#\\s:@]*:)([^/?#\\s]*)@");
    /** {@code jdbc:oracle:thin:user/password@}: group 2 is the password (S21). */
    private static final Pattern ORACLE_USERINFO =
            Pattern.compile("(jdbc:oracle:[a-z]+:[^/@\\s]*/)([^@\\s]*)@", Pattern.CASE_INSENSITIVE);
    /** A {@code key=value} pair inside a url (query string or driver-specific suffix). */
    private static final Pattern URL_PAIR = Pattern.compile("([A-Za-z0-9_.\\-]+)=([^;&,\\s]*)");

    private static final Set<String> TOP_KEYS = Set.of("version", "credentials", "jdbc", "masking");
    private static final Set<String> SOURCE_KEYS =
            Set.of("url", "user", "password", "driver", "statementTimeout", "loginTimeout", "properties");
    private static final Set<String> MASKING_KEYS = Set.of("minLengthInText");

    private static final Credentials EMPTY =
            new Credentials("no credentials", Map.of(), Map.of(), SecretMask.MIN_SUBSTRING);

    private final String origin;
    /** realm -> id -> field -> value, in file order. */
    private final Map<String, Map<String, Map<String, String>>> realms;
    private final Map<String, JdbcSource> jdbc;
    private final int minLengthInText;
    private final SecretMask mask;

    private Credentials(String origin, Map<String, Map<String, Map<String, String>>> realms,
                        Map<String, JdbcSource> jdbc, int minLengthInText) {
        this.origin = origin;
        this.realms = realms;
        this.jdbc = jdbc;
        this.minLengthInText = minLengthInText;
        Set<String> secrets = new LinkedHashSet<>();
        for (Map<String, Map<String, String>> ids : realms.values()) {
            for (Map<String, String> fields : ids.values()) {
                secrets.addAll(fields.values());
            }
        }
        for (JdbcSource source : jdbc.values()) {
            secrets.addAll(source.secrets());
        }
        this.mask = SecretMask.of(secrets, minLengthInText);
    }

    /** One JDBC source of the file. The password and the properties leave it only towards {@code Driver.connect}. */
    record JdbcSource(String id, String url, String user, String password, String driver, Duration statementTimeout,
                      Duration loginTimeout, Map<String, String> properties) {

        /** The values of this source that are secrets. */
        List<String> secrets() {
            List<String> out = new ArrayList<>();
            if (password != null) {
                out.add(password);
            }
            for (String value : properties.values()) {
                // A flag (true/false) is never a secret (A25: booleans never); masking the text "true" everywhere
                // would only damage the outputs.
                if (!"true".equals(value) && !"false".equals(value)) {
                    out.add(value);
                }
            }
            out.addAll(urlSecrets(url));
            return out;
        }

        /** The id and the settings that are not secret; never the url, the password or a property value. */
        @Override
        public String toString() {
            return "JdbcSource[" + id + ", driver=" + driver + ", statementTimeout=" + statementTimeout
                    + ", loginTimeout=" + loginTimeout + ", properties=" + properties.keySet() + "]";
        }
    }

    /** Credentials without any entry or source. */
    public static Credentials empty() {
        return EMPTY;
    }

    /**
     * Loads a credentials file: its permissions first, then its content.
     *
     * @throws CredentialsException if the file is missing, not a regular file, accessible to other users, unreadable,
     *     larger than 1 MiB, not valid YAML or against the grammar; the message names the file and never a value
     */
    public static Credentials load(Path file) throws CredentialsException {
        return load(file, CredentialFiles.REAL);
    }

    static Credentials load(Path file, CredentialFiles.Access access) throws CredentialsException {
        if (file == null) {
            throw new CredentialsException("no credentials file was named");
        }
        String name = file.toString();
        if (!Files.exists(file)) {
            throw new CredentialsException("credentials file " + name + " does not exist");
        }
        if (!Files.isRegularFile(file)) {
            throw new CredentialsException("credentials file " + name + " is not a regular file");
        }
        String problem = CredentialFiles.problem(file, access);
        if (problem != null) {
            throw new CredentialsException(name + ": " + problem);
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            throw new CredentialsException("credentials file " + name + " cannot be read (" + e.getClass().getName()
                    + ")");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new CredentialsException(name + ": a credentials file is at most 1 MiB");
        }
        StrictYaml.Doc doc;
        try {
            doc = StrictYaml.read(bytes);
        } catch (YamlException e) {
            throw new CredentialsException(where(name, e.line()) + (e.quotesInput()
                    ? "not valid YAML (the reader's message is withheld because it can quote a value; check the"
                            + " syntax near this line, and quote codes and passwords)"
                    : e.reason()));
        }
        if (!(doc.root() instanceof Map<?, ?>)) {
            throw new CredentialsException(where(name, doc.lineOf(doc.root())) + "a credentials file must be a YAML"
                    + " mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) doc.root();
        return new Reader(name, doc).read(root);
    }

    /**
     * Credentials from an already parsed document of the same grammar (tests, embedding).
     *
     * @param origin a label for messages, such as the name the caller would give the file
     * @throws CredentialsException if the document is against the grammar; the message never holds a value
     */
    public static Credentials of(Map<String, Object> document, String origin) throws CredentialsException {
        String name = origin == null ? "credentials" : origin;
        if (document == null) {
            throw new CredentialsException(name + ": no document");
        }
        return new Reader(name, null).read(document);
    }

    /**
     * Where the credentials of an invocation are: {@code explicit} (the CLI's {@code --credentials}); else the system
     * property {@value #PROPERTY}; else the environment variable {@value #ENV}; else
     * {@code <bindingsRoot>/<envId>/credentials.yaml} when that file exists; else none.
     *
     * @param bindingsRoot the invocation's bindings root, or null
     * @param envId the environment id ({@code [a-z0-9-]+}); another value skips the last rule
     * @return the file to load, or null when there are no credentials
     */
    public static Path locate(Path explicit, Path bindingsRoot, String envId) {
        return locate(explicit, bindingsRoot, envId, System::getProperty, System::getenv);
    }

    static Path locate(Path explicit, Path bindingsRoot, String envId, UnaryOperator<String> properties,
                       UnaryOperator<String> environment) {
        if (explicit != null) {
            return explicit;
        }
        String property = properties.apply(PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property.strip());
        }
        String variable = environment.apply(ENV);
        if (variable != null && !variable.isBlank()) {
            return Path.of(variable.strip());
        }
        if (bindingsRoot != null && envId != null && ENV_ID.matcher(envId).matches()) {
            Path candidate = bindingsRoot.resolve(envId).resolve(FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** True when the realm has an entry with this id (ids are compared as text). */
    public boolean has(String realm, String id) {
        Map<String, Map<String, String>> ids = realms.get(realm);
        return ids != null && id != null && ids.containsKey(id);
    }

    /** The ids of the sources served over JDBC, sorted. */
    public Set<String> jdbcSources() {
        return Collections.unmodifiableSet(new TreeSet<>(jdbc.keySet()));
    }

    /** A mask that knows every secret of the file, with the file's threshold ({@link #minLengthInText()}). */
    public SecretMask mask() {
        return mask;
    }

    /**
     * The file's {@code masking.minLengthInText}: the shortest secret the mask also replaces inside a longer text
     * (phase2-contracts.md 4). {@link SecretMask#MIN_SUBSTRING} (8) when the file does not set it.
     */
    public int minLengthInText() {
        return minLengthInText;
    }

    /**
     * The secret fields of one entry, in file order (empty for an entry without fields). Only the binder (for the
     * credential join) and the JDBC channel call this.
     *
     * @return the fields, or null when the realm has no entry with this id
     */
    public Map<String, String> secrets(String realm, String id) {
        Map<String, Map<String, String>> ids = realms.get(realm);
        return ids == null || id == null ? null : ids.get(id);
    }

    /** The source with this id, or null. */
    JdbcSource jdbc(String sourceId) {
        return sourceId == null ? null : jdbc.get(sourceId);
    }

    /** The label messages use for the file. */
    public String origin() {
        return origin;
    }

    /** The origin, realm names with their entry counts, and the JDBC source ids; never a value. */
    @Override
    public String toString() {
        Map<String, Integer> counts = new TreeMap<>();
        realms.forEach((realm, ids) -> counts.put(realm, ids.size()));
        return "Credentials[" + origin + ", realms=" + counts + ", jdbc=" + jdbcSources() + "]";
    }

    /** The url's secrets: the user-information password (S21) and the values of secret-looking pairs. */
    static List<String> urlSecrets(String url) {
        List<String> out = new ArrayList<>();
        if (url == null) {
            return out;
        }
        for (Pattern p : List.of(USERINFO, ORACLE_USERINFO)) {
            Matcher m = p.matcher(url);
            while (m.find()) {
                if (!m.group(2).isEmpty()) {
                    out.add(m.group(2));
                }
            }
        }
        Matcher pair = URL_PAIR.matcher(url);
        while (pair.find()) {
            if (SecretKeys.looksSecret(pair.group(1)) && !pair.group(2).isEmpty()) {
                out.add(pair.group(2));
            }
        }
        return out;
    }

    private static String where(String name, int line) {
        return name + (line > 0 ? ":" + line : "") + ": ";
    }

    /**
     * Reads one document against the grammar; messages name lines, the fixed keys of the grammar and the parent path
     * of a problem, never a value and never the text of a key the grammar does not fix (it may be a value typed in
     * the wrong place).
     */
    private static final class Reader {

        private final String name;
        private final StrictYaml.Doc doc;

        Reader(String name, StrictYaml.Doc doc) {
            this.name = name;
            this.doc = doc;
        }

        Credentials read(Map<String, Object> root) throws CredentialsException {
            for (String key : root.keySet()) {
                if (!TOP_KEYS.contains(key)) {
                    throw error(root, key, unquoted(root, key, "top-level key") + " is not known; allowed keys:"
                            + " version, credentials, jdbc, masking");
                }
            }
            if (!root.containsKey("version")) {
                throw new CredentialsException(where(name, doc == null ? 0 : doc.lineOf(root))
                        + "'version' is required (version: 1)");
            }
            Object version = root.get("version");
            if (!(version instanceof Number n) || n.doubleValue() != VERSION || n instanceof Double) {
                throw error(root, "version", "'version' must be 1; this version reads credentials files of"
                        + " version 1 only");
            }
            Map<String, Map<String, Map<String, String>>> realms = realms(root);
            Map<String, JdbcSource> sources = sources(root);
            return new Credentials(name, realms, sources, masking(root));
        }

        /** {@code masking.minLengthInText}, or the default: a whole number from 1 to 1000. */
        private int masking(Map<String, Object> root) throws CredentialsException {
            Map<String, Object> raw = optionalMap(root, "masking", "'masking'");
            for (String key : raw.keySet()) {
                if (!MASKING_KEYS.contains(key)) {
                    throw error(raw, key, "masking: " + unquoted(raw, key, "key") + " is not known; allowed keys:"
                            + " minLengthInText");
                }
            }
            Object value = raw.get("minLengthInText");
            if (value == null) {
                return SecretMask.MIN_SUBSTRING;
            }
            if ((value instanceof Long || value instanceof Integer || value instanceof Short)
                    && ((Number) value).longValue() >= 1
                    && ((Number) value).longValue() <= SecretMask.MAX_MIN_SUBSTRING) {
                return ((Number) value).intValue();
            }
            throw error(raw, "minLengthInText", "masking.minLengthInText must be a whole number from 1 to "
                    + SecretMask.MAX_MIN_SUBSTRING);
        }

        private Map<String, Map<String, Map<String, String>>> realms(Map<String, Object> root)
                throws CredentialsException {
            Map<String, Object> raw = optionalMap(root, "credentials", "'credentials'");
            Map<String, Map<String, Map<String, String>>> realms = new LinkedHashMap<>();
            for (Map.Entry<String, Object> realm : raw.entrySet()) {
                String realmName = realm.getKey();
                if (!REALM.matcher(realmName).matches()) {
                    throw error(raw, realmName, "credentials: " + unquoted(raw, realmName, "realm name")
                            + " must match [a-z0-9-]+");
                }
                Map<String, Object> ids = requireMap(raw, realmName, "credentials." + realmName);
                Map<String, Map<String, String>> entries = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : ids.entrySet()) {
                    String id = entry.getKey();
                    String path = "credentials." + realmName + "." + id;
                    if (id.isEmpty()) {
                        throw error(ids, id, "an id in realm '" + realmName + "' is empty");
                    }
                    Map<String, Object> fields = requireMap(ids, id, path);
                    Map<String, String> values = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> field : fields.entrySet()) {
                        String fieldName = field.getKey();
                        if (!FIELD.matcher(fieldName).matches()) {
                            throw error(fields, fieldName, path + ": " + unquoted(fields, fieldName, "field name")
                                    + " must be an identifier ([A-Za-z_][A-Za-z0-9_]*)");
                        }
                        values.put(fieldName, secretText(fields, fieldName, path + ": the value of "
                                + unquoted(fields, fieldName, "field")));
                    }
                    entries.put(id, Collections.unmodifiableMap(values));
                }
                realms.put(realmName, Collections.unmodifiableMap(entries));
            }
            return Collections.unmodifiableMap(realms);
        }

        private Map<String, JdbcSource> sources(Map<String, Object> root) throws CredentialsException {
            Map<String, Object> raw = optionalMap(root, "jdbc", "'jdbc'");
            Map<String, JdbcSource> sources = new LinkedHashMap<>();
            for (String id : raw.keySet()) {
                if (!SOURCE.matcher(id).matches()) {
                    throw error(raw, id, "jdbc: " + unquoted(raw, id, "source id")
                            + " must match [A-Za-z0-9][A-Za-z0-9_.-]*");
                }
                String path = "jdbc." + id;
                Map<String, Object> source = requireMap(raw, id, path);
                for (String key : source.keySet()) {
                    if (!SOURCE_KEYS.contains(key)) {
                        throw error(source, key, path + ": " + unquoted(source, key, "key") + " is not known;"
                                + " allowed keys: url, user, password, driver, statementTimeout, loginTimeout,"
                                + " properties");
                    }
                }
                String url = text(source, "url", path + ".url", true);
                if (!url.startsWith("jdbc:")) {
                    throw error(source, "url", path + ".url must start with 'jdbc:'");
                }
                String user = text(source, "user", path + ".user", false);
                String password = source.containsKey("password")
                        ? secretText(source, "password", path + ".password") : null;
                String driver = text(source, "driver", path + ".driver", false);
                if (driver != null && !CLASS_NAME.matcher(driver).matches()) {
                    throw error(source, "driver", path + ".driver must be a Java class name");
                }
                Duration statement = duration(source, "statementTimeout", path, DEFAULT_STATEMENT_TIMEOUT);
                Duration login = duration(source, "loginTimeout", path, DEFAULT_LOGIN_TIMEOUT);
                Map<String, Object> rawProperties = optionalMap(source, "properties", path + ".properties");
                Map<String, String> properties = new LinkedHashMap<>();
                for (Map.Entry<String, Object> property : rawProperties.entrySet()) {
                    String key = property.getKey();
                    String propertyPath = path + ".properties: the value of " + unquoted(rawProperties, key,
                            "property");
                    Object value = property.getValue();
                    if (value instanceof Boolean b) {
                        properties.put(key, b.toString());
                    } else {
                        properties.put(key, secretText(rawProperties, key, propertyPath));
                    }
                }
                sources.put(id, new JdbcSource(id, url, user, password, driver, statement, login,
                        Collections.unmodifiableMap(properties)));
            }
            return Collections.unmodifiableMap(sources);
        }

        /** A secret value: non-empty text, or a whole number taken as its text. Never echoed. */
        private String secretText(Map<String, Object> map, String key, String path) throws CredentialsException {
            Object value = map.get(key);
            if (value instanceof String s) {
                if (s.isEmpty()) {
                    throw error(map, key, path + " must not be empty");
                }
                return s;
            }
            if (value instanceof Long || value instanceof Integer || value instanceof Short
                    || value instanceof java.math.BigInteger) {
                return value.toString();
            }
            throw error(map, key, path + " must be a text or a whole number (write it in quotes)");
        }

        private String text(Map<String, Object> map, String key, String path, boolean required)
                throws CredentialsException {
            Object value = map.get(key);
            if (value == null) {
                if (required) {
                    throw error(map, key, path + " is required");
                }
                return null;
            }
            if (!(value instanceof String s) || s.isEmpty()) {
                throw error(map, key, path + " must be a non-empty text");
            }
            return s;
        }

        private Duration duration(Map<String, Object> map, String key, String path, Duration dflt)
                throws CredentialsException {
            Object value = map.get(key);
            if (value == null) {
                return dflt;
            }
            Matcher m = value instanceof String s ? DURATION.matcher(s.strip()) : null;
            if (m == null || !m.matches()) {
                throw error(map, key, path + "." + key + " must be a duration such as 10s, 500ms or 2m");
            }
            long n = Long.parseLong(m.group(1));
            Duration d = switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                default -> Duration.ofMinutes(n);
            };
            if (d.compareTo(MAX_TIMEOUT) > 0) {
                throw error(map, key, path + "." + key + " must be at most 120s");
            }
            return d;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> optionalMap(Map<String, Object> map, String key, String path)
                throws CredentialsException {
            Object value = map.get(key);
            if (value == null) {
                return Map.of();
            }
            if (!(value instanceof Map<?, ?>)) {
                throw error(map, key, path + " must be a mapping");
            }
            return (Map<String, Object>) value;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> requireMap(Map<String, Object> map, String key, String path)
                throws CredentialsException {
            Object value = map.get(key);
            if (!(value instanceof Map<?, ?>)) {
                throw error(map, key, path + " must be a mapping");
            }
            return (Map<String, Object>) value;
        }

        /**
         * Points at a key without quoting it: text typed where the grammar wants a key may be a secret (a password
         * written without its field name), and no message may hold one (section 4). In a file the line in the
         * message's prefix shows where; in a document built in code its position among its siblings does.
         */
        private String unquoted(Map<String, Object> map, String key, String noun) {
            if (doc != null && doc.keyLine(map, key) > 0) {
                return "a " + noun;
            }
            int position = 1;
            for (String k : map.keySet()) {
                if (k.equals(key)) {
                    break;
                }
                position++;
            }
            return noun + " " + position;
        }

        private CredentialsException error(Map<String, Object> map, String key, String reason) {
            int line = doc == null ? 0 : doc.keyLine(map, key);
            return new CredentialsException(where(name, line) + reason);
        }
    }
}
