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

package ai.skillroy.testjava.samples.lending;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A plugin that records its lifecycle in a static log, for {@link LendingAppTest}. */
public class RecordingPlugin implements LendingPlugin {

    static final List<String> LOG = Collections.synchronizedList(new ArrayList<String>());

    @Override
    public void start(LendingApp app) {
        LOG.add("start " + app.seed() + " " + (LendingApp.instance() == app) + " " + app.engine().isRunning());
    }

    @Override
    public void stop() {
        LOG.add("stop");
    }

    /** A plugin whose start fails; the desk must keep going. */
    public static class Failing implements LendingPlugin {
        @Override
        public void start(LendingApp app) {
            throw new IllegalStateException("plugin failure for the test");
        }
    }
}
