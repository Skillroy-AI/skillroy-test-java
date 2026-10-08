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

package ai.skillroy.testjava.inference.claude;

import ai.skillroy.testjava.protocol.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A loopback fake of the provider's HTTP API (phase5-contracts.md 15.1 item 2) on the JDK's {@code HttpServer},
 * bound to 127.0.0.1 only: canned answers in order (a default when none is queued), every request recorded with its
 * method, path, headers (lower-case names) and body. No test reaches the network. Closed with {@link #close()}.
 */
final class FakeAnthropic implements AutoCloseable {

    /** One canned answer. */
    record Answer(int status, Map<String, String> headers, String body, long delayMs) {

        static Answer json(String body) {
            return new Answer(200, Map.of(), body, 0);
        }

        static Answer error(int status, String type, String message, Map<String, String> headers) {
            return new Answer(status, headers, "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\""
                    + message + "\"},\"request_id\":\"req_" + status + "\"}", 0);
        }
    }

    /** One request as the fake saw it. */
    record Seen(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        Map<String, Object> json() {
            try {
                return Json.parseObject(body);
            } catch (RuntimeException e) {
                throw new AssertionError("not a JSON body: " + body, e);
            }
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Deque<Answer> answers = new ArrayDeque<>();
    private final List<Seen> seen = new ArrayList<>();
    private Answer fallback = Answer.json(message("end_turn", "[{\"type\":\"text\",\"text\":\"OK\"}]", "{}"));

    FakeAnthropic() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "fake-anthropic");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The base URL to point a client at. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queues answers, served in order. */
    synchronized FakeAnthropic answer(Answer... more) {
        answers.addAll(List.of(more));
        return this;
    }

    /** The answer when none is queued. */
    synchronized FakeAnthropic otherwise(Answer answer) {
        fallback = answer;
        return this;
    }

    synchronized List<Seen> seen() {
        return List.copyOf(seen);
    }

    synchronized Seen last() {
        return seen.get(seen.size() - 1);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
        Answer answer;
        synchronized (this) {
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body));
            answer = answers.isEmpty() ? fallback : answers.removeFirst();
        }
        if (answer.delayMs() > 0) {
            try {
                Thread.sleep(answer.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
        answer.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.getResponseHeaders().add("content-type", ex.getRequestURI().getPath().endsWith("/results")
                ? "application/x-jsonl" : "application/json");
        try {
            ex.sendResponseHeaders(answer.status(), out.length);
            ex.getResponseBody().write(out);
        } catch (IOException ignored) {
            // The client gave up (a timeout test).
        } finally {
            ex.close();
        }
    }

    /** A message response with these parts. */
    static String message(String stop, String contentJson, String usageExtraJson) {
        return message("claude-sonnet-5-5", stop, contentJson, usageExtraJson, null);
    }

    static String message(String model, String stop, String contentJson, String usageExtraJson, String stopDetails) {
        String usage = "{\"input_tokens\":100,\"output_tokens\":20"
                + (usageExtraJson.equals("{}") ? "" : "," + usageExtraJson.substring(1, usageExtraJson.length() - 1))
                + "}";
        return "{\"id\":\"msg_fake_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + model + "\","
                + "\"content\":" + contentJson + ",\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,"
                + "\"stop_details\":" + (stopDetails == null ? "null" : stopDetails) + ",\"usage\":" + usage + "}";
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
