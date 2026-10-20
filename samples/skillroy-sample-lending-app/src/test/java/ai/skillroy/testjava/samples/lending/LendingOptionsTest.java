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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/** The desk's Phase 8 launch property {@code lending.tools} (phase8-contracts.md 12.1, 13.1 row 5). */
class LendingOptionsTest {

    @Test
    void toolsIsParsedAndDefaultsToFalse() {
        assertEquals("lending.tools", LendingOptions.PROP_TOOLS);
        assertFalse(LendingOptions.defaults().tools());
        Properties props = new Properties();
        assertFalse(LendingOptions.fromProperties(props).tools(), "absent: false");
        props.setProperty(LendingOptions.PROP_TOOLS, " TRUE ");
        assertTrue(LendingOptions.fromProperties(props).tools());
        props.setProperty(LendingOptions.PROP_TOOLS, "false");
        assertFalse(LendingOptions.fromProperties(props).tools());
        props.setProperty(LendingOptions.PROP_TOOLS, "");
        assertFalse(LendingOptions.fromProperties(props).tools(), "empty: the default");
        props.setProperty(LendingOptions.PROP_TOOLS, "yes");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LendingOptions.fromProperties(props));
        assertTrue(e.getMessage().contains("lending.tools"), e.getMessage());
        assertTrue(LendingOptions.defaults().tools(true).tools());
    }

    @Test
    void aHeadlessDeskOpensNoToolsWindowEvenWhenAskedTo() throws Exception {
        LendingApp app = LendingApp.launch(LendingOptions.defaults().dbName("options_tools").ui(false).tools(true));
        try {
            assertEquals(null, app.tools(), "the tools window needs the desk's window");
        } finally {
            app.shutdown();
        }
    }
}
