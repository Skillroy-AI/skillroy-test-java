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

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Writes the results of one run of N scenarios as one surefire-style JUnit XML file, so that any CI server shows them:
 * a {@code <testsuite>} with one {@code <testcase>} per scenario plus an {@code environment} test case that carries
 * attach, handshake and capability failures of the whole run.
 *
 * <p>FAIL is a {@code <failure>}, ERROR an {@code <error>} (both with {@code type} = the failure kind, ORACLE_SUSPECT
 * included), and DEGRADED, by {@link RunOptions.DegradedAs}: a {@code <skipped>} whose message lists the skipped
 * expectations, the skipped oracle cross-checks and the degraded-fidelity entries ({@link RunResult#degradedSummary()};
 * default), a {@code <failure type="DEGRADED">}, or a pass. Each test case's
 * {@code <system-out>} holds the step log. Text and attributes are XML-escaped; characters XML 1.0 cannot hold are
 * replaced by {@code ?}.
 *
 * <p>A caller that must remove a value from the report (the CLI replaces its connection's token) passes a scrub
 * function to {@link #render(String, List, String, UnaryOperator)}: it is applied to each value before that value is
 * escaped (security finding S27), so that a replacement such as {@code <redacted>} is escaped like any other text and
 * the markup itself is never touched.
 *
 * <p>Telemetry (phase6-contracts.md 10.3): a reporter made {@link #withTraceId with a trace id} ends the suite with
 * one {@code <system-out>traceId=<hex></system-out>} (a trace id is not a secret), so that a CI page leads to the
 * run's spans.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class JunitXmlReporter {

    /** The name of the extra test case for run-wide environment failures. */
    public static final String ENVIRONMENT_TESTCASE = "environment";

    private final RunOptions.DegradedAs degradedAs;
    /** The run's trace id (32 lower-case hex), or null. */
    private final String traceId;

    /** A reporter that shows DEGRADED as skipped. */
    public JunitXmlReporter() {
        this(RunOptions.DegradedAs.SKIPPED);
    }

    /**
     * @param degradedAs how to show DEGRADED runs; null means SKIPPED
     */
    public JunitXmlReporter(RunOptions.DegradedAs degradedAs) {
        this(degradedAs, null);
    }

    private JunitXmlReporter(RunOptions.DegradedAs degradedAs, String traceId) {
        this.degradedAs = degradedAs == null ? RunOptions.DegradedAs.SKIPPED : degradedAs;
        this.traceId = traceId != null && traceId.matches("[0-9a-f]{32}") ? traceId : null;
    }

    /**
     * A copy whose report ends with the suite's {@code <system-out>traceId=<hex></system-out>} (phase6-contracts.md
     * 10.3); null, or a text that is not 32 lower-case hex digits, writes none.
     */
    public JunitXmlReporter withTraceId(String id) {
        return new JunitXmlReporter(degradedAs, id);
    }

    /**
     * Writes the XML file (UTF-8), creating parent directories.
     *
     * @param suiteName the test suite name, also used as every test case's class name
     * @param environmentFailure why the run as a whole could not be set up (attach, handshake, capabilities), or
     *     null when it could
     */
    public void write(Path file, String suiteName, List<RunResult> results, String environmentFailure)
            throws IOException {
        // Phase 6 security fix, pass 2, P2-L8-F3: created exclusively, never through what is in its place.
        OutputFiles.writeString(file, render(suiteName, results, environmentFailure));
    }

    /** The XML text; see {@link #write}. */
    public String render(String suiteName, List<RunResult> results, String environmentFailure) {
        return render(suiteName, results, environmentFailure, UnaryOperator.identity());
    }

    /**
     * The XML text, with {@code scrub} applied to every value (suite name, scenario ids, messages, failure bodies,
     * step logs, the environment failure) before it is escaped.
     *
     * @param scrub maps a value to the text to write; null means identity. Must not return null for a non-null value
     */
    public String render(String suiteName, List<RunResult> results, String environmentFailure,
                        UnaryOperator<String> scrub) {
        return new Rendering(scrub == null ? UnaryOperator.identity() : scrub).render(suiteName, results,
                environmentFailure);
    }

    /** One rendering with its scrub function. */
    private final class Rendering {

        private final UnaryOperator<String> scrub;

        Rendering(UnaryOperator<String> scrub) {
            this.scrub = scrub;
        }

        /** An attribute value: scrubbed, then escaped. */
        private String a(String value) {
            return attr(value == null ? null : scrub.apply(value));
        }

        /** Element text: scrubbed, then escaped. */
        private String t(String value) {
            return text(value == null ? null : scrub.apply(value));
        }

        String render(String suiteName, List<RunResult> results, String environmentFailure) {
            int failures = 0;
            int errors = environmentFailure == null ? 0 : 1;
            int skippedCount = 0;
            double total = 0;
            for (RunResult result : results) {
                total += seconds(result);
                switch (outcome(result)) {
                    case "failure" -> failures++;
                    case "error" -> errors++;
                    case "skipped" -> skippedCount++;
                    default -> {
                    }
                }
            }
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<testsuite name=\"").append(a(suiteName)).append("\" tests=\"").append(results.size() + 1)
                    .append("\" failures=\"").append(failures).append("\" errors=\"").append(errors)
                    .append("\" skipped=\"").append(skippedCount).append("\" time=\"").append(time(total))
                    .append("\">\n");
            xml.append("  <testcase name=\"").append(ENVIRONMENT_TESTCASE).append("\" classname=\"")
                    .append(a(suiteName)).append("\" time=\"0.000\"");
            if (environmentFailure == null) {
                xml.append("/>\n");
            } else {
                xml.append(">\n    <error message=\"").append(a(firstLine(environmentFailure)))
                        .append("\" type=\"ENVIRONMENT\">").append(t(environmentFailure)).append("</error>\n")
                        .append("  </testcase>\n");
            }
            for (RunResult result : results) {
                testcase(xml, suiteName, result);
            }
            if (traceId != null) {
                xml.append("  <system-out>traceId=").append(traceId).append("</system-out>\n");
            }
            xml.append("</testsuite>\n");
            return xml.toString();
        }

        private void testcase(StringBuilder xml, String suiteName, RunResult result) {
            xml.append("  <testcase name=\"").append(a(result.scenarioId())).append("\" classname=\"")
                    .append(a(suiteName)).append("\" time=\"").append(time(seconds(result))).append("\">\n");
            String kind = result.failureKind() == null ? "" : result.failureKind().name();
            String where = result.failedStepId() == null ? "" : " at " + result.failedStepId();
            switch (outcome(result)) {
                case "failure" -> {
                    boolean degraded = result.status() == Status.DEGRADED;
                    String message = degraded ? degradedMessage(result) : kind + where + ": " + message(result);
                    xml.append("    <failure message=\"").append(a(firstLine(message))).append("\" type=\"")
                            .append(degraded ? "DEGRADED" : kind).append("\">").append(t(body(message, result)))
                            .append("</failure>\n");
                }
                case "error" -> {
                    String message = kind + where + ": " + message(result);
                    xml.append("    <error message=\"").append(a(firstLine(message))).append("\" type=\"")
                            .append(kind).append("\">").append(t(body(message, result))).append("</error>\n");
                }
                case "skipped" -> xml.append("    <skipped message=\"").append(a(degradedMessage(result)))
                        .append("\"/>\n");
                default -> {
                }
            }
            xml.append("    <system-out>").append(t(stepLog(result))).append("</system-out>\n");
            xml.append("  </testcase>\n");
        }
    }

    /** "pass", "failure", "error" or "skipped" for a result under this reporter's DEGRADED mapping. */
    private String outcome(RunResult result) {
        return switch (result.status()) {
            case PASS -> "pass";
            case FAIL -> "failure";
            case ERROR -> "error";
            case DEGRADED -> switch (degradedAs) {
                case SKIPPED -> "skipped";
                case FAILURE -> "failure";
                case PASS -> "pass";
            };
        };
    }

    private static String message(RunResult result) {
        return result.message() == null ? "(no message)" : result.message();
    }

    private static String degradedMessage(RunResult result) {
        return "DEGRADED: " + result.degradedSummary();
    }

    private static String body(String message, RunResult result) {
        StringBuilder body = new StringBuilder(message).append('\n');
        Map<String, Object> detail = result.detail();
        Object snapshot = detail.get("snapshot");
        Map<String, Object> rest = new LinkedHashMap<>(detail);
        rest.remove("snapshot");
        if (!rest.isEmpty()) {
            body.append("\ndetail:\n").append(Values.safePretty(rest));
        }
        if (snapshot instanceof String text && !text.isEmpty()) {
            body.append("\nUI at failure:\n").append(text);
        }
        return body.toString();
    }

    private static String stepLog(RunResult result) {
        StringBuilder log = new StringBuilder();
        log.append(result.scenarioId()).append(' ').append(result.status());
        if (result.failureKind() != null) {
            log.append(' ').append(result.failureKind());
        }
        log.append(" in ").append(time(seconds(result))).append(" s\n");
        for (StepResult step : result.steps()) {
            log.append(step.id()).append(' ').append(step.verb());
            if (step.line() > 0) {
                log.append(" (line ").append(step.line()).append(')');
            }
            log.append(' ').append(step.status()).append(' ')
                    .append(time(step.duration().toMillis() / 1000.0)).append(" s");
            if (step.message() != null) {
                log.append(": ").append(step.message());
            }
            log.append('\n');
            for (String skipped : step.skippedExpectations()) {
                log.append("  skipped ").append(skipped).append('\n');
            }
            for (String fidelity : step.degradedFidelity()) {
                log.append("  degraded ").append(fidelity).append('\n');
            }
        }
        if (result.message() != null) {
            log.append(result.message()).append('\n');
        }
        return log.toString();
    }

    private static double seconds(RunResult result) {
        return result.duration() == null ? 0 : result.duration().toMillis() / 1000.0;
    }

    private static String time(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** Escapes element text. */
    static String text(String value) {
        return escape(value, false);
    }

    /** Escapes an attribute value (also quotes and line breaks). */
    static String attr(String value) {
        return escape(value, true);
    }

    private static String escape(String value, boolean attribute) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                case '\'' -> out.append(attribute ? "&apos;" : "'");
                case '\n' -> out.append(attribute ? "&#10;" : "\n");
                case '\r' -> out.append(attribute ? "&#13;" : "\r");
                case '\t' -> out.append(attribute ? "&#9;" : "\t");
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        out.append(c).append(value.charAt(++i));
                    } else if (c < 0x20 || Character.isSurrogate(c) || c == 0xFFFE || c == 0xFFFF) {
                        out.append('?');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
