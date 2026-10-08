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

package ai.skillroy.testjava.agent.api.spi.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link WebScriptException} and {@link WebFrameGoneException}: checked exceptions with their typed fields. */
class WebExceptionsTest {

    @Test
    void aScriptErrorCarriesTheMessageAndTheStack() {
        String stack = "ReferenceError: deposit is not defined\n    at <anonymous>:1:1";
        WebScriptException e = new WebScriptException("ReferenceError: deposit is not defined", stack);
        assertEquals("ReferenceError: deposit is not defined", e.getMessage());
        assertEquals(stack, e.stack());
        assertNull(new WebScriptException("rejected", null).stack());
        assertNull(new WebScriptException(null, null).getMessage());
        assertEquals(Exception.class, WebScriptException.class.getSuperclass(), "a checked exception");
    }

    @Test
    void aGoneFrameNamesItsKey() {
        WebFrameGoneException e = new WebFrameGoneException("F-2");
        assertEquals("F-2", e.frameKey());
        assertEquals("frame 'F-2' no longer exists", e.getMessage());
        assertEquals(Exception.class, WebFrameGoneException.class.getSuperclass(), "a checked exception");
    }
}
