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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.Resolution;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The journal of an interactive session (phase5-contracts.md 12.3, decision D80): what the session did, in order, as
 * typed entries, written as the session runs to {@code <outputDir>/journal.jsonl} (append-only, one JSON object per
 * line, flushed per line, every record through the session's secret mask) and kept in memory; and the export of that
 * record as an ordinary scenario ({@link #export}), which replays with zero model calls.
 *
 * <p>Records, each starting with {@code type}: {@code session-start {id, pack, packVersion, envId, env: {fingerprint,
 * components}, readOnly, options, startedAt, primitives}}, {@code step {stepId, origin, verb?, args?, calls:
 * [{primitive, args, sync, timeoutMs, await?, capture?}], outcome: {status, kind?, message?, detail?, executed,
 * seqBefore, seqAfter, durationMs, degradedFidelity}, snapshotHash?, provenance?}} (provenance: phase7-contracts.md
 * 9.2), {@code resolve {slot, kind, args, strategy,
 * verify, credential?, resolution}}, {@code oracle {name, fn, args, inputsHash, value}}, {@code check {path, matcher,
 * actual, held, reason}}, {@code observe {path, value}}, {@code note {origin, text}}, {@code budget {scope, spentUsd,
 * limitUsd, exhausted}}, {@code session-end {reason, steps, cost}}. A step's {@code args} are as given (references in
 * place; for a raw call, the call's own arguments), its calls' {@code args} as sent (substituted, then masked); a
 * {@code resolve}'s {@code args} as given and its {@code resolution} in the bindings file's shape (P2 9).
 *
 * <p>The entries kept in memory are the records as written and read back, so that a journal read from its file
 * ({@link #read}) exports exactly what the live journal exports.
 *
 * <p>Thread-safe: appends and reads are synchronised.
 */
public final class StepJournal {

    /** The file name inside a session's output directory. */
    public static final String FILE = "journal.jsonl";

    private final DomainPack pack;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private BufferedWriter out;
    private String error;
    /**
     * Told the type of every record appended (phase6-contracts.md 10.4: the session span's events are the journal's
     * record types, never their content); set by the session, already wrapped so that it never throws.
     */
    RunObserver observer = RunObserver.NONE;

    private StepJournal(DomainPack pack, Path file, BufferedWriter out) {
        this.pack = pack;
        this.file = file;
        this.out = out;
    }

    /**
     * A new journal writing to {@code <dir>/journal.jsonl} (replacing an old one), or kept in memory only when
     * {@code dir} is null.
     *
     * @throws IOException if the directory or the file cannot be created
     */
    static StepJournal open(Path dir, DomainPack pack) throws IOException {
        if (dir == null) {
            return new StepJournal(pack, null, null);
        }
        Path file = dir.resolve(FILE);
        // Phase 6 security fix, pass 2, P2-L8-F3: created exclusively in a directory of the current user.
        return new StepJournal(pack, file, OutputFiles.newWriter(file));
    }

    /**
     * Reads a journal back from its file, for an export later or elsewhere.
     *
     * @param pack the pack the session ran with (the export compiles against it)
     * @throws IOException if the file cannot be read, or a line is not a journal record (the message names the line)
     */
    public static StepJournal read(Path file, DomainPack pack) throws IOException {
        Objects.requireNonNull(pack, "pack");
        StepJournal journal = new StepJournal(pack, file, null);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                journal.entries.add(parse(Json.parseObject(line)));
            } catch (JsonException | IllegalArgumentException | ClassCastException | NullPointerException e) {
                throw new IOException(file.getFileName() + " line " + (i + 1) + ": not a journal record: "
                        + e.getMessage(), e);
            }
        }
        return journal;
    }

    /** The entries, in order; a copy. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** The journal's file, or null when it is kept in memory only. */
    public Path file() {
        return file;
    }

    /** The first write error, or null: writing stops at the first one, the entries are still kept. */
    public synchronized String error() {
        return error;
    }

    /** The pack the session ran with. */
    DomainPack pack() {
        return pack;
    }

    /**
     * Exports the journal as a scenario (phase5-contracts.md 12.3): see {@link JournalExport}. Writes nothing.
     *
     * @throws ExportException when an argument was masked, a call has no {@code call} form, or the scenario does not
     *     compile or its references do not resolve against the exported bindings
     */
    public ExportResult export(ExportOptions options) throws ExportException {
        Objects.requireNonNull(options, "options");
        return new JournalExport(entries(), pack, options).run();
    }

    /**
     * Appends one record: an image's bytes elided ({@link Images}: a check's {@code actual} or an observed
     * {@code value} that holds a capture keeps its shape, its {@code base64} written {@code "<png: N bytes>"}; phase6
     * security fix, pass 2, P2-L6-F3), masked, written as one line, and kept as the line reads back.
     *
     * @return the entry as kept
     */
    synchronized Entry append(Entry entry, SecretMask mask) {
        Map<String, Object> json = entry.toJson();
        Object masked = (mask == null ? SecretMask.NONE : mask).apply(Images.elided(RunLog.jsonForm(json, 0)));
        String line = Json.write(masked);
        Entry kept = parse(Json.parseObject(line));
        entries.add(kept);
        if (out != null) {
            try {
                out.write(line);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                error = e.toString();
                closeQuietly();
            }
        }
        observer.sessionRecord(kept.type());
        return kept;
    }

    /** Closes the file; the entries stay readable. Idempotent. */
    synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                if (error == null) {
                    error = e.toString();
                }
            }
            out = null;
        }
    }

    // ------------------------------------------------------------------------------------------------ entries

    /** One journal record. */
    public sealed interface Entry permits SessionStart, StepEntry, ResolveEntry, FixtureEntry, OracleEntry, CheckEntry,
            ObserveEntry, NoteEntry, BudgetEntry, SessionEnd {

        /** The record's {@code type}. */
        String type();

        /** The record as {@code journal.jsonl} holds it, {@code type} first; a new map on each call. */
        Map<String, Object> toJson();
    }

    /**
     * {@code session-start}: the session, its pack, its environment and its options.
     *
     * @param id the session id
     * @param pack the pack id
     * @param packVersion the pack version
     * @param envId the environment id
     * @param env {@code {fingerprint, components}}
     * @param readOnly whether the session refused mutating calls
     * @param options the session's options ({@code stepTimeoutMs}, {@code readOnly}, {@code recoverOnClose},
     *     {@code ui?}, {@code web?})
     * @param startedAt when it opened, ISO-8601, from the session's clock
     * @param primitives the target's primitives that name a capability: primitive to capability id (what an export's
     *     {@code requires} is made of)
     * @param reads the target's primitives that change nothing (neither mutating nor destructive in its catalogue),
     *     sorted: a raw call of one of them is an observation, which an export leaves out unless
     *     {@link ExportOptions#includeReads()}; empty in a journal written before the field existed (every raw call is
     *     then exported)
     */
    public record SessionStart(String id, String pack, String packVersion, String envId, Map<String, Object> env,
                               boolean readOnly, Map<String, Object> options, String startedAt,
                               Map<String, String> primitives, List<String> reads) implements Entry {

        /** Copies and freezes; the reads are sorted. */
        @SuppressWarnings("unchecked")
        public SessionStart {
            Objects.requireNonNull(id, "id");
            env = env == null ? Map.of() : (Map<String, Object>) Plan.freeze(env);
            options = options == null ? Map.of() : (Map<String, Object>) Plan.freeze(options);
            primitives = primitives == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(primitives));
            reads = reads == null ? List.of() : List.copyOf(new java.util.TreeSet<>(reads));
        }

        /** A start record without the reads (the shape before {@code reads}): every raw call is exported. */
        public SessionStart(String id, String pack, String packVersion, String envId, Map<String, Object> env,
                            boolean readOnly, Map<String, Object> options, String startedAt,
                            Map<String, String> primitives) {
            this(id, pack, packVersion, envId, env, readOnly, options, startedAt, primitives, null);
        }

        @Override
        public String type() {
            return "session-start";
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> json = RunLog.fields("type", type(), "id", id, "pack", pack, "packVersion",
                    packVersion, "envId", envId, "env", env, "readOnly", readOnly, "options", options);
            json.put("startedAt", startedAt);
            json.put("primitives", primitives);
            json.put("reads", reads);
            return json;
        }

        static SessionStart fromJson(Map<String, Object> json) {
            Map<String, String> primitives = new LinkedHashMap<>();
            if (json.get("primitives") instanceof Map<?, ?> map) {
                map.forEach((k, v) -> primitives.put(String.valueOf(k), String.valueOf(v)));
            }
            return new SessionStart(text(json, "id"), text(json, "pack"), text(json, "packVersion"),
                    text(json, "envId"), map(json, "env"), Boolean.TRUE.equals(json.get("readOnly")),
                    map(json, "options"), text(json, "startedAt"), primitives, strings(json, "reads"));
        }
    }

    /**
     * {@code step}: one step the session ran (or refused), with its calls and outcome.
     *
     * @param stepId {@code s1, s2, …}
     * @param origin who asked for it
     * @param verb the verb, or null for a raw call
     * @param args the verb's arguments as given (references in place), or a raw call's own arguments
     * @param calls the calls: {@code {primitive, args, sync, timeoutMs, await?, capture?}}, args as sent and masked
     * @param outcome {@code {status, kind?, message?, detail?, executed, seqBefore, seqAfter, durationMs,
     *     degradedFidelity}}
     * @param snapshotHash {@code sha256:} of the snapshot text after a passed step a model chose, or null
     * @param provenance what a vision tool that chose the step handed in with the act (phase7-contracts.md 9.2,
     *     decisions D331, D351): {@code tier}, {@code via?}, {@code intent?} (masked, at most 500 characters),
     *     {@code model?}, {@code requestId?}, {@code capture?}, {@code point?}, {@code hitTest?}, {@code anchor?},
     *     {@code verdict?}; no bytes; null for a step no vision tool chose. The eighth component
     */
    public record StepEntry(String stepId, Origin origin, String verb, Object args, List<Map<String, Object>> calls,
                            Map<String, Object> outcome, String snapshotHash, Map<String, Object> provenance)
            implements Entry {

        /** Copies and freezes. */
        @SuppressWarnings("unchecked")
        public StepEntry {
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(origin, "origin");
            args = Plan.freeze(args);
            calls = calls == null ? List.of() : (List<Map<String, Object>>) Plan.freeze(calls);
            outcome = outcome == null ? Map.of() : (Map<String, Object>) Plan.freeze(outcome);
            provenance = provenance == null ? null : (Map<String, Object>) Plan.freeze(provenance);
        }

        /** A step without provenance (the Phase 5 shape). */
        public StepEntry(String stepId, Origin origin, String verb, Object args, List<Map<String, Object>> calls,
                         Map<String, Object> outcome, String snapshotHash) {
            this(stepId, origin, verb, args, calls, outcome, snapshotHash, null);
        }

        /** The outcome's status. */
        public Status status() {
            return Status.valueOf(String.valueOf(outcome.get("status")));
        }

        /** True for PASS and DEGRADED. */
        public boolean succeeded() {
            Status status = status();
            return status == Status.PASS || status == Status.DEGRADED;
        }

        @Override
        public String type() {
            return "step";
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> json = RunLog.fields("type", type(), "stepId", stepId, "origin", origin.toJson());
            if (verb != null) {
                json.put("verb", verb);
            }
            if (args != null) {
                json.put("args", args);
            }
            json.put("calls", calls);
            json.put("outcome", outcome);
            if (snapshotHash != null) {
                json.put("snapshotHash", snapshotHash);
            }
            if (provenance != null) {
                json.put("provenance", provenance);
            }
            return json;
        }

        @SuppressWarnings("unchecked")
        static StepEntry fromJson(Map<String, Object> json) {
            List<Map<String, Object>> calls = new ArrayList<>();
            if (json.get("calls") instanceof List<?> list) {
                for (Object item : list) {
                    calls.add((Map<String, Object>) item);
                }
            }
            return new StepEntry(text(json, "stepId"), Origin.fromJson(json.get("origin")), text(json, "verb"),
                    json.get("args"), calls, map(json, "outcome"), text(json, "snapshotHash"),
                    json.get("provenance") instanceof Map<?, ?> ? map(json, "provenance") : null);
        }
    }

    /**
     * {@code resolve}: a slot the session resolved live.
     *
     * @param slot the slot name
     * @param kind the query kind
     * @param args the arguments as given (references in place)
     * @param strategy the strategy tokens as given (empty: not restricted)
     * @param verify the verifier ids as given (empty: the pack's for the kind)
     * @param credential the slot's credential declaration, or null
     * @param resolution the resolution in the bindings file's shape (P2 9)
     */
    public record ResolveEntry(String slot, String kind, Map<String, Object> args, List<String> strategy,
                               List<String> verify, CredentialSpec credential, Map<String, Object> resolution)
            implements Entry {

        /** Copies and freezes. */
        @SuppressWarnings("unchecked")
        public ResolveEntry {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(kind, "kind");
            args = args == null ? Map.of() : (Map<String, Object>) Plan.freeze(args);
            strategy = strategy == null ? List.of() : List.copyOf(strategy);
            verify = verify == null ? List.of() : List.copyOf(verify);
            resolution = resolution == null ? Map.of() : (Map<String, Object>) Plan.freeze(resolution);
        }

        /**
         * The resolution, read as a bindings file reads it.
         *
         * @throws IllegalArgumentException if the recorded shape is not a resolution
         */
        public Resolution toResolution() {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("bindingsVersion", (long) BindingsFile.VERSION);
            file.put("scenarioId", "journal");
            file.put("envId", "journal");
            file.put("pack", null);
            file.put("packVersion", null);
            file.put("planHash", null);
            file.put("resolveHash", "journal");
            file.put("env", RunLog.fields("fingerprint", "journal", "components", Map.of()));
            file.put("resolutions", Map.of(slot, resolution));
            file.put("oracles", Map.of());
            try {
                return BindingsFile.parse(Json.write(file)).resolutions().get(slot);
            } catch (BindingsFormatException e) {
                throw new IllegalArgumentException("the resolution of slot '" + slot + "' in the journal is not a"
                        + " bindings-file resolution: " + e.getMessage(), e);
            }
        }

        @Override
        public String type() {
            return "resolve";
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> json = RunLog.fields("type", type(), "slot", slot, "kind", kind, "args", args,
                    "strategy", strategy, "verify", verify);
            if (credential != null) {
                json.put("credential", RunLog.fields("realm", credential.realm(), "key", credential.key()));
            }
            json.put("resolution", resolution);
            return json;
        }

        static ResolveEntry fromJson(Map<String, Object> json) {
            CredentialSpec credential = null;
            if (json.get("credential") instanceof Map<?, ?> c) {
                credential = new CredentialSpec(String.valueOf(c.get("realm")), String.valueOf(c.get("key")));
            }
            return new ResolveEntry(text(json, "slot"), text(json, "kind"), map(json, "args"),
                    strings(json, "strategy"), strings(json, "verify"), credential, map(json, "resolution"));
        }
    }

    /**
     * {@code fixture}: a fixture slot the session bound (phase7-contracts.md 9.2, D-VM-6; the scenario player binds a
     * scenario's fixture slots this way).
     *
     * @param slot the slot name
     * @param value the value as given (references in place)
     * @param credential the slot's credential declaration, or null
     * @param origin who bound it (a slot with a credential: always a person)
     */
    public record FixtureEntry(String slot, Object value, CredentialSpec credential, Origin origin) implements Entry {

        /** Freezes. */
        public FixtureEntry {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(origin, "origin");
            value = Plan.freeze(value);
        }

        @Override
        public String type() {
            return "fixture";
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> json = RunLog.fields("type", type(), "slot", slot, "value", value);
            if (credential != null) {
                json.put("credential", RunLog.fields("realm", credential.realm(), "key", credential.key()));
            }
            json.put("origin", origin.toJson());
            return json;
        }

        static FixtureEntry fromJson(Map<String, Object> json) {
            CredentialSpec credential = null;
            if (json.get("credential") instanceof Map<?, ?> c) {
                credential = new CredentialSpec(String.valueOf(c.get("realm")), String.valueOf(c.get("key")));
            }
            return new FixtureEntry(text(json, "slot"), json.get("value"), credential,
                    Origin.fromJson(json.get("origin")));
        }
    }

    /**
     * {@code oracle}: an oracle the session evaluated.
     *
     * @param name its name ({@code $oracle.<name>})
     * @param fn the pack's function
     * @param args the arguments as given (references in place)
     * @param inputsHash {@code sha256:} over the substituted arguments
     * @param value the output
     */
    public record OracleEntry(String name, String fn, Object args, String inputsHash, Object value)
            implements Entry {

        /** Freezes. */
        public OracleEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(fn, "fn");
            args = Plan.freeze(args);
            value = Plan.freeze(value);
        }

        @Override
        public String type() {
            return "oracle";
        }

        @Override
        public Map<String, Object> toJson() {
            return RunLog.fields("type", type(), "name", name, "fn", fn, "args", args, "inputsHash", inputsHash,
                    "value", value);
        }

        static OracleEntry fromJson(Map<String, Object> json) {
            return new OracleEntry(text(json, "name"), text(json, "fn"), json.get("args"), text(json, "inputsHash"),
                    json.get("value"));
        }
    }

    /**
     * {@code check}: an expectation evaluated now.
     *
     * @param path the path
     * @param matcher the matcher as given
     * @param actual the value found, masked
     * @param held whether it held
     * @param reason why not, or null
     */
    public record CheckEntry(String path, Object matcher, Object actual, boolean held, String reason)
            implements Entry {

        /** Freezes. */
        public CheckEntry {
            Objects.requireNonNull(path, "path");
            matcher = Plan.freeze(matcher);
            actual = Plan.freeze(actual);
        }

        @Override
        public String type() {
            return "check";
        }

        @Override
        public Map<String, Object> toJson() {
            return RunLog.fields("type", type(), "path", path, "matcher", matcher, "actual", actual, "held", held,
                    "reason", reason);
        }

        static CheckEntry fromJson(Map<String, Object> json) {
            return new CheckEntry(text(json, "path"), json.get("matcher"), json.get("actual"),
                    Boolean.TRUE.equals(json.get("held")), text(json, "reason"));
        }
    }

    /**
     * {@code observe}: the value at a path, for an expectation the export marks as observed.
     *
     * @param path the path
     * @param value the value, masked
     */
    public record ObserveEntry(String path, Object value) implements Entry {

        /** Freezes. */
        public ObserveEntry {
            Objects.requireNonNull(path, "path");
            value = Plan.freeze(value);
        }

        @Override
        public String type() {
            return "observe";
        }

        @Override
        public Map<String, Object> toJson() {
            return RunLog.fields("type", type(), "path", path, "value", value);
        }

        static ObserveEntry fromJson(Map<String, Object> json) {
            return new ObserveEntry(text(json, "path"), json.get("value"));
        }
    }

    /**
     * {@code note}: a remark of a person, a model or the system.
     *
     * @param origin who wrote it
     * @param text the note
     */
    public record NoteEntry(Origin origin, String text) implements Entry {

        /** @throws NullPointerException if the origin is null */
        public NoteEntry {
            Objects.requireNonNull(origin, "origin");
        }

        @Override
        public String type() {
            return "note";
        }

        @Override
        public Map<String, Object> toJson() {
            return RunLog.fields("type", type(), "origin", origin.toJson(), "text", text);
        }

        static NoteEntry fromJson(Map<String, Object> json) {
            return new NoteEntry(Origin.fromJson(json.get("origin")), StepJournal.text(json, "text"));
        }
    }

    /**
     * {@code budget}: the state of a money budget (the session service writes it).
     *
     * @param scope {@code SESSION}, …
     * @param spentUsd what was spent, a plain decimal
     * @param limitUsd the limit, a plain decimal, or null for none
     * @param exhausted whether no further model call is made
     */
    public record BudgetEntry(String scope, String spentUsd, String limitUsd, boolean exhausted) implements Entry {

        @Override
        public String type() {
            return "budget";
        }

        @Override
        public Map<String, Object> toJson() {
            return RunLog.fields("type", type(), "scope", scope, "spentUsd", spentUsd, "limitUsd", limitUsd,
                    "exhausted", exhausted);
        }

        static BudgetEntry fromJson(Map<String, Object> json) {
            return new BudgetEntry(text(json, "scope"), text(json, "spentUsd"), text(json, "limitUsd"),
                    Boolean.TRUE.equals(json.get("exhausted")));
        }
    }

    /**
     * {@code session-end}: why the session ended, how many steps it ran and what its model calls cost.
     *
     * @param reason {@code closed} or {@code agent lost}
     * @param steps the steps journaled
     * @param cost the model cost the session's ledger recorded, a plain decimal in USD
     */
    public record SessionEnd(String reason, int steps, String cost) implements Entry {

        @Override
        public String type() {
            return "session-end";
        }

        @Override
        public Map<String, Object> toJson() {
            return RunLog.fields("type", type(), "reason", reason, "steps", steps, "cost", cost);
        }

        static SessionEnd fromJson(Map<String, Object> json) {
            return new SessionEnd(text(json, "reason"), json.get("steps") instanceof Number n ? n.intValue() : 0,
                    text(json, "cost"));
        }
    }

    /** One record by its type. */
    static Entry parse(Map<String, Object> json) {
        String type = text(json, "type");
        if (type == null) {
            throw new IllegalArgumentException("a record without a type");
        }
        return switch (type) {
            case "session-start" -> SessionStart.fromJson(json);
            case "step" -> StepEntry.fromJson(json);
            case "resolve" -> ResolveEntry.fromJson(json);
            case "fixture" -> FixtureEntry.fromJson(json);
            case "oracle" -> OracleEntry.fromJson(json);
            case "check" -> CheckEntry.fromJson(json);
            case "observe" -> ObserveEntry.fromJson(json);
            case "note" -> NoteEntry.fromJson(json);
            case "budget" -> BudgetEntry.fromJson(json);
            case "session-end" -> SessionEnd.fromJson(json);
            default -> throw new IllegalArgumentException("unknown record type '" + type + "'");
        };
    }

    private static String text(Map<String, Object> json, String key) {
        Object value = json.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> json, String key) {
        return json.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> strings(Map<String, Object> json, String key) {
        List<String> out = new ArrayList<>();
        if (json.get(key) instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
