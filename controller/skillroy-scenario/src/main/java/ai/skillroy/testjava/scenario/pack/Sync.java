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

/**
 * How the executor waits after a planned call returns, so that the next call or expectation sees the call's
 * consequences without sleeping. Every wait starts from the call's {@code seq.before}, so it can never miss an event
 * the call caused.
 *
 * <p>A pack verb chooses the level of each call it emits; the topics of {@link #ACK} and {@link #READY} come from the
 * pack's {@link SyncPolicy}, those of {@link #EVENT} from the call's own {@link AwaitSpec}.
 */
public enum Sync {

    /** Return as soon as the call returns. */
    NONE,

    /** Then await the pack's ack topic. A plan may use it only when the pack has an ack topic. */
    ACK,

    /** Then await the ack topic (when the pack has one), then the first of the pack's ready topics. */
    READY,

    /** Then call {@code swing.settle}: the UI has processed the input and no window opened or closed. */
    SETTLE,

    /**
     * Then await the first event matching the call's {@link AwaitSpec} (topics, payload match, timeout) published
     * since the call's {@code seq.before}; none within the timeout is {@code SYNC_TIMEOUT}. The pack's ack topic is
     * not awaited. Use it when the application's acknowledgement means only "consumed", not "accepted", or when the
     * primitive publishes no acknowledgement at all: such a verb uses {@code EVENT} with the pack's ready topics
     * instead of {@link #READY}.
     *
     * <p>A call with this level must carry an {@link AwaitSpec}; a call with any other level must not. Awaiting a
     * prompt topic this way does not make the call a prompt handler: mark it with
     * {@link CompileContext#markHandlesPrompt} if it answers the prompt.
     */
    EVENT
}
