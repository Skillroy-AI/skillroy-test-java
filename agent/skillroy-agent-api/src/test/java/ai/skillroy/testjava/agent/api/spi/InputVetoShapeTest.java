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

package ai.skillroy.testjava.agent.api.spi;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the Phase 8 SPI {@link InputVeto} (phase8-contracts.md 3.2, 13.1 row 1): the eleven route constants and
 * their values (equal to the Swing rung capability ids where one exists), {@code refuse} the only abstract method and
 * no default method, an anonymous implementation that compiles at {@code --release 8} (this module's tests compile at
 * that level), and the package documentation naming the SPI.
 */
class InputVetoShapeTest {

    @Test
    void theElevenRouteConstantsHaveTheirValues() throws Exception {
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("CLICK_ACTION", "swing.click.action");
        expected.put("CLICK_COMPONENT", "swing.click.component");
        expected.put("CLICK_DISPATCH", "swing.click.dispatch");
        expected.put("TYPE_SET", "swing.type.set");
        expected.put("TYPE_DISPATCH", "swing.type.dispatch");
        expected.put("KEY", "swing.key");
        expected.put("POINTER_ROUTED", "swing.pointer.routed");
        expected.put("POINTER_ROBOT", "swing.pointer.robot");
        expected.put("DISMISS_BUTTON", "swing.dismiss.button");
        expected.put("DISMISS_CLOSE", "swing.dismiss.close");
        expected.put("ANY", "*");
        Map<String, String> actual = new LinkedHashMap<String, String>();
        for (Field f : InputVeto.class.getDeclaredFields()) {
            assertTrue(Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()), f.getName());
            assertEquals(String.class, f.getType(), f.getName());
            actual.put(f.getName(), (String) f.get(null));
        }
        assertEquals(expected, actual);
    }

    @Test
    void refuseIsTheOnlyMethodAndItIsAbstract() throws Exception {
        assertTrue(InputVeto.class.isInterface());
        List<String> methods = new ArrayList<String>();
        for (Method m : InputVeto.class.getDeclaredMethods()) {
            if (!m.isSynthetic()) {
                methods.add(m.getName());
                assertTrue(Modifier.isAbstract(m.getModifiers()), m + " has no default");
            }
        }
        assertEquals(Arrays.asList("refuse"), methods);
        Method refuse = InputVeto.class.getMethod("refuse", Component.class, String.class);
        assertEquals(String.class, refuse.getReturnType());
        assertEquals(0, refuse.getExceptionTypes().length, "no checked exception");
    }

    @Test
    void anAnonymousImplementationCompilesAndAnswers() {
        InputVeto veto = new InputVeto() {
            @Override
            public String refuse(Component component, String route) {
                return KEY.equals(route) || ANY.equals(route) ? "no keys here" : null;
            }
        };
        assertEquals("no keys here", veto.refuse(null, InputVeto.KEY));
        assertEquals("no keys here", veto.refuse(null, InputVeto.ANY));
        assertNull(veto.refuse(null, InputVeto.CLICK_ACTION));
    }

    @Test
    void thePackageDocumentationNamesTheSpi() throws Exception {
        File source = new File("src/main/java/ai/skillroy/testjava/agent/api/spi/package-info.java");
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("InputVeto"), "package-info names the SPI");
    }
}
