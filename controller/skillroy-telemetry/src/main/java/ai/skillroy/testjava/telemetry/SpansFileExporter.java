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

import ai.skillroy.testjava.protocol.Json;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code file} exporter's writer (phase6-contracts.md 10.5, decision D143): {@code <out>/spans.jsonl} in the
 * project's own format {@value #FORMAT}, one JSON object per finished span, flushed per span:
 * {@code {format, traceId, spanId, parentSpanId, name, kind, startEpochNanos, endEpochNanos, status: {code,
 * description}, attributes: {…}, events: [{name, epochNanos, attributes}], resource: {…}}}. Not OTLP JSON (whose file
 * format is still "Development"); the test oracle and a CI artifact without a collector. The values are the allow-list's
 * (masked before they became attributes). A write error ends the file and fails the batches that follow, counted like
 * an OTLP failure.
 *
 * <p>Thread-safe: writes are synchronised.
 */
final class SpansFileExporter implements SpanExporter {

    /** The format's name, the first field of every line. */
    static final String FORMAT = "skillroy-spans/1";
    /** The file's name inside the exporter's directory. */
    static final String FILE = "spans.jsonl";

    private BufferedWriter out;

    /**
     * Opens {@code <dir>/spans.jsonl}, appending (one invocation's spans after another's in the same directory).
     *
     * @throws IOException if the directory or the file cannot be created
     */
    SpansFileExporter(Path dir) throws IOException {
        Files.createDirectories(dir);
        this.out = Files.newBufferedWriter(dir.resolve(FILE), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Override
    public synchronized CompletableResultCode export(Collection<SpanData> spans) {
        if (out == null) {
            return CompletableResultCode.ofFailure();
        }
        try {
            for (SpanData span : spans) {
                out.write(Json.write(toJson(span)));
                out.write('\n');
            }
            out.flush();
            return CompletableResultCode.ofSuccess();
        } catch (IOException | RuntimeException e) {
            closeFile();
            return CompletableResultCode.ofFailure();
        }
    }

    /** One span in {@value #FORMAT}. */
    static Map<String, Object> toJson(SpanData span) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", FORMAT);
        json.put("traceId", span.getTraceId());
        json.put("spanId", span.getSpanId());
        json.put("parentSpanId", span.getParentSpanContext().isValid() ? span.getParentSpanId() : null);
        json.put("name", span.getName());
        json.put("kind", span.getKind().name());
        json.put("startEpochNanos", span.getStartEpochNanos());
        json.put("endEpochNanos", span.getEndEpochNanos());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("code", span.getStatus().getStatusCode().name());
        status.put("description", span.getStatus().getDescription());
        json.put("status", status);
        json.put("attributes", attributes(span.getAttributes()));
        List<Object> events = new ArrayList<>();
        for (EventData event : span.getEvents()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", event.getName());
            e.put("epochNanos", event.getEpochNanos());
            e.put("attributes", attributes(event.getAttributes()));
            events.add(e);
        }
        json.put("events", events);
        json.put("resource", attributes(span.getResource().getAttributes()));
        return json;
    }

    private static Map<String, Object> attributes(Attributes attributes) {
        Map<String, Object> json = new LinkedHashMap<>();
        attributes.forEach((AttributeKey<?> key, Object value) -> json.put(key.getKey(), value(value)));
        return json;
    }

    private static Object value(Object value) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(value(item));
            }
            return out;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Double || value instanceof Float) {
            return String.valueOf(value);
        }
        return value;
    }

    @Override
    public CompletableResultCode flush() {
        synchronized (this) {
            try {
                if (out != null) {
                    out.flush();
                }
            } catch (IOException e) {
                return CompletableResultCode.ofFailure();
            }
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public synchronized CompletableResultCode shutdown() {
        closeFile();
        return CompletableResultCode.ofSuccess();
    }

    private synchronized void closeFile() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                // nothing more can be written
            }
            out = null;
        }
    }
}
