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

package ai.skillroy.testjava.agent.web.jcef;

import ai.skillroy.testjava.agent.api.spi.web.WebScriptException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security fix S43 in the JCEF adapter: a DevTools text over {@link WebLib#MAX_CHANNEL_CHARS} is refused (an answer)
 * or dropped (an event) before it is parsed, except a screenshot's answer, which the viewport bounds; a script error's
 * message is its first line of at most 2000 characters and its stack at most 8000.
 */
class JcefChannelBoundsTest {

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void aDevToolsTextOverTheChannelBoundIsRefusedUnparsed() {
        String big = repeat('x', WebLib.MAX_CHANNEL_CHARS + 1);
        assertTrue(JcefBrowser.oversized("Runtime.evaluate", big));
        assertTrue(JcefBrowser.oversized("Runtime.consoleAPICalled", big), "an event too");
        assertFalse(JcefBrowser.oversized("Page.captureScreenshot", big), "the viewport bounds a screenshot");
        assertFalse(JcefBrowser.oversized("Runtime.evaluate", repeat('x', 1000)));
        assertFalse(JcefBrowser.oversized("Runtime.evaluate", null));
        RpcException e = JcefBrowser.oversizedAnswer("Runtime.evaluate");
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals("oversized", e.error().data().get("reason"));
    }

    @Test
    void aScriptErrorIsCutToItsFirstLineAndItsStackToEightThousand() {
        Map<String, Object> exception = new LinkedHashMap<String, Object>();
        exception.put("description", "Error: " + repeat('m', 10000) + "\n" + repeat('s', 100000));
        WebScriptException e = JcefBrowser.scriptError(Collections.singletonMap("exception", exception));
        assertEquals(WebLib.MAX_ERROR_CHARS, e.getMessage().length());
        assertEquals(WebLib.MAX_STACK_CHARS, e.stack().length());
    }
}
