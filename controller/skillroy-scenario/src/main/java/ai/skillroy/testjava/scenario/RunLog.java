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
import ai.skillroy.testjava.scenario.observe.RunObserver;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes {@code run.jsonl}: one compact JSON object per line, flushed per line so that a crash keeps what happened.
 * Every record starts with {@code "type"} and {@code "t"} (milliseconds since the run started); a record holding a
 * value the JSON codec rejects is written as {@code {"type", "t", "unwritable": reason, "fields": [names]}}. Types:
 * {@code run-start}, {@code pack} (version note), {@code requires}, {@code launchProperties}, {@code preflight},
 * {@code bind}, {@code call}, {@code sync}, {@code fidelity} (a UI call that ran at a lower fidelity than requested,
 * amendment A29), {@code prompt}, {@code expect}, {@code evidence}, {@code artifact}, {@code cleanup},
 * {@code recover}, {@code run-end}, and from Phase 2 {@code fingerprint}, {@code resolver}, {@code verifier},
 * {@code resolve}, {@code oracle}, {@code guard}, {@code capture}, {@code db}, {@code ledger}, and from Phase 4
 * (phase4-contracts.md 8.2) {@code web.path} (once per run with web calls: the path chosen and why, the CDP attach's
 * details, the script library's hash), {@code web.fallback} (a call the chosen path could not serve went to the
 * agent), {@code web.recover} (written by the CDP path through its run context), {@code fact} (a value the
 * {@code web} fact root left out) and {@code web.close} (only when closing the path failed), and from Phase 6
 * (phase6-contracts.md 5.3, 6.4) {@code hooks} (step 0b's install of the pack's hook catalogue: {@code {mode,
 * skipped}}, {@code {mode, alreadyInstalled, catalogue, armed}} or {@code {mode, catalogue, installed, unavailable,
 * armed, touched, ms, otherAgents}}; {@code ok: false} and the error when refused) and {@code capabilities} (the
 * archived matrix: {@code {hash, ids, file}}), and from Phase 8 (phase8-contracts.md 7.1, 7.6) {@code warning} (one
 * per warning a call reported: {@code {step, index, primitive, code, message?, data?, accepted, source}}, a cleanup
 * call's with {@code cleanup: true}), {@code rung} (one per ladder call: {@code {step, index, primitive, rungs,
 * minRung?, rung, fidelity, ok}}) and {@code rungs} (the run-start check of a call with {@code minRung}). A
 * {@code call} record of a {@code web.*} primitive carries
 * {@code path}. Every record passes through the run's {@link SecretMask} first
 * (amendment A25, phase2-contracts.md 11.4), and carries no image's bytes: a capture's {@code base64} (a {@code call},
 * {@code sync}, {@code cleanup} or {@code trace.mark} record's value, an abort's detail) is written as
 * {@code "<png: N bytes>"} by {@link Images} (phase6 security fix, pass 2, P2-L6-F2; the pixels go to
 * {@code artifacts/} and {@code failure/} PNG files only).
 *
 * <p>Telemetry (phase6-contracts.md 10.2; as built, Phase 6, TL): an observer set with {@link #observe} receives, as
 * {@code scenarioRecord}, every record of the types {@link #OBSERVED} names exactly as it is written (masked, in JSON
 * form, without {@code type} and {@code t}), whoever writes it: the seven scenario records the contract names
 * ({@code requires}, {@code launchProperties}, {@code preflight}, {@code hooks}, {@code capabilities},
 * {@code recover}, {@code cleanup}) and the five the step span's events come from ({@code expect}, {@code db},
 * {@code prompt}, {@code fidelity}, {@code capture}). A log without a file still tells its observer.
 *
 * <p>Best effort: a write error disables the log and is never thrown into the run. Used by the executor's thread;
 * {@link #write} is synchronised because a web path may append a record through its run context from a thread of
 * its own.
 */
final class RunLog implements AutoCloseable {

    /** The file name inside the output directory. */
    static final String FILE = "run.jsonl";

    /** The nesting the JSON codec writes at most. */
    private static final int MAX_DEPTH = 200;

    /** The record types an observer receives as {@code scenarioRecord} (phase6-contracts.md 10.2, 10.4). */
    static final Set<String> OBSERVED = Set.of("requires", "launchProperties", "preflight", "hooks", "capabilities",
            "recover", "cleanup", "expect", "db", "prompt", "fidelity", "capture",
            // phase8-contracts.md 7.1, 7.6 (ES): a call's warnings, a ladder call's rung, the run-start rung check.
            "warning", "rung", "rungs");

    private final Clock clock;
    private final long startMs;
    private BufferedWriter out;
    private String error;
    private SecretMask mask = SecretMask.NONE;
    private RunObserver observer = RunObserver.NONE;

    private RunLog(BufferedWriter out, Clock clock) {
        this.out = out;
        this.clock = clock;
        this.startMs = clock.millis();
    }

    /**
     * A log writing to {@code dir/run.jsonl} (replacing an old one), or a no-op log when {@code dir} is null. The file
     * is created exclusively in a directory of the current user, never through what is in its place
     * ({@link OutputFiles}; Phase 6 security fix, pass 2, P2-L8-F3); when it cannot be, the log has no file and
     * {@link #error()} says why.
     */
    static RunLog open(Path dir, Clock clock) {
        if (dir == null) {
            return new RunLog(null, clock);
        }
        try {
            return new RunLog(OutputFiles.newWriter(dir.resolve(FILE)), clock);
        } catch (IOException e) {
            RunLog log = new RunLog(null, clock);
            log.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return log;
        }
    }

    /**
     * Sets the observer that receives the {@link #OBSERVED} records as written; it must not throw (the executor's is
     * wrapped).
     */
    synchronized void observe(RunObserver newObserver) {
        this.observer = newObserver == null ? RunObserver.NONE : newObserver;
    }

    /** Sets the mask every later record passes through. */
    void mask(SecretMask secretMask) {
        this.mask = secretMask == null ? SecretMask.NONE : secretMask;
    }

    /**
     * Appends one record; {@code fields} are written after {@code type} and {@code t} in their own order, with the
     * run's secrets masked. The mask sees the record in the form the JSON codec writes it ({@link #jsonForm}), so
     * that a set, an array or a record a pack built (verifier evidence, call params) is masked like a list or a
     * text. A record the codec cannot write (NaN, nesting deeper than 200) keeps its field names only, since its
     * values could not be masked. An image's bytes are elided before the mask ({@link Images#elided}), in the line
     * and in the observer's copy alike.
     */
    synchronized void write(String type, Map<String, ?> fields) {
        boolean observed = observer != RunObserver.NONE && OBSERVED.contains(type);
        if (out == null && !observed) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", type);
        record.put("t", clock.millis() - startMs);
        String line;
        try {
            if (fields != null) {
                @SuppressWarnings("unchecked")
                Map<String, ?> masked = (Map<String, ?>) mask.apply(Images.elided(jsonForm(fields, 0)));
                record.putAll(masked);
            }
            line = Json.write(record);
        } catch (RuntimeException e) {
            line = Json.write(fields("type", type, "t", record.get("t"), "unwritable", mask.apply(e.toString()),
                    "fields", fields == null ? List.of() : new ArrayList<>(fields.keySet())));
            observed = false;
        }
        if (observed) {
            Map<String, Object> written = new LinkedHashMap<>(record);
            written.remove("type");
            written.remove("t");
            observer.scenarioRecord(type, written);
        }
        if (out == null) {
            return;
        }
        try {
            out.write(line);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            error = e.toString();
            closeQuietly();
        }
    }

    /** The first write error, or null. */
    String error() {
        return error;
    }

    @Override
    public synchronized void close() {
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

    /**
     * The value as the JSON codec writes it, for the mask: maps (keys as text) and lists are copied, strings, numbers,
     * booleans and null kept as they are, and anything else (a set or another collection, an array, a record, any
     * other object) replaced by the codec's own reading of it ({@link Values#normalise}): an array where it writes
     * an array, its text where it writes a text.
     *
     * @throws RuntimeException for a value the codec rejects, or nesting deeper than 200
     */
    static Object jsonForm(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nesting deeper than " + MAX_DEPTH);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), jsonForm(e.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(jsonForm(item, depth + 1));
            }
            return copy;
        }
        return Values.normalise(value);
    }

    /** Builds a field map in insertion order: {@code fields("step", "s1", "ok", true)}. */
    static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /** Json text with a newline, for tests and evidence files. */
    static String pretty(Object value) {
        return Json.writePretty(value);
    }
}
