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

package ai.skillroy.testjava.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The names both ends of the channel must spell identically (hardening H2 and H3). */
class ProtocolConstantsTest {

    @Test
    void versionAndNames() {
        assertEquals("0.2", Protocol.VERSION);
        assertEquals("skillroy.agent.session", Protocol.PROP_SESSION);
        assertEquals("sessionId", Protocol.KEY_SESSION_ID);
        assertEquals(100, Protocol.MAX_BATCH);
        assertEquals("secretParams", Protocol.META_SECRET_PARAMS);
    }

    /** phase6-contracts.md section 4.4: the hook engine's and the Byteman module's topics. */
    @Test
    void hookAndRuleTopics() {
        assertEquals("hook.", Topics.HOOK_PREFIX);
        assertEquals("hooks.installed", Topics.HOOKS_INSTALLED);
        assertEquals("hooks.removed", Topics.HOOKS_REMOVED);
        assertEquals("hooks.stubbed", Topics.HOOKS_STUBBED);
        assertEquals("rule.fired", Topics.RULE_FIRED);
        assertEquals("rules.changed", Topics.RULES_CHANGED);
        assertTrue(TopicFilter.parse("hook.*").matches(Topics.HOOK_PREFIX + "limit.applied"),
                "the SSE filter hook.* sees every hook");
    }
}
