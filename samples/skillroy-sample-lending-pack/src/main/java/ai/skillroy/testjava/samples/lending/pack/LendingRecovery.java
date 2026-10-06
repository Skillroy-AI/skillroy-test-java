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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings the desk back to SIGNED_OFF after a scenario that did not pass (contract section 9.2): answers any open prompt
 * with its first button, cancels any open loan (and any pending sub-step), and signs off. Each action is followed by a
 * wait for the desk's own "waiting again" or "asking" event, never by a pause.
 *
 * <p>Idempotent (a desk that is already signed off and idle is left alone) and bounded: at most {@value #ROUNDS}
 * actions and {@value #BUDGET_MS} ms in total. Throws if the desk cannot be brought back; the executor logs that
 * and never changes the verdict because of it.
 */
final class LendingRecovery {

    static final int ROUNDS = 20;
    static final long BUDGET_MS = 60_000L;
    static final Duration READY_WAIT = Duration.ofSeconds(10);
    static final String READY_TOPICS = String.join(",", LendingPack.READY_TOPICS);
    private static final CallOptions OPTIONS = CallOptions.defaults().withTimeoutMs(10_000L);

    private LendingRecovery() {
    }

    static void recover(AgentConnection agent) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(BUDGET_MS).toNanos();
        String last = "nothing read yet";
        for (int round = 0; round < ROUNDS; round++) {
            if (System.nanoTime() - deadline > 0) {
                break;
            }
            RpcResult promptResult = agent.call("lending.prompt", Map.of(), OPTIONS);
            if (promptResult.value() instanceof Map<?, ?> prompt && prompt.get("buttons") instanceof List<?> buttons
                    && !buttons.isEmpty()) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("choice", String.valueOf(buttons.get(0)));
                if (prompt.get("id") != null) {
                    params.put("id", String.valueOf(prompt.get("id")));
                }
                last = "prompt " + prompt.get("id");
                RpcResult answered;
                try {
                    answered = agent.call("lending.prompt.answer", params, OPTIONS);
                } catch (AgentRpcException e) {
                    continue; // answered meanwhile, or another prompt is open now: look again
                }
                // An agent attached while the prompt was already open has no engine listener yet (the desk
                // lets one be added only while it waits for input) and says so; no event can follow the answer
                // then, and the next look at the desk attaches the listener.
                boolean listening = !(answered.value() instanceof Map<?, ?> result
                        && Boolean.FALSE.equals(result.get("listening")));
                if (listening) {
                    agent.await(READY_TOPICS, answered.seqBefore(), null, READY_WAIT);
                }
                continue;
            }
            RpcResult stateResult = agent.call("lending.state", Map.of(), OPTIONS);
            if (!(stateResult.value() instanceof Map<?, ?> state)) {
                throw new IllegalStateException("lending.state returned " + stateResult.value());
            }
            String name = String.valueOf(state.get("name"));
            boolean awaiting = Boolean.TRUE.equals(state.get("awaitingInput"));
            List<?> valid = state.get("validInputs") instanceof List<?> l ? l : List.of();
            last = "state " + name + " " + valid;
            if (!awaiting) {
                // Busy, or a prompt opened just now: wait for the desk to wait or ask, then look again.
                agent.await(READY_TOPICS, stateResult.seqBefore(), null, READY_WAIT);
                continue;
            }
            if ("SIGNED_OFF".equals(name)) {
                return;
            }
            String key = valid.contains("Key.CANCEL") ? "Key.CANCEL" : valid.contains("Key.SIGN_OFF") ? "Key.SIGN_OFF"
                    : null;
            if (key == null) {
                throw new IllegalStateException("recover: no way out of " + last);
            }
            RpcResult sent;
            try {
                sent = agent.call("lending.input", Map.of("source", key), OPTIONS);
            } catch (AgentRpcException e) {
                if (e.error().code() == ErrorCodes.INPUT_REJECTED) {
                    continue; // the state changed under us: look again
                }
                throw e;
            }
            agent.await(READY_TOPICS, sent.seqBefore(), null, READY_WAIT);
        }
        throw new IllegalStateException("recover: the desk was not signed off after " + ROUNDS
                + " actions or " + BUDGET_MS + " ms (last: " + last + ")");
    }
}
