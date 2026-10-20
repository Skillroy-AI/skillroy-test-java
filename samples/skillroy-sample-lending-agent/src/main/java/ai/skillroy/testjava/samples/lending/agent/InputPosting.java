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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.protocol.CallWarning;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.InputResult;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingInput;

import java.util.Map;

/**
 * Posting one input to the desk and waiting (bounded) for the engine to take it, shared by {@code lending.input}, the
 * composites ({@link LendingComposites}) and the scanner device ({@link ScannerDevice}) (phase8-contracts.md 7.2, 7.4).
 * The input gets an {@code inputId} (the caller's, or one minted here); it is registered in the engine listener's
 * pending table before it is offered, so that its acknowledgement cannot be missed; the call publishes
 * {@code input.acked {source, inputId}} on its own thread when the engine took it in time, and the listener publishes
 * it later otherwise. Never the value in an event.
 *
 * <p>Stateless; safe from any thread (it runs on the module's worker).
 */
final class InputPosting {

    /** Where an input goes: the desk's inbox, or its scanner driver. */
    interface Target {
        boolean offer(LendingInput input);
    }

    /** What one posting did. */
    static final class Posted {
        final LendingInput input;
        final String inputId;
        final boolean acked;
        final long ackMs;
        /** The desk's prompt line when the input was posted. */
        final String promptAtPost;
        /** The desk's prompt line when the engine took it, or null when it has not. */
        final String promptAtTake;

        Posted(LendingInput input, String inputId, boolean acked, long ackMs, String promptAtPost,
               String promptAtTake) {
            this.input = input;
            this.inputId = inputId;
            this.acked = acked;
            this.ackMs = ackMs;
            this.promptAtPost = promptAtPost;
            this.promptAtTake = promptAtTake;
        }

        /** {@code CONSUMED} when the engine took the input within the call, else {@code QUEUED}. */
        String consumed() {
            return acked ? InputResult.STATE_CONSUMED : InputResult.STATE_QUEUED;
        }
    }

    private InputPosting() {
    }

    /** The desk's inbox as a target. */
    static Target inbox(final LendingApp app) {
        return new Target() {
            @Override
            public boolean offer(LendingInput input) {
                return app.inbox().offer(input);
            }
        };
    }

    /**
     * The caller's {@code inputId} argument, or null when it gave none.
     *
     * @throws RpcException INVALID_PARAMS when it does not match {@link InputResult#INPUT_ID_PATTERN}
     */
    static String requestedId(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String) || !InputResult.INPUT_ID_PATTERN.matcher((String) value).matches()) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "inputId must match "
                    + InputResult.INPUT_ID_PATTERN.pattern(), EngineEvents.map("param", "inputId", "reason",
                    "malformed"));
        }
        return (String) value;
    }

    /**
     * Posts {@code input} and waits up to {@code waitMs} for the engine to take it.
     *
     * @param inputId the caller's id, or null to mint one
     * @param correlationId the posting call's correlation id (for a late acknowledgement), or null
     * @throws RpcException INPUT_REJECTED {@code reason: inboxFull} when the target refuses the input
     */
    static Posted post(AgentContext ctx, EngineEvents events, LendingApp app, LendingInput input, String inputId,
                       String correlationId, Target target, long waitMs) throws InterruptedException {
        String id = inputId != null ? inputId : EngineEvents.mintInputId();
        String promptAtPost = app.engine().status().prompt();
        EngineEvents.AckWait wait = events.expectAck(input, id, promptAtPost, correlationId);
        long t0 = System.nanoTime();
        if (!target.offer(input)) {
            events.forget(input);
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "the desk's inbox is full", EngineEvents.map("source",
                    input.source(), "inboxSize", Long.valueOf(app.inbox().size()), InputResult.REASON,
                    InputResult.INBOX_FULL));
        }
        boolean acked;
        try {
            wait.await(Math.max(0L, waitMs));
        } finally {
            acked = events.callReturned(input, wait);
        }
        long ackMs = (System.nanoTime() - t0) / 1000000L;
        if (acked) {
            ctx.events().publish(Topics.INPUT_ACKED, EngineEvents.SOURCE, EngineEvents.map("source", input.source(),
                    InputResult.INPUT_ID, id));
        }
        return new Posted(input, id, acked, ackMs, promptAtPost, wait.takenPromptLine());
    }

    /**
     * The caveats of a consumed input (phase8-contracts.md 7.2), through {@code inv.warn} and into {@code result}:
     * {@code VALUE_DROPPED} when the trimmed value of an {@code Entry} or {@code Barcode} input is empty;
     * {@code VALUE_COERCED} with {@code taken} (the trimmed value) when the desk trims the value, but never the value
     * of a secret parameter; {@code ANSWERED_NESTED} with {@code answeredBy} when the desk took the input under
     * another prompt line than the one it showed when the input was posted. Nothing for an input not yet consumed.
     */
    static void caveats(Invocation inv, Posted posted, String valueParam, Map<String, Object> result) {
        if (!posted.acked) {
            return;
        }
        String value = posted.input.value();
        boolean secret = inv.secretParams().contains(valueParam);
        if (value != null) {
            String trimmed = value.trim();
            String source = posted.input.source();
            if (trimmed.isEmpty() && (LendingInput.ENTRY.equals(source) || LendingInput.BARCODE.equals(source))) {
                inv.warn(CallWarning.VALUE_DROPPED, "the desk ignored an empty " + source + " input", null);
            } else if (!trimmed.equals(value)) {
                if (secret) {
                    inv.warn(CallWarning.VALUE_COERCED, "the desk took the value trimmed", null);
                } else {
                    result.put(InputResult.TAKEN, trimmed);
                    inv.warn(CallWarning.VALUE_COERCED, "the desk took " + trimmed, EngineEvents.map(
                            InputResult.TAKEN, trimmed));
                }
            }
        }
        String before = posted.promptAtPost;
        String taken = posted.promptAtTake;
        if (before != null && taken != null && !before.equals(taken)) {
            Map<String, Object> answeredBy = EngineEvents.map("kind", "question", "text", taken);
            result.put(InputResult.ANSWERED_BY, answeredBy);
            inv.warn(CallWarning.ANSWERED_NESTED, "the desk took the input at '" + taken + "', not at '" + before
                    + "'", EngineEvents.map(InputResult.ANSWERED_BY, answeredBy));
        }
    }
}
