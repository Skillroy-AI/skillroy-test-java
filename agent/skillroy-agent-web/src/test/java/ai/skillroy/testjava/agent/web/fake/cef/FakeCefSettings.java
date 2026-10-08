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

package ai.skillroy.testjava.agent.web.fake.cef;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

/** The neutral fake's settings, with JCEF's public field names (snake case, as the technology names them). */
public final class FakeCefSettings {

    static {
        ClassInitialisations.record(FakeCefSettings.class);
    }

    public int remote_debugging_port;
    public String root_cache_path;
    public String cache_path;
    public boolean windowless_rendering_enabled = true;
}
