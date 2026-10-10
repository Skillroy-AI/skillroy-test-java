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

import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.TelemetrySettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Builds the module's observers (phase6-contracts.md 10.4, decisions D140, D141): an {@link SdkTracerProvider} whose
 * {@code Resource} is {@code Resource.getDefault()} merged with {@code service.name} and {@code service.version} and
 * nothing else (no {@code OTEL_RESOURCE_ATTRIBUTES}, no resource detectors: a process resource would carry the
 * command line), {@code AlwaysOn} sampling, the SDK's default span limits set explicitly (64 attributes, 128 events),
 * and the exporter: OTLP/HTTP (protobuf) to {@code <endpoint>/v1/traces} with the settings' headers and timeout and no
 * retry, through the JDK's HTTP client sender (found by the exporter through {@code ServiceLoader}; the OkHttp sender
 * is not on the class path), behind a {@link BatchSpanProcessor}; or the {@code skillroy-spans/1} file behind a
 * {@link SimpleSpanProcessor} (one line per span as it ends). No {@code GlobalOpenTelemetry}, no autoconfiguration, no
 * {@code OTEL_*} variable: every setting is the settings' (the exporter's and the processor's meter provider is the
 * no-op one, so that nothing asks the global instance).
 *
 * <p>As built (Phase 6, TL): the {@link BatchSpanProcessor} names its own daemon worker thread
 * ({@code BatchSpanProcessor_WorkerThread-1}; version 1.66.0 offers no thread factory), and the JDK sender's HTTP client
 * runs on an executor of daemon threads named {@value #THREAD_NAME} that {@code close} shuts down (decision D163 of
 * the build).
 *
 * <p>Stateless; safe from any thread.
 */
final class TelemetryObservers {

    /** The name of the sender's threads. */
    static final String THREAD_NAME = "skillroy-telemetry";
    /** The instrumentation scope's name. */
    static final String SCOPE = "ai.skillroy.testjava.telemetry";

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey("service.version");

    private TelemetryObservers() {
    }

    /**
     * The {@code otlp} observer.
     *
     * @param environment where a header's value is read from (production: {@code System::getenv}); asked once per
     *     header, here, and never kept beyond the exporter's header map
     * @param err where the first export failure is reported
     * @throws SettingsException when a header's variable is not set (naming the variable, never a value)
     */
    static RunObserver otlp(TelemetrySettings settings, UnaryOperator<String> environment, PrintStream err)
            throws SettingsException {
        return otlp(settings, environment, err, null);
    }

    /**
     * {@link #otlp(TelemetrySettings, UnaryOperator, PrintStream)} trusting {@code trustedCertificatesPem} as well:
     * the tests' TLS stub (a self-signed certificate) only, since a header needs an {@code https://} endpoint
     * (P2-L8-F2). Production passes null and the JDK's default trust store holds; nothing exposes this as a setting
     * (§10.3: no trust-store option).
     */
    static RunObserver otlp(TelemetrySettings settings, UnaryOperator<String> environment, PrintStream err,
                            byte[] trustedCertificatesPem) throws SettingsException {
        TelemetrySettings.checkEndpoint(settings.endpoint(), "the telemetry endpoint");
        // P2-L8-F2: the provider checks the header rule again, as it checks the endpoint rule.
        TelemetrySettings.checkHeaders(settings.endpoint(), settings.headerVariables(), "the telemetry endpoint");
        FailureReporting.configureLogging();
        OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder()
                .setEndpoint(settings.tracesUrl())
                .setTimeout(Duration.ofMillis(settings.timeoutMs()))
                .setConnectTimeout(Duration.ofMillis(settings.timeoutMs()))
                .setRetryPolicy(null)
                .setMeterProvider(MeterProvider.noop());
        for (Map.Entry<String, String> header : settings.headerVariables().entrySet()) {
            String value = environment.apply(header.getValue());
            if (value == null) {
                throw new SettingsException("the environment variable " + header.getValue() + " that a telemetry"
                        + " header names is not set");
            }
            builder.addHeader(header.getKey(), value);
        }
        if (trustedCertificatesPem != null) {
            builder.setTrustedCertificates(trustedCertificatesPem);
        }
        ExecutorService sender = Executors.newCachedThreadPool(daemon());
        builder.setExecutorService(sender);
        TelemetryObserver.Dropped dropped = new TelemetryObserver.Dropped();
        String where = settings.endpointHostPort();
        SpanExporter exporter = new FailureReporting.Counting(builder.build(), dropped, where, err);
        SpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setExporterTimeout(Duration.ofMillis(settings.timeoutMs()))
                .setMeterProvider(MeterProvider.noop())
                .build();
        return new TelemetryObserver(provider(settings, processor), settings.minimal(), settings.timeoutMs(),
                TelemetrySettings.EXPORTER_OTLP, where, dropped, () -> {
                    sender.shutdown();
                    try {
                        sender.awaitTermination(Math.min(1_000L, settings.timeoutMs()), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    sender.shutdownNow();
                });
    }

    /**
     * The {@code file} observer: {@code <out>/spans.jsonl}.
     *
     * @throws SettingsException when the directory or the file cannot be created
     */
    static RunObserver file(TelemetrySettings settings, PrintStream err) throws SettingsException {
        if (settings.out() == null) {
            throw new SettingsException("the file exporter needs a directory (" + TelemetrySettings.PROP_OUT + ")");
        }
        FailureReporting.configureLogging();
        SpanExporter writer;
        try {
            writer = new SpansFileExporter(settings.out());
        } catch (IOException e) {
            throw new SettingsException("the telemetry directory " + settings.out() + " cannot be written ("
                    + e.getClass().getSimpleName() + ")");
        }
        TelemetryObserver.Dropped dropped = new TelemetryObserver.Dropped();
        String where = settings.out().toString();
        SpanExporter exporter = new FailureReporting.Counting(writer, dropped, SpansFileExporter.FILE, err);
        return new TelemetryObserver(provider(settings, SimpleSpanProcessor.create(exporter)), settings.minimal(),
                settings.timeoutMs(), TelemetrySettings.EXPORTER_FILE, where, dropped, null);
    }

    /** An observer over any exporter (the tests' in-memory one), synchronous: each span is exported as it ends. */
    static TelemetryObserver withExporter(TelemetrySettings settings, SpanExporter exporter) {
        TelemetryObserver.Dropped dropped = new TelemetryObserver.Dropped();
        return new TelemetryObserver(provider(settings, SimpleSpanProcessor.create(exporter)), settings.minimal(),
                settings.timeoutMs(), settings.exporter(), null, dropped, null);
    }

    private static SdkTracerProvider provider(TelemetrySettings settings, SpanProcessor processor) {
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(SERVICE_NAME,
                settings.serviceName(), SERVICE_VERSION, settings.serviceVersion())));
        return SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn())
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(TelemetryAttributes.MAX_ATTRIBUTES)
                        .setMaxNumberOfEvents(TelemetryAttributes.MAX_EVENTS)
                        .setMaxAttributeValueLength(TelemetryAttributes.MAX_VALUE_LENGTH).build())
                .addSpanProcessor(processor)
                .build();
    }

    private static java.util.concurrent.ThreadFactory daemon() {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME + (count.incrementAndGet() == 1 ? "" : "-"
                    + count.get()));
            thread.setDaemon(true);
            return thread;
        };
    }
}
