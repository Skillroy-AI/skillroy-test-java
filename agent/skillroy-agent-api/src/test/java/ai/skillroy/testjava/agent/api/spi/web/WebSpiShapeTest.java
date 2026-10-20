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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The SPI's shape as the Phase 4 contract §3 lists it: every enum constant present by name (the adapter contract kit
 * relies on them), the members of every type, and no type outside the JDK and the agent API in any signature.
 */
class WebSpiShapeTest {

    private static final String AGENT_API = "ai.skillroy.testjava.agent.api.";
    private static final String WEB = AGENT_API + "spi.web.";
    private static final String PROBE_RESULT = "ai.skillroy.testjava.agent.api.ProbeResult";
    private static final String FUTURE = "java.util.concurrent.CompletableFuture";

    private static final List<Class<?>> TYPES = Arrays.<Class<?>>asList(WebEngineAdapter.class, WebBrowser.class,
            WebEval.class, WebEval.Builder.class, WebFrame.class, WebEvent.class, WebEvent.Kind.class,
            WebEventListener.class, WebSubscription.class, WebInitScript.class, WebInitScript.Timing.class,
            WebCapability.class, WebRendering.class, BitmapSource.class, CdpEndpoint.class, WebScriptException.class,
            WebFrameGoneException.class, BrowserRegistryAdapter.class, BrowserSeed.class, WebPointerEvent.class,
            WebPointerEvent.Builder.class, WebPointerEvent.Action.class, WebPointerEvent.Button.class,
            WebPointerEvent.Modifier.class, BrowserMask.class);

    @Test
    void everyCapabilityIsPresentByName() {
        List<String> names = Arrays.asList("EVALUATE", "AWAIT_PROMISE", "FRAMES", "EVENTS_LOAD", "EVENTS_CONSOLE",
                "INIT_SCRIPT_DOCUMENT_START", "RESIZE", "BITMAP", "CDP_TARGET_ID", "INPUT", "RAW_PROTOCOL");
        assertEquals(names, names(WebCapability.values()));
        for (String name : names) {
            assertEquals(name, WebCapability.valueOf(name).name());
        }
    }

    @Test
    void everyRenderingIsPresentByName() {
        List<String> names = Arrays.asList("WINDOWED", "OFFSCREEN_LIGHTWEIGHT", "OFFSCREEN_HEAVYWEIGHT", "UNKNOWN");
        assertEquals(names, names(WebRendering.values()));
        for (String name : names) {
            assertEquals(name, WebRendering.valueOf(name).name());
        }
    }

    @Test
    void everyBitmapSourceIsPresentByName() {
        List<String> names = Arrays.asList("PROTOCOL", "VENDOR");
        assertEquals(names, names(BitmapSource.values()));
        for (String name : names) {
            assertEquals(name, BitmapSource.valueOf(name).name());
        }
    }

    /** Phase 7 (A66): the pointer event's enums, in their frozen order. */
    @Test
    void everyPointerActionButtonAndModifierIsPresentByName() {
        assertEquals(Arrays.asList("MOVE", "PRESS", "RELEASE", "WHEEL"), names(WebPointerEvent.Action.values()));
        assertEquals(Arrays.asList("NONE", "LEFT", "MIDDLE", "RIGHT"), names(WebPointerEvent.Button.values()));
        assertEquals(Arrays.asList("ALT", "CTRL", "META", "SHIFT"), names(WebPointerEvent.Modifier.values()));
    }

    @Test
    void everyEventKindAndInitScriptTimingIsPresentByName() {
        assertEquals(Arrays.asList("LOAD_FINISHED", "LOAD_FAILED", "NAVIGATED", "CONSOLE", "CLOSED"),
                names(WebEvent.Kind.values()));
        assertEquals(Arrays.asList("DOCUMENT_START", "DOCUMENT_LOADED"), names(WebInitScript.Timing.values()));
    }

    @Test
    void webEngineAdapterHasTheContractsMembers() {
        assertMembers(WebEngineAdapter.class,
                "java.lang.String id()",
                PROBE_RESULT + " probe(java.lang.ClassLoader)",
                "java.util.List<" + WEB + "WebBrowser> browsers(java.util.Collection<?>)",
                "java.util.List<" + WEB + "CdpEndpoint> cdpEndpoints(java.util.Collection<?>)",
                PROBE_RESULT + " enumerationState()",
                "java.util.List<java.lang.String> enumerationNotes()",
                "void detach()");
    }

    @Test
    void webBrowserHasTheContractsMembers() {
        assertMembers(WebBrowser.class,
                "java.lang.String adapterId()",
                "java.lang.Object vendorObject()",
                "java.lang.String nativeId()",
                "java.util.Set<" + WEB + "WebCapability> capabilities()",
                "boolean isClosed()",
                "java.lang.String url()",
                "java.awt.Component component()",
                WEB + "WebRendering rendering()",
                "java.awt.Dimension viewport()",
                "java.util.List<" + WEB + "WebFrame> frames()",
                FUTURE + "<java.lang.String> evaluate(" + WEB + "WebEval)",
                WEB + "WebSubscription subscribe(" + WEB + "WebEventListener)",
                WEB + "WebInitScript addInitScript(java.lang.String)",
                FUTURE + "<java.lang.Void> resize(int, int)",
                FUTURE + "<java.lang.Void> clearResize()",
                FUTURE + "<java.awt.image.BufferedImage> bitmap()",
                FUTURE + "<java.lang.String> cdpTargetId()",
                FUTURE + "<java.lang.Void> reload(boolean)",
                WEB + "BitmapSource bitmapSource()",
                FUTURE + "<java.lang.Void> dispatchPointer(" + WEB + "WebPointerEvent)");
    }

    /**
     * A37 and A66: the methods added to {@code WebBrowser} after Phase 4 are default methods, so that an adapter built
     * against the Phase 4 SPI links and runs unchanged; every other method stays abstract.
     */
    @Test
    void theMethodsAddedAfterPhase4AreDefaultMethods() throws Exception {
        List<String> added = Arrays.asList("reload", "bitmapSource", "dispatchPointer");
        for (Method method : WebBrowser.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertEquals(added.contains(method.getName()), method.isDefault(), method.toGenericString());
        }
        assertTrue(WebBrowser.class.getMethod("reload", boolean.class).isDefault());
        assertTrue(WebBrowser.class.getMethod("bitmapSource").isDefault());
        assertTrue(WebBrowser.class.getMethod("dispatchPointer", WebPointerEvent.class).isDefault());
    }

    @Test
    void theSmallInterfacesHaveTheContractsMembers() {
        assertMembers(WebEventListener.class, "void onEvent(" + WEB + "WebBrowser, " + WEB + "WebEvent)");
        assertMembers(WebSubscription.class, "void cancel()");
        assertMembers(WebInitScript.class, WEB + "WebInitScript$Timing timing()");
        assertEquals(Arrays.<Type>asList(WebSubscription.class), Arrays.asList(WebInitScript.class.getInterfaces()));
        assertMembers(BrowserRegistryAdapter.class, "java.util.Collection<" + WEB + "BrowserSeed> seeds()");
    }

    /**
     * Phase 8 (gap 110, A79; phase8-contracts.md 5.2): {@code BrowserMask} is a new interface with one abstract method
     * and no default, whose signature names no AWT and no vendor type (it is called on an agent worker, never on the
     * EDT, and a pack implements it without knowing a vendor type).
     */
    @Test
    void browserMaskHasOneAbstractMethodAndNamesNoAwtType() throws Exception {
        assertTrue(BrowserMask.class.isInterface());
        assertEquals(0, BrowserMask.class.getInterfaces().length);
        assertMembers(BrowserMask.class, "java.lang.Boolean masks(java.lang.String, java.lang.Object, boolean)");
        Method masks = BrowserMask.class.getMethod("masks", String.class, Object.class, boolean.class);
        assertTrue(Modifier.isAbstract(masks.getModifiers()), "abstract, not a default");
        for (Method method : BrowserMask.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            List<Type> named = new ArrayList<Type>(Arrays.asList(method.getGenericParameterTypes()));
            named.add(method.getGenericReturnType());
            for (Type type : named) {
                String name = type.getTypeName();
                assertTrue(!name.startsWith("java.awt.") && !name.startsWith("javax.swing."),
                        method.toGenericString() + " names " + name);
            }
        }
        BrowserMask lambda = (tag, vendor, hasView) -> "back".equals(tag) ? Boolean.TRUE : null;
        assertEquals(Boolean.TRUE, lambda.masks("back", new Object(), true));
        assertEquals(null, lambda.masks(null, new Object(), false));
    }

    @Test
    void theValueTypesHaveTheContractsMembers() {
        assertMembers(WebEval.class,
                "static " + WEB + "WebEval$Builder of(java.lang.String)",
                "java.lang.String expression()",
                "java.lang.String frameKey()",
                "boolean awaitPromise()",
                "long timeoutMs()",
                "boolean equals(java.lang.Object)", "int hashCode()", "java.lang.String toString()");
        assertMembers(WebEval.Builder.class,
                WEB + "WebEval$Builder frameKey(java.lang.String)",
                WEB + "WebEval$Builder awaitPromise(boolean)",
                WEB + "WebEval$Builder timeoutMs(long)",
                WEB + "WebEval build()");
        assertMembers(WebFrame.class,
                "new(java.lang.String, java.lang.String, java.lang.String, java.lang.String, boolean)",
                "java.lang.String key()",
                "java.lang.String parentKey()",
                "java.lang.String name()",
                "java.lang.String url()",
                "boolean main()",
                "boolean equals(java.lang.Object)", "int hashCode()", "java.lang.String toString()");
        assertMembers(WebEvent.class,
                "static " + WEB + "WebEvent loadFinished(java.lang.String, java.lang.String)",
                "static " + WEB + "WebEvent loadFailed(java.lang.String, java.lang.String, java.lang.String)",
                "static " + WEB + "WebEvent navigated(java.lang.String, java.lang.String)",
                "static " + WEB + "WebEvent console(java.lang.String, java.lang.String, java.lang.String, int)",
                "static " + WEB + "WebEvent closed()",
                WEB + "WebEvent$Kind kind()",
                "java.lang.String frameKey()",
                "java.lang.String url()",
                "java.lang.String level()",
                "java.lang.String text()",
                "java.lang.String source()",
                "int line()",
                "java.lang.String error()",
                "boolean equals(java.lang.Object)", "int hashCode()", "java.lang.String toString()");
        assertMembers(CdpEndpoint.class,
                "new(java.lang.String, int, java.lang.String)",
                "java.lang.String host()",
                "int port()",
                "java.lang.String engineLabel()",
                "boolean equals(java.lang.Object)", "int hashCode()", "java.lang.String toString()");
        assertMembers(WebScriptException.class, "new(java.lang.String, java.lang.String)", "java.lang.String stack()");
        assertMembers(WebFrameGoneException.class, "new(java.lang.String)", "java.lang.String frameKey()");
        assertMembers(BrowserSeed.class,
                "static " + WEB + "BrowserSeed of(java.lang.Object)",
                "static " + WEB + "BrowserSeed of(java.lang.Object, java.lang.String)",
                "java.lang.Object object()",
                "java.lang.String tag()",
                "boolean equals(java.lang.Object)", "int hashCode()", "java.lang.String toString()");
        assertMembers(WebPointerEvent.class,
                "static " + WEB + "WebPointerEvent$Builder of(" + WEB + "WebPointerEvent$Action, int, int)",
                WEB + "WebPointerEvent$Action action()",
                "int x()",
                "int y()",
                WEB + "WebPointerEvent$Button button()",
                "int clickCount()",
                "java.util.Set<" + WEB + "WebPointerEvent$Modifier> modifiers()",
                "int deltaX()",
                "int deltaY()",
                "boolean equals(java.lang.Object)", "int hashCode()", "java.lang.String toString()");
        assertMembers(WebPointerEvent.Builder.class,
                WEB + "WebPointerEvent$Builder button(" + WEB + "WebPointerEvent$Button)",
                WEB + "WebPointerEvent$Builder clickCount(int)",
                WEB + "WebPointerEvent$Builder modifiers(java.util.Set<" + WEB + "WebPointerEvent$Modifier>)",
                WEB + "WebPointerEvent$Builder delta(int, int)",
                WEB + "WebPointerEvent build()");
    }

    @Test
    void theValueTypesAreFinalWithPrivateFinalFields() {
        for (Class<?> type : Arrays.<Class<?>>asList(WebEval.class, WebFrame.class, WebEvent.class, CdpEndpoint.class,
                BrowserSeed.class, WebScriptException.class, WebFrameGoneException.class, WebPointerEvent.class)) {
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getName() + " is final");
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers)) {
                    // a constant (WebPointerEvent's bounds): final, whatever its visibility
                    assertTrue(Modifier.isFinal(modifiers), type.getSimpleName() + "." + field.getName() + " is final");
                    continue;
                }
                assertTrue(Modifier.isPrivate(modifiers) && Modifier.isFinal(modifiers),
                        type.getSimpleName() + "." + field.getName() + " is private and final");
            }
        }
    }

    @Test
    void noSignatureNamesATypeOutsideTheJdkAndTheAgentApi() {
        for (Class<?> type : TYPES) {
            assertAllowed(type.getGenericSuperclass(), type.getName());
            for (Type implemented : type.getGenericInterfaces()) {
                assertAllowed(implemented, type.getName());
            }
            for (Field field : type.getFields()) {
                assertAllowed(field.getGenericType(), type.getName() + "." + field.getName());
            }
            for (Constructor<?> constructor : type.getConstructors()) {
                assertAllowed(constructor.getGenericParameterTypes(), constructor.toGenericString());
                assertAllowed(constructor.getGenericExceptionTypes(), constructor.toGenericString());
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                assertAllowed(method.getGenericReturnType(), method.toGenericString());
                assertAllowed(method.getGenericParameterTypes(), method.toGenericString());
                assertAllowed(method.getGenericExceptionTypes(), method.toGenericString());
            }
        }
    }

    /** The public constructors ({@code new(...)}) and the public methods declared by the type, in any order. */
    private static void assertMembers(Class<?> type, String... expected) {
        Set<String> actual = new TreeSet<String>();
        for (Constructor<?> constructor : type.getConstructors()) {
            actual.add("new(" + typeNames(constructor.getGenericParameterTypes()) + ")");
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            actual.add((Modifier.isStatic(method.getModifiers()) ? "static " : "")
                    + method.getGenericReturnType().getTypeName() + " " + method.getName() + "("
                    + typeNames(method.getGenericParameterTypes()) + ")");
        }
        assertEquals(new TreeSet<String>(Arrays.asList(expected)), actual, type.getName());
    }

    private static String typeNames(Type[] types) {
        StringBuilder text = new StringBuilder();
        for (Type type : types) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(type.getTypeName());
        }
        return text.toString();
    }

    private static void assertAllowed(Type[] types, String where) {
        for (Type type : types) {
            assertAllowed(type, where);
        }
    }

    private static void assertAllowed(Type type, String where) {
        if (type == null) {
            return;
        }
        if (type instanceof Class) {
            Class<?> raw = (Class<?>) type;
            while (raw.isArray()) {
                raw = raw.getComponentType();
            }
            String name = raw.getName();
            assertTrue(raw.isPrimitive() || name.startsWith("java.") || name.startsWith(AGENT_API),
                    where + " names " + name);
        } else if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            assertAllowed(parameterized.getRawType(), where);
            assertAllowed(parameterized.getOwnerType(), where);
            assertAllowed(parameterized.getActualTypeArguments(), where);
        } else if (type instanceof WildcardType) {
            assertAllowed(((WildcardType) type).getUpperBounds(), where);
            assertAllowed(((WildcardType) type).getLowerBounds(), where);
        } else if (type instanceof GenericArrayType) {
            assertAllowed(((GenericArrayType) type).getGenericComponentType(), where);
        } else if (type instanceof TypeVariable) {
            assertAllowed(((TypeVariable<?>) type).getBounds(), where);
        } else {
            fail(where + ": unexpected kind of type " + type);
        }
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> names = new ArrayList<String>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }
}
