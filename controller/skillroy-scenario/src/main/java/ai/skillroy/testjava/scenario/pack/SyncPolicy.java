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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.protocol.Topics;

import java.time.Duration;
import java.util.List;

/**
 * The one per-domain contract of the step protocol: which event topics mean "the input was acknowledged", "the
 * application waits for input again" and "the application is asking a question". The pack's agent module must
 * publish them from the application's own seams, so that "ready" is a fact rather than a guess from quiet time.
 *
 * <p>promptTopics: topics that mean "the application is asking a question"; the executor applies
 * {@link PromptPolicy} to them. They are usually also ready topics. Defaults to {@code ["prompt.waiting"]} when null.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param ackTopic topic (glob allowed) published when an input was accepted; null when the domain has none, in which
 *     case {@link Sync#ACK} cannot be used and {@link Sync#READY} waits for a ready topic only
 * @param readyTopics topics (globs allowed) published when the application waits for input again; null means none
 * @param promptTopics topics of application prompts; null means {@code ["prompt.waiting"]}, empty means the executor
 *     never looks for unexpected prompts
 * @param ackTimeout how long to wait for the ack topic; null means the call's own timeout
 * @param readyTimeout how long to wait for a ready topic; null means the call's own timeout
 */
public record SyncPolicy(String ackTopic, List<String> readyTopics, List<String> promptTopics,
                         Duration ackTimeout, Duration readyTimeout) {

    /**
     * @throws IllegalArgumentException if a timeout is zero or negative, or a topic list holds a null or blank entry
     */
    public SyncPolicy {
        if (ackTopic != null && ackTopic.isBlank()) {
            ackTopic = null;
        }
        readyTopics = readyTopics == null ? List.of() : copy(readyTopics, "readyTopics");
        promptTopics = promptTopics == null ? List.of(Topics.PROMPT_WAITING) : copy(promptTopics, "promptTopics");
        requirePositive(ackTimeout, "ackTimeout");
        requirePositive(readyTimeout, "readyTimeout");
    }

    private static List<String> copy(List<String> topics, String what) {
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException(what + " must not hold a null or blank topic");
            }
        }
        return List.copyOf(topics);
    }

    private static void requirePositive(Duration timeout, String what) {
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException(what + " must be positive: " + timeout);
        }
    }
}
