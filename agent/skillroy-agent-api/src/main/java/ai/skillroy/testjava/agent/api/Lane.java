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

package ai.skillroy.testjava.agent.api;

import java.util.regex.Pattern;

/**
 * A named, bounded group of agent-owned daemon threads with a bounded FIFO, declared by a module through
 * {@link ThreadBroker#declareLane}, visible in {@code agent.info.threads.lanes} and retired with the module
 * (phase8-contracts.md 4.2, D525). The agent owns the threads, so their bound, queue and report are the agent's: a
 * module that needs reads kept apart from its other work (a slow source, for example) declares a lane instead of
 * hopping to {@link ThreadPolicy#APP}. Work runs on it through {@link ThreadBroker#callOnLane} or a primitive whose
 * spec names it ({@link ThreadPolicy#LANE}).
 *
 * <p>The core implements this interface; a module only holds the handle it was given. Thread-safe.
 */
public interface Lane extends AutoCloseable {

    /** The most threads one lane may have. */
    int MAX_THREADS = 4;

    /** The longest queue one lane may have; 0 means no queue (a task that finds every thread busy is refused). */
    int MAX_QUEUE = 16;

    /** The most lanes one agent may hold, counted over every module. */
    int MAX_LANES = 8;

    /** The longest lane name. */
    int MAX_NAME_LENGTH = 64;

    /** The lane name grammar: {@code <module id>.<word>} by convention ({@code lending.reads}). */
    String NAME_PATTERN = "^[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+$";

    /** The lane's name. */
    String name();

    /** The lane's thread bound, 1 to {@value #MAX_THREADS}. */
    int threads();

    /** The lane's queue bound, 0 to {@value #MAX_QUEUE}. */
    int queue();

    /**
     * Retires the lane: queued tasks fail {@code CAPABILITY_UNAVAILABLE} ("lane closed"), running ones are interrupted
     * (best effort) and finish on their own, its threads end; the name may then be declared again. Idempotent.
     */
    @Override
    void close();

    /**
     * True if {@code name} follows {@link #NAME_PATTERN} and is at most {@value #MAX_NAME_LENGTH} characters. Compiles
     * the grammar on each call: meant for declarations and spec building, not for a hot path.
     */
    static boolean isValidName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && Pattern.matches(NAME_PATTERN, name);
    }
}
