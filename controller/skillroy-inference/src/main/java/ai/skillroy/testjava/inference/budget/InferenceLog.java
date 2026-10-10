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

package ai.skillroy.testjava.inference.budget;

import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.CostLedger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where the records of {@code inference.jsonl} go (phase5-contracts.md 7.4): one masked record per gateway call,
 * appended by the {@link LedgerWriter}. A file for a job, a triage or a session that owns an output directory
 * ({@link #toFile}); in memory for a bind, whose contributor does not know the run's output directory: the run's
 * writer takes the records of its ledger with {@link #forLedger} and writes them beside {@code cost-ledger.jsonl}.
 *
 * <p>Implementations are thread-safe; a record is a JSON-like map that is already masked.
 */
public interface InferenceLog {

    /** A log that keeps nothing. */
    InferenceLog NONE = record -> { };

    /** Appends one record. Never throws for an I/O problem (the record is evidence, not the run). */
    void append(Map<String, Object> record);

    /** A log kept in memory. */
    static Memory inMemory() {
        return new Memory();
    }

    /**
     * The in-memory log shared by everything that records into this ledger (a bind's resolvers record into the run's
     * ledger): created on first use, held as long as the ledger is. The run's writer reads it with
     * {@link Memory#records()} or {@link Memory#writeTo}.
     */
    static Memory forLedger(CostLedger ledger) {
        synchronized (Memory.BY_LEDGER) {
            return Memory.BY_LEDGER.computeIfAbsent(ledger, l -> new Memory());
        }
    }

    /**
     * A log that appends each record as one line of JSON to a file (created with its directories on first use). An I/O
     * failure is remembered ({@link FileLog#failed()}), never thrown.
     */
    static FileLog toFile(Path file) {
        return new FileLog(file);
    }

    /** The records in memory, in order. */
    final class Memory implements InferenceLog {

        private static final Map<CostLedger, Memory> BY_LEDGER = new WeakHashMap<>();

        private final List<Map<String, Object>> records = new ArrayList<>();

        Memory() {
        }

        @Override
        public synchronized void append(Map<String, Object> record) {
            records.add(record);
        }

        /** A copy of the records, in order. */
        public synchronized List<Map<String, Object>> records() {
            return List.copyOf(records);
        }

        /**
         * Writes the records as {@code inference.jsonl} lines (replacing the file).
         *
         * @throws IOException when the file cannot be written
         */
        public void writeTo(Path file) throws IOException {
            StringBuilder text = new StringBuilder();
            for (Map<String, Object> record : records()) {
                text.append(Json.write(record)).append('\n');
            }
            // Phase 6 security fix, pass 2, P2-L8-F3: created exclusively, never through what is in its place.
            OutputFiles.writeString(file, text.toString());
        }
    }

    /** Appends to a file. */
    final class FileLog implements InferenceLog {

        private final Path file;
        private volatile boolean failed;

        FileLog(Path file) {
            this.file = java.util.Objects.requireNonNull(file, "file");
        }

        @Override
        public synchronized void append(Map<String, Object> record) {
            try {
                // P2-L8-F3: appended only to a regular file of the current user, never through a link.
                OutputFiles.append(file, (Json.write(record) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                failed = true;
            }
        }

        /** True when a record could not be written. */
        public boolean failed() {
            return failed;
        }

        /** The file. */
        public Path file() {
            return file;
        }
    }
}
