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

import java.util.List;

/**
 * The agent's own log. Exists because the agent must not write to {@code System.out} or reconfigure the host's
 * logging; recent lines are kept in memory and served by {@code agent.log}.
 *
 * <p>Implementations are thread-safe and never block the caller for long.
 */
public interface AgentLog {

    /** Logs a routine message. */
    void info(String msg);

    /** Logs a problem the agent works around. */
    void warn(String msg);

    /** Logs a problem the agent works around, with its cause. */
    void warn(String msg, Throwable t);

    /** Logs a failure. */
    void error(String msg, Throwable t);

    /** The most recent {@code n} lines, oldest first. */
    List<String> tail(int n);
}
