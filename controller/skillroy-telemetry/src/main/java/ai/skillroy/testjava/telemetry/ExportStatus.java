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


package ai.skillroy.testjava.telemetry;

/**
 * What the CLI's summary line says about an export (phase6-contracts.md 10.3): {@code telemetry: otlp <host:port>,
 * trace <id>, <n> batches dropped}. Implemented by the module's observer; names no OpenTelemetry type, so that the
 * CLI may refer to it without loading the SDK.
 */
public interface ExportStatus {

    /** {@code otlp} or {@code file}. */
    String exporter();

    /** {@code host:port} of the OTLP endpoint, or the file's directory; null when unknown. */
    String endpoint();

    /** How many batches the exporter could not deliver (the first failure is reported once on stderr). */
    long droppedBatches();

    /**
     * The summary line for an observer, or null when it is not one of this module's.
     *
     * @param traceId the observer's trace id
     */
    static String summaryLine(Object observer, String traceId) {
        if (!(observer instanceof ExportStatus status)) {
            return null;
        }
        long dropped = status.droppedBatches();
        return "telemetry: " + status.exporter() + (status.endpoint() == null ? "" : " " + status.endpoint())
                + ", trace " + (traceId == null ? "(none)" : traceId) + ", " + dropped + " batch"
                + (dropped == 1 ? "" : "es") + " dropped";
    }
}
