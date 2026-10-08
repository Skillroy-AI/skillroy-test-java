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

package ai.skillroy.testjava.agent.web.fake;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The neutral fake's profile ({@code browsers()}). */
public final class FakeProfile {

    static {
        ClassInitialisations.record(FakeProfile.class);
    }

    private final String name;
    final List<FakeBrowser> browsers = new CopyOnWriteArrayList<FakeBrowser>();

    FakeProfile(String name) {
        this.name = name;
    }

    public List<FakeBrowser> browsers() {
        return new ArrayList<FakeBrowser>(browsers);
    }

    public String name() {
        return name;
    }
}
