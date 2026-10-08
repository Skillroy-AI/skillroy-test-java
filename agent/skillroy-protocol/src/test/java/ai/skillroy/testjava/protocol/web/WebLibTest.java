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

package ai.skillroy.testjava.protocol.web;

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The script library as a resource and the expressions built around it (phase4-contracts.md section 12.1 item 2).
 * No JavaScript engine runs here (the JDK has none and none may be added); the library's behaviour is verified by
 * the adapter contract kit against a real browser and by both paths' own tests.
 */
class WebLibTest {

    private static final String HEADER = "/*\n"
            + " * Copyright 2026 Mike Mills.\n"
            + " *\n"
            + " * Licensed under the Apache License, Version 2.0 (the \"License\");\n"
            + " * you may not use this file except in compliance with the License.\n"
            + " * You may obtain a copy of the License at\n"
            + " *\n"
            + " *     http://www.apache.org/licenses/LICENSE-2.0\n"
            + " *\n"
            + " * Unless required by applicable law or agreed to in writing, software\n"
            + " * distributed under the License is distributed on an \"AS IS\" BASIS,\n"
            + " * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n"
            + " * See the License for the specific language governing permissions and\n"
            + " * limitations under the License.\n"
            + " */\n";

    private static final String LIB = "window[Symbol.for(\"skillroy\")]";

    // ---- the resource ----

    @Test
    void sourceIsTheResourceText() throws IOException {
        String text = new String(resourceBytes(), StandardCharsets.UTF_8);
        assertEquals(text, WebLib.source());
        assertSame(WebLib.source(), WebLib.source());
        assertTrue(text.length() > 10000, "the library, not a placeholder");
    }

    @Test
    void hashIsTheSha256OfTheResourceAndStable() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(resourceBytes());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", Integer.valueOf(b & 0xff)));
        }
        assertEquals(hex.toString(), WebLib.hash());
        assertEquals(WebLib.hash(), WebLib.hash());
        assertTrue(WebLib.hash().matches("[0-9a-f]{64}"));
    }

    @Test
    void resourceStartsWithTheLicenceHeader() {
        assertTrue(WebLib.source().startsWith(HEADER), "the Apache-2.0 header as the leading /* */ comment");
    }

    /** ASCII with LF line endings (the hash is the same in every working copy) and the project's 120 columns. */
    @Test
    void resourceIsAsciiWithLfLineEndingsAndShortLines() throws IOException {
        byte[] bytes = resourceBytes();
        for (int i = 0; i < bytes.length; i++) {
            assertTrue(bytes[i] >= 0 && bytes[i] != '\r' && bytes[i] != '\t', "byte " + i + " is " + bytes[i]);
        }
        String[] lines = WebLib.source().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            assertTrue(lines[i].length() <= 120, "line " + (i + 1) + " is longer than 120 columns");
        }
        assertTrue(WebLib.source().endsWith("})()\n"), "ends with the invocation and one newline");
    }

    /**
     * The whole text is one strict function expression invoked in place, with nothing before or after it but
     * comments: it declares nothing at the top level, and parenthesised it is an expression (installExpression).
     */
    @Test
    void libraryIsOneStrictFunctionExpressionInvokedInPlace() {
        assertEquals(Collections.<String>emptyList(), structureViolations(WebLib.source()));
    }

    /**
     * What this proves: no statement of the library can create an enumerable property of the global object. It
     * declares nothing at the top level (one function expression), runs in strict mode (an assignment to an
     * undeclared name throws instead of creating a global), assigns no property through a name of the global object
     * (window, self, globalThis, top, parent, frames: no {@code window.x = }, {@code window[k] = }, compound
     * assignments or increments), calls no eval, Function constructor, Object.assign, defineProperties or
     * Reflect.set on anything, and every Object.defineProperty says {@code enumerable: false} in an inline
     * descriptor, except the one restore of a descriptor the tap saved before it replaced it. The text has no
     * regular-expression literal and no division (no '/' outside comments and strings), so the lexer below, which
     * strips comments and string literals only, cannot be misled. What it does not prove: a write through a
     * variable that holds the window; the jsdom run of the build and the contract kit's Object.keys(window) check
     * (section 10.1 item 11) cover the behaviour.
     */
    @Test
    void libraryAddsNoEnumerableGlobal() {
        assertEquals(Collections.<String>emptyList(), globalViolations(WebLib.source()));
        String code = code(WebLib.source(), false);
        assertEquals(1, count(code, "Object.defineProperty(window, KEY, {value: L, enumerable: false, writable: false,"
                + " configurable: true})"), "the slot: non-enumerable, non-writable");
    }

    /** The checks above find what they look for: each fixture breaks one rule. */
    @Test
    void theTextualChecksCatchViolations() {
        String ok = "/* c */\n(function () {\n  \"use strict\";\n  var a = \"window.x = 1\"; /* self.y = 2 */\n"
                + "  // globalThis.z = 3\n  return a;\n})()\n";
        assertEquals(Collections.<String>emptyList(), structureViolations(ok));
        assertEquals(Collections.<String>emptyList(), globalViolations(ok));
        assertFalse(globalViolations(iife("window.leak = 1;")).isEmpty());
        assertFalse(globalViolations(iife("self [\"leak\"] = 1;")).isEmpty());
        assertFalse(globalViolations(iife("globalThis.n += 1;")).isEmpty());
        assertFalse(globalViolations(iife("window.n++;")).isEmpty());
        assertFalse(globalViolations(iife("Object.defineProperty(window, \"x\", {value: 1, enumerable: true});"))
                .isEmpty());
        assertFalse(globalViolations(iife("Object.defineProperty(window, \"x\", {value: 1});")).isEmpty());
        assertFalse(globalViolations(iife("Object.defineProperty(window, \"x\", d);")).isEmpty());
        assertFalse(globalViolations(iife("Object.assign(window, {x: 1});")).isEmpty());
        assertFalse(globalViolations(iife("eval(\"x = 1\");")).isEmpty());
        assertFalse(globalViolations(iife("var f = new Function(\"return 1\");")).isEmpty());
        assertFalse(globalViolations(iife("var r = 4 / 2;")).isEmpty());
        assertFalse(globalViolations(iife("var r = /x/;")).isEmpty());
        assertFalse(structureViolations("var leak = 1;\n(function () {\n  \"use strict\";\n})()\n").isEmpty());
        assertFalse(structureViolations("(function () {\n  var a = 1;\n})()\n").isEmpty());
        assertFalse(structureViolations("(function () {\n  \"use strict\";\n})();\n").isEmpty());
        assertFalse(structureViolations("(function () {\n  \"use strict\";\n})(); (function () {})()\n").isEmpty());
        assertThrows(AssertionError.class, () -> code("var s = `template`;", false));
        assertThrows(AssertionError.class, () -> code("var s = \"unterminated;", false));
    }

    /** {@link WebLib#FUNCTIONS} names exactly the functions the library exposes, besides its version string. */
    @Test
    void functionsAreTheLibrarysExposedFunctions() {
        Set<String> exposed = new TreeSet<String>();
        Matcher m = Pattern.compile("expose\\(\"([A-Za-z]+)\", ").matcher(WebLib.source());
        while (m.find()) {
            exposed.add(m.group(1));
        }
        assertTrue(exposed.remove("version"));
        assertEquals(new TreeSet<String>(WebLib.FUNCTIONS), exposed);
        for (String function : WebLib.FUNCTIONS) {
            assertTrue(WebLib.source().contains("function " + function + "("), function + " is a function");
        }
    }

    /** The limits the Java constants document are the ones the library applies. */
    @Test
    void constantsAreTheLibrarysLimits() {
        String source = WebLib.source();
        assertTrue(source.contains("var MAX_JSON = " + WebLib.MAX_JSON_CHARS + ";"));
        assertTrue(source.contains("var MAX_DETAIL = " + WebLib.MAX_DETAIL_CHARS + ";"));
        assertTrue(source.contains("var MAX_EVENTS = " + WebLib.MAX_BUFFERED_EVENTS + ";"));
        assertEquals(1048576, WebLib.MAX_JSON_CHARS);
        assertEquals(65536, WebLib.MAX_DETAIL_CHARS);
        assertEquals("window[" + WebLib.SLOT + "]", LIB);
        assertTrue(source.contains("var KEY = " + WebLib.SLOT + ";"));
        assertTrue(source.contains("__error: code"), "error objects use " + WebLib.ERROR_KEY);
        assertEquals("__error", WebLib.ERROR_KEY);
    }

    // ---- the expressions ----

    @Test
    void installExpressionIsTheParenthesisedSource() {
        assertEquals("(" + WebLib.source() + "\n)", WebLib.installExpression());
    }

    @Test
    void callExpressionEncodesArgumentsAsJson() {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("selector", "#item");
        options.put("text", "a \"quoted\" \\ back\nslash   </script>");
        options.put("clear", Boolean.TRUE);
        options.put("index", Integer.valueOf(0));
        options.put("ratio", Double.valueOf(1.5));
        options.put("big", Long.valueOf(9007199254740993L));
        options.put("none", null);
        options.put("list", Arrays.<Object>asList("x", Integer.valueOf(2), Boolean.FALSE, null));
        String expression = WebLib.callExpression("domFill", options, "second", Integer.valueOf(-3), Boolean.TRUE,
                null, Arrays.asList(Integer.valueOf(1), Integer.valueOf(2)), new String[] {"p", "q"});
        assertTrue(expression.startsWith("(function(){" + WebLib.source() + ";return " + LIB + ".domFill("));
        assertTrue(expression.endsWith(".domFill({\"selector\":\"#item\",\"text\":\"a \\\"quoted\\\" \\\\ back\\n"
                + "slash \\u2028 </script>\",\"clear\":true,\"index\":0,\"ratio\":1.5,\"big\":9007199254740993,"
                + "\"none\":null,\"list\":[\"x\",2,false,null]},\"second\",-3,true,null,[1,2],[\"p\",\"q\"]);})()"),
                expression.substring(expression.length() - 300));
        assertEquals(1, count(expression, WebLib.source()), "the library once");
        assertTrue(WebLib.callExpression("drain").endsWith("return " + LIB + ".drain();})()"));
        assertTrue(WebLib.callExpression("aria", null, Integer.valueOf(2000), Boolean.TRUE)
                .endsWith(".aria(null,2000,true);})()"));
        assertTrue(WebLib.callExpression("stringify", "x").endsWith(".stringify(\"x\");})()"));
    }

    @Test
    void callExpressionRefusesABadFunctionName() {
        for (String bad : Arrays.asList(null, "", "nope", "domclick", "drain()", "a.b", "constructor", "__proto__",
                "version", "toString", "tap;alert(1)")) {
            assertThrows(IllegalArgumentException.class, () -> WebLib.callExpression(bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> WebLib.callJsonExpression(bad), String.valueOf(bad));
        }
    }

    @Test
    void callJsonExpressionStringifiesTheReturnValueWithTheLibraryOnce() {
        Map<String, Object> options = Collections.<String, Object>singletonMap("selector", "#status");
        String expression = WebLib.callJsonExpression("domText", options);
        assertTrue(expression.startsWith("(function(){" + WebLib.source() + ";"));
        assertTrue(expression.endsWith("return " + LIB + ".stringify(" + LIB
                + ".domText({\"selector\":\"#status\"}));})()"));
        assertEquals(1, count(expression, WebLib.source()));
        assertTrue(WebLib.callJsonExpression("drain").endsWith(".stringify(" + LIB + ".drain());})()"));
    }

    @Test
    void callExpressionWithANullArrayPassesOneNull() {
        assertTrue(WebLib.callExpression("stringify", (Object[]) null).endsWith(".stringify(null);})()"));
        assertTrue(WebLib.callExpression("stringify", (Object) null).endsWith(".stringify(null);})()"));
    }

    @Test
    void wrapContainsTheExpressionOnceAndEndsByCallingStringify() {
        String expression = "document.querySelector('#item').value.length + 41";
        String sync = WebLib.wrap(expression, false);
        assertEquals(1, count(sync, expression));
        assertTrue(sync.startsWith("(function(){" + WebLib.source() + ";"));
        assertTrue(sync.endsWith("return " + LIB + ".stringify((" + expression + "\n));})()"));
        String async = WebLib.wrap(expression, true);
        assertEquals(1, count(async, expression));
        assertTrue(async.startsWith("(async function(){" + WebLib.source() + ";"));
        assertTrue(async.endsWith("return " + LIB + ".stringify(await (" + expression + "\n));})()"));
    }

    @Test
    void wrapDropsTrailingSemicolonsAndRefusesBlank() {
        assertTrue(WebLib.wrap("document.title; ;\n", false).endsWith(".stringify((document.title\n));})()"));
        assertTrue(WebLib.wrap("1 // a trailing comment", false).endsWith("((1 // a trailing comment\n));})()"),
                "the newline ends a line comment before the closing parentheses");
        assertThrows(IllegalArgumentException.class, () -> WebLib.wrap(null, false));
        assertThrows(IllegalArgumentException.class, () -> WebLib.wrap(" ; \n", true));
        assertThrows(IllegalArgumentException.class, () -> WebLib.wrap("", false));
    }

    @Test
    void deferAndCollectExpressions() {
        String defer = WebLib.deferExpression("fetchSlip();");
        assertTrue(defer.startsWith("(function(){" + WebLib.source() + ";"));
        assertTrue(defer.endsWith("return " + LIB + ".defer((fetchSlip()\n));})()"));
        assertTrue(WebLib.collectExpression("d7").endsWith("return " + LIB + ".collect(\"d7\");})()"));
        assertThrows(IllegalArgumentException.class, () -> WebLib.deferExpression(" "));
        assertThrows(IllegalArgumentException.class, () -> WebLib.collectExpression(null));
        assertThrows(IllegalArgumentException.class, () -> WebLib.collectExpression(""));
    }

    @Test
    void tapInstallerNamesThePatternsAndTheTarget() {
        String installer = WebLib.tapInstaller(Arrays.asList("webhost.*", "lending.ready"), "document");
        assertTrue(installer.startsWith("(function(){" + WebLib.source() + ";"));
        assertTrue(installer.endsWith("return " + LIB + ".tap([\"webhost.*\",\"lending.ready\"],\"document\");})()"));
        assertTrue(WebLib.tapInstaller(Collections.singletonList("a.b"), null)
                .endsWith(".tap([\"a.b\"],\"window\");})()"));
        assertTrue(WebLib.tapInstaller(Collections.<String>emptyList(), "window").endsWith(".tap([],\"window\");})()"));
    }

    @Test
    void tapInstallerRefusesBadInput() {
        assertThrows(IllegalArgumentException.class, () -> WebLib.tapInstaller(null, "window"));
        assertThrows(IllegalArgumentException.class,
                () -> WebLib.tapInstaller(Arrays.asList("a", null), "window"));
        assertThrows(IllegalArgumentException.class,
                () -> WebLib.tapInstaller(Collections.singletonList(""), "window"));
        assertThrows(IllegalArgumentException.class,
                () -> WebLib.tapInstaller(Collections.singletonList("a"), "body"));
    }

    // ---- error objects ----

    @Test
    void errorOfMapsLibraryErrorObjects() {
        RpcError notFound = WebLib.errorOf(error("NOT_FOUND", "selector", "#nope"));
        assertEquals(ErrorCodes.NOT_FOUND, notFound.code());
        assertEquals("no element matches the selector \"#nope\"", notFound.message());
        assertEquals(Collections.singletonMap("selector", "#nope"), notFound.data());

        Map<String, Object> byRole = error("NOT_FOUND", "role", "button");
        data(byRole).put("name", "Twin");
        data(byRole).put("index", Integer.valueOf(7));
        data(byRole).put("count", Integer.valueOf(2));
        RpcError atIndex = WebLib.errorOf(byRole);
        assertEquals("no element matches the role \"button\" named \"Twin\" at index 7 (2 matched)", atIndex.message());
        assertEquals(Long.valueOf(7), atIndex.data().get("index"), "numbers normalised as the codec reads them");

        Map<String, Object> regex = new LinkedHashMap<String, Object>();
        regex.put("regex", "^B");
        Map<String, Object> byRegex = error("NOT_FOUND", "role", "button");
        data(byRegex).put("name", regex);
        assertEquals("no element matches the role \"button\" named {\"regex\":\"^B\"}",
                WebLib.errorOf(byRegex).message());

        Map<String, Object> ambiguous = error("AMBIGUOUS", "count", Integer.valueOf(2));
        data(ambiguous).put("candidates", new ArrayList<Object>());
        RpcError several = WebLib.errorOf(ambiguous);
        assertEquals(ErrorCodes.AMBIGUOUS, several.code());
        assertEquals("2 elements match; give an index or a narrower locator", several.message());

        RpcError hidden = WebLib.errorOf(error("INVALID_PARAMS", "reason", "not visible"));
        assertEquals(ErrorCodes.INVALID_PARAMS, hidden.code());
        assertEquals("not visible", hidden.message());
        Map<String, Object> badSelector = error("INVALID_PARAMS", "reason", "invalid selector");
        data(badSelector).put("message", "'[' is not a valid selector");
        assertEquals("invalid selector: '[' is not a valid selector", WebLib.errorOf(badSelector).message());

        RpcError other = WebLib.errorOf(error("SOMETHING_NEW", "x", "y"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, other.code());
        assertEquals("SOMETHING_NEW", other.data().get("error"));

        Map<String, Object> noData = new LinkedHashMap<String, Object>();
        noData.put(WebLib.ERROR_KEY, "INVALID_PARAMS");
        assertEquals("invalid parameters", WebLib.errorOf(noData).message());
        assertEquals(Collections.emptyMap(), WebLib.errorOf(noData).data());

        RpcError back = RpcError.fromJson(Json.parseObject(Json.write(atIndex.toJson())));
        assertEquals(atIndex, back);
    }

    @Test
    void errorOfIgnoresOtherValues() {
        assertNull(WebLib.errorOf(null));
        assertNull(WebLib.errorOf("NOT_FOUND"));
        assertNull(WebLib.errorOf(Collections.singletonMap("matched", Integer.valueOf(1))));
        assertNull(WebLib.errorOf(Collections.singletonMap(WebLib.ERROR_KEY, Integer.valueOf(1))));
        assertNull(WebLib.errorOf(Arrays.asList(WebLib.ERROR_KEY, "NOT_FOUND")));
    }

    // ---- helpers ----

    private static String iife(String body) {
        return "(function () {\n  \"use strict\";\n  " + body + "\n})()\n";
    }

    private static Map<String, Object> error(String name, String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put(key, value);
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put(WebLib.ERROR_KEY, name);
        error.put("data", data);
        return error;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> error) {
        return (Map<String, Object>) error.get("data");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            n++;
        }
        return n;
    }

    /**
     * The code of a JavaScript text: comments removed (a block comment becomes one space) and, unless
     * {@code keepStrings}, the content of every string literal removed (its quotes kept). Fails on a template
     * literal and on an unterminated comment or string. Regular-expression literals are not recognised; the library
     * has none, which {@link #globalViolations} checks (no '/' may remain).
     */
    static String code(String js, boolean keepStrings) {
        StringBuilder out = new StringBuilder(js.length());
        int i = 0;
        int n = js.length();
        while (i < n) {
            char c = js.charAt(i);
            if (c == '/' && i + 1 < n && js.charAt(i + 1) == '/') {
                while (i < n && js.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && js.charAt(i + 1) == '*') {
                int end = js.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new AssertionError("unterminated comment at " + i);
                }
                out.append(' ');
                i = end + 2;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && js.charAt(j) != c) {
                    if (js.charAt(j) == '\n') {
                        throw new AssertionError("unterminated string at " + i);
                    }
                    j += js.charAt(j) == '\\' ? 2 : 1;
                }
                if (j >= n) {
                    throw new AssertionError("unterminated string at " + i);
                }
                out.append(keepStrings ? js.substring(i, j + 1) : "" + c + c);
                i = j + 1;
            } else if (c == '`') {
                throw new AssertionError("template literal at " + i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** One function expression invoked in place, nothing else, whose body starts with "use strict". */
    static List<String> structureViolations(String js) {
        List<String> problems = new ArrayList<String>();
        String code = code(js, true).trim();
        if (!code.startsWith("(function () {") || !code.endsWith("})()")) {
            problems.add("not of the form (function () {...})(): " + abbreviate(code));
            return problems;
        }
        int depth = 0;
        String stripped = code(js, false).trim();
        for (int i = 0; i < stripped.length() - 2; i++) {
            char c = stripped.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0 && i != stripped.length() - 3) {
                    problems.add("the function expression closes early, at " + i + ": " + abbreviate(stripped));
                    break;
                }
            }
        }
        if (!code.substring("(function () {".length()).trim().startsWith("\"use strict\";")) {
            problems.add("the body does not start with \"use strict\"");
        }
        return problems;
    }

    private static final Pattern GLOBAL_NAME = Pattern.compile("\\b(window|self|globalThis|top|parent|frames)\\b");
    private static final Pattern MEMBER_WRITE = Pattern.compile("\\b(window|self|globalThis|top|parent|frames)\\s*"
            + "(\\.\\s*[A-Za-z_$][\\w$]*|\\[[^\\]]*\\])\\s*"
            + "(=(?!=)|[-+*%&|^]=|\\*\\*=|<<=|>>>?=|&&=|\\|\\|=|\\?\\?=|\\+\\+|--)");
    private static final Pattern MEMBER_INCREMENT = Pattern.compile("(\\+\\+|--)\\s*"
            + "(window|self|globalThis|top|parent|frames)\\s*[.\\[]");
    private static final Pattern FORBIDDEN_CALL = Pattern.compile("\\beval\\s*\\(|\\bFunction\\s*\\(|"
            + "\\bObject\\s*\\.\\s*(assign|defineProperties)\\s*\\(|\\bReflect\\s*\\.\\s*(set|defineProperty)\\s*\\(|"
            + "__define[GS]etter__|\\bwith\\s*\\(");

    /** The rules of {@link #libraryAddsNoEnumerableGlobal}; an empty list when the text keeps all of them. */
    static List<String> globalViolations(String js) {
        List<String> problems = new ArrayList<String>(structureViolations(js));
        String code = code(js, false);
        if (code.indexOf('/') >= 0) {
            problems.add("a '/' outside comments and strings (division or a regular-expression literal): "
                    + abbreviate(code.substring(code.indexOf('/'))));
        }
        Matcher write = MEMBER_WRITE.matcher(code);
        while (write.find()) {
            problems.add("assignment through a global name: " + write.group());
        }
        Matcher increment = MEMBER_INCREMENT.matcher(code);
        while (increment.find()) {
            problems.add("increment through a global name: " + increment.group());
        }
        Matcher call = FORBIDDEN_CALL.matcher(code);
        while (call.find()) {
            problems.add("forbidden call: " + call.group());
        }
        int restores = 0;
        for (int at = code.indexOf("defineProperty("); at >= 0; at = code.indexOf("defineProperty(", at + 1)) {
            List<String> args = arguments(code, at + "defineProperty".length());
            String descriptor = args.size() == 3 ? args.get(2).trim() : "";
            if (descriptor.equals("saved")) {
                restores++;
            } else if (!descriptor.startsWith("{") || !descriptor.matches("(?s).*\\benumerable: false\\b.*")
                    || descriptor.matches("(?s).*\\benumerable: true\\b.*")) {
                problems.add("defineProperty without an inline enumerable: false: " + abbreviate(descriptor));
            }
        }
        if (restores > 1) {
            problems.add("more than one restore of a saved descriptor");
        }
        if (!GLOBAL_NAME.matcher(code).find() && code.contains("defineProperty(")) {
            problems.add("no global name at all: the check would be vacuous");
        }
        return problems;
    }

    /** The comma-separated arguments of the call whose '(' is at {@code open}, split at depth one. */
    private static List<String> arguments(String code, int open) {
        List<String> args = new ArrayList<String>();
        int depth = 0;
        int start = open + 1;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    args.add(code.substring(start, i));
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(code.substring(start, i));
                start = i + 1;
            }
        }
        throw new AssertionError("unbalanced call at " + open);
    }

    private static String abbreviate(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }

    private static byte[] resourceBytes() throws IOException {
        InputStream in = WebLibTest.class.getClassLoader().getResourceAsStream(WebLib.RESOURCE);
        assertTrue(in != null, "resource " + WebLib.RESOURCE);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
