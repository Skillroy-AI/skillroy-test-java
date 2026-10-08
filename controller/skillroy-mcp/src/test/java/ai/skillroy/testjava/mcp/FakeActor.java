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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.StepOutcome;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for the session service: each request is answered with a turn record; with a session given, it acts once
 * through it as a model would ({@code enter}, its {@code confirm} stripped by the session), so that the journal shows
 * the model's origin.
 */
class FakeActor implements SessionActor {

    /** What the session service's turn looks like, roughly: a record with an enum, a list, a duration. */
    record Turn(int index, String request, List<String> actions, String answer, Outcome outcome, Duration duration) {
        enum Outcome { DONE, QUESTION }
    }

    final List<String> requests = new ArrayList<>();
    final ExecutorSession session;
    boolean closed;
    boolean lose;

    FakeActor() {
        this(null);
    }

    FakeActor(ExecutorSession session) {
        this.session = session;
    }

    @Override
    public Object ask(String request) throws AgentLostException, InterruptedException {
        requests.add(request);
        if (lose) {
            throw new AgentLostException("the agent went away during the turn");
        }
        List<String> actions = new ArrayList<>();
        if (session != null) {
            StepOutcome outcome = session.execute("enter", Map.of("value", "HAM-1", "confirm", true),
                    Origin.model("a-model", "lend the hammer", "req-1"));
            actions.add("enter: " + outcome.status());
        }
        return new Turn(requests.size(), request, actions, "done: " + request + " (operator pin " + Desk.SECRET + ")",
                Turn.Outcome.DONE, Duration.ofMillis(1500));
    }

    @Override
    public void close() {
        closed = true;
    }
}
