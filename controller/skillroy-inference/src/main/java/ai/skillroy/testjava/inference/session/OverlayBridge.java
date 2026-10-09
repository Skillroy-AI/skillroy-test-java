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

package ai.skillroy.testjava.inference.session;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The overlay bridge (phase5-contracts.md 12.4, host 3; B15): an in-application overlay asks the session's model
 * through the target's own event stream and gets the answer back through a primitive, so that the overlay's agent
 * module never sees a model SDK. The bridge subscribes to {@value #TOPIC} through an {@link EventSubscription} (the CLI
 * binds it to {@code AgentClient.events}), takes {@code {requestId, text}} from each event, runs
 * {@link SessionService#ask} with the text (the service puts it in a {@code <person>} block, masked), and answers
 * through the primitive the pack names in {@code DomainPack.assistAnswerPrimitive()} with {@code {requestId, answer}}.
 * The answer is a call of the overlay's own primitive, not a step of the session: it is not journaled as a step, so an
 * export never replays it (the turn's actions are journaled by the service as usual).
 *
 * <p>Bounded and without a thread of its own: the subscription's consumer only queues a request (at most
 * {@value #MAX_PENDING}; a request beyond is dropped and counted, an event without a {@code requestId} likewise), and
 * the host serves one request at a time on its own thread with {@link #serveNext}, which waits for the next request at
 * most the time it is given. {@link #close()} closes the subscription and drops what is queued.
 *
 * <p>Thread-safe: the consumer may run on the subscription's thread while the host serves on its own.
 */
public final class OverlayBridge implements AutoCloseable {

    /** The topic an overlay publishes a request on. */
    public static final String TOPIC = "assist.requested";
    /** The most requests queued at a time. */
    public static final int MAX_PENDING = 16;
    /**
     * The most characters of a request's text the model is given: the text is masked whole first and then cut here
     * (phase6 security fix S53: never cut before the mask, so that no part of a secret straddling the cut survives).
     */
    public static final int MAX_TEXT = 10_000;
    /**
     * The longest request text the bridge takes: a longer one is not cut (a cut before the mask could leave part of a
     * secret) but answered that it is too long, without a model call (phase6 security fix S53).
     */
    public static final int MAX_QUEUED_TEXT = PromptMask.MAX_TEXT;
    /** How long the answer primitive may take. */
    public static final long ANSWER_TIMEOUT_MS = 10_000L;

    private static final int MAX_REQUEST_ID = 200;

    /**
     * The event seam (12.4): subscribes to the target's events after {@code sinceSeq} on {@code topics}; the returned
     * handle ends the subscription. The consumer must not block (the bridge's only queues).
     */
    @FunctionalInterface
    public interface EventSubscription {

        /**
         * @param sinceSeq deliver events after this sequence number (negative: from now)
         * @param topics comma-separated topic globs
         * @param consumer receives each event
         * @return what ends the subscription
         * @throws Exception when the subscription cannot be made
         */
        AutoCloseable subscribe(long sinceSeq, String topics, Consumer<Event> consumer) throws Exception;
    }

    /**
     * One overlay request.
     *
     * @param requestId the overlay's id of the request, echoed in the answer
     * @param text what the person asked
     */
    public record Request(String requestId, String text) {

        /** @throws NullPointerException if the id is null */
        public Request {
            Objects.requireNonNull(requestId, "requestId");
            text = text == null ? "" : text;
        }
    }

    /**
     * What serving one request did.
     *
     * @param request the request
     * @param turn the session service's turn
     * @param answered true when the answer primitive accepted the answer
     * @param error why the answer could not be delivered, or null
     */
    public record Handled(Request request, Turn turn, boolean answered, String error) {
    }

    private final SessionService service;
    private final AgentConnection agent;
    private final String answerPrimitive;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(MAX_PENDING);

    /** A queued request, or one whose text was longer than {@link #MAX_QUEUED_TEXT} (its text not kept). */
    private record Pending(Request request, boolean oversized) {
    }
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile AutoCloseable subscription;
    private volatile boolean closed;

    private OverlayBridge(SessionService service, AgentConnection agent, String answerPrimitive) {
        this.service = service;
        this.agent = agent;
        this.answerPrimitive = answerPrimitive;
    }

    /**
     * Opens a bridge: subscribes to {@value #TOPIC} after {@code sinceSeq}.
     *
     * @param service the session service that answers
     * @param agent the target's agent, through which the answer primitive is called
     * @param pack the running pack, which names the answer primitive
     * @param events the event seam
     * @param sinceSeq the sequence number after which requests are taken (negative: from now)
     * @throws IllegalArgumentException when the pack names no answer primitive (it has no overlay)
     * @throws IllegalStateException when the subscription cannot be made (the cause says why)
     */
    public static OverlayBridge open(SessionService service, AgentConnection agent, DomainPack pack,
                                     EventSubscription events, long sinceSeq) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(events, "events");
        String primitive = pack.assistAnswerPrimitive();
        if (primitive == null || primitive.isBlank()) {
            throw new IllegalArgumentException("pack " + pack.id() + " names no assist answer primitive: it has no"
                    + " overlay to bridge");
        }
        OverlayBridge bridge = new OverlayBridge(service, agent, primitive);
        try {
            bridge.subscription = events.subscribe(sinceSeq, TOPIC, bridge::offer);
        } catch (Exception e) {
            throw new IllegalStateException("the overlay's events cannot be subscribed to: " + e.getMessage(), e);
        }
        return bridge;
    }

    /** The subscription's consumer: queues a well-formed request; drops and counts anything else. */
    private void offer(Event event) {
        if (closed || event == null || !TOPIC.equals(event.topic())) {
            return;
        }
        Map<String, Object> payload = event.payload();
        Object id = payload == null ? null : payload.get("requestId");
        Object text = payload == null ? null : payload.get("text");
        if (!(id instanceof String requestId) || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID) {
            dropped.incrementAndGet();
            return;
        }
        String t = text instanceof String s ? s : "";
        boolean oversized = t.length() > MAX_QUEUED_TEXT;
        if (!queue.offer(new Pending(new Request(requestId, oversized ? "" : t), oversized))) {
            dropped.incrementAndGet();
        }
    }

    /**
     * Serves the next request: waits for one at most {@code wait}, asks the session service, and answers through the
     * pack's primitive.
     *
     * @param wait the longest wait for a request; zero takes one only if one is queued
     * @return what was done, or null when no request came (or the bridge is closed)
     * @throws AgentLostException if the agent is lost while answering
     * @throws InterruptedException if the thread is interrupted while waiting or asking
     */
    public Handled serveNext(Duration wait) throws AgentLostException, InterruptedException {
        if (closed) {
            return null;
        }
        long millis = wait == null ? 0 : Math.max(0, wait.toMillis());
        Pending pending = millis == 0 ? queue.poll() : queue.poll(millis, TimeUnit.MILLISECONDS);
        if (pending == null || closed) {
            return null;
        }
        Request request = pending.request();
        Turn turn;
        if (pending.oversized() || request.text().isBlank()) {
            turn = null;
        } else {
            // S53: masked whole, then cut at MAX_TEXT, by the service.
            turn = service.ask(request.text(), MAX_TEXT);
        }
        String answer = pending.oversized() ? "The request is longer than " + MAX_QUEUED_TEXT + " characters; shorten"
                + " it." : turn == null ? "The request had no text." : turn.answer();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", request.requestId());
        params.put("answer", answer == null ? "" : answer);
        try {
            agent.call(answerPrimitive, params, CallOptions.defaults().withTimeoutMs(ANSWER_TIMEOUT_MS));
            return new Handled(request, turn, true, null);
        } catch (AgentRpcException e) {
            return new Handled(request, turn, false, answerPrimitive + " refused the answer: "
                    + e.error().message());
        }
    }

    /** How many requests are queued. */
    public int pending() {
        return queue.size();
    }

    /** How many events were dropped: malformed, or beyond {@value #MAX_PENDING} queued requests. */
    public int dropped() {
        return dropped.get();
    }

    /** The primitive answers go through. */
    public String answerPrimitive() {
        return answerPrimitive;
    }

    /** Closes the subscription and drops what is queued; idempotent; never throws. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        AutoCloseable s = subscription;
        subscription = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                // Best effort: the subscription's own close ends its thread.
            }
        }
        queue.clear();
    }
}
