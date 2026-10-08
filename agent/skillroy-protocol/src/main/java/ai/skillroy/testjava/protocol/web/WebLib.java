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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The script library both web paths inject into a page (phase4-contracts.md section 4.6): one JavaScript source,
 * {@value #RESOURCE}, installed under the non-enumerable, non-writable slot {@code window[Symbol.for("skillroy")]},
 * idempotent. The agent module evaluates it through the embedded browser's own channel, the CDP path through
 * Playwright, so that both return identical shapes for the same page; {@link #hash()} is recorded by both so that a
 * difference can be traced to a library version. Java 8; no dependency beyond the JDK and this module's codec.
 *
 * <p>Every expression this class builds is one self-contained JavaScript expression (an immediately invoked
 * function) that installs the library when it is absent, so it can be handed as it is to {@code Runtime.evaluate},
 * to a JxBrowser frame's {@code executeJavaScript}, to Playwright's {@code page.evaluate} (whose driver evaluates a
 * string and calls the result only when it is a function, which none of these is) or to an init script. The
 * library is per document: a call meant for a frame is evaluated in that frame's context. The one exception is the
 * guard of {@code web.waitFlag} ({@link #flagExpression}, amendment A35), which needs no library and installs none:
 * it compiles the caller's expression with {@code new Function}, which the library itself never does.
 *
 * <p>The library object {@code L} offers ({@link #FUNCTIONS}; a failure the caller can act on is returned as an
 * error object, see {@link #errorOf}):
 * <ul>
 *   <li>{@code stringify(value)}: the JSON text of {@code {json, typeofResult, truncated}}; {@code json} is the JSON
 *     text of the value with a replacer that writes {@code undefined} as {@code null}, functions, symbols and
 *     bigints as their {@code String()}, windows as their {@code String()}, DOM nodes as
 *     {@code {nodeName, id, className}} and a value that is its own ancestor as {@code "[cycle]"}; it is cut at
 *     {@value #MAX_JSON_CHARS} UTF-16 code units (never inside a surrogate pair) with {@code truncated: true}, and
 *     is then not valid JSON.</li>
 *   <li>{@code aria(root, maxNodes, interestingOnly)}: {@code {nodes: [{role, name, value?, checked?, disabled?,
 *     focused?, level?, children?}], truncated}}; {@code root} null walks the document's body without emitting the
 *     body; an element (or a selector) is walked as any element is, itself included. Text becomes
 *     {@code {role: "text", name}}; {@code maxNodes} (default 2000) counts every node, text included.</li>
 *   <li>{@code find(selectorOrRole, name, index)}: the matched element, or {@code {count, candidates: [{role, name,
 *     tag, id?}]}} (at most 20 candidates, document order) when none or several match without an index, or an index
 *     is out of range; {@code name == null} means a CSS selector, else a role with a name that is a string (exact
 *     after trimming and collapsing whitespace) or {@code {regex, ignoreCase?}}.</li>
 *   <li>{@code domClick({selector? | role?, name?, index?, expectVisible = true})}: {@code {matched, clicked: {role,
 *     name, tag, id?}, fidelity: "DOM"}}; {@code el.click()} after the visibility check, no pointer events.</li>
 *   <li>{@code domFill({selector? | role?, name?, index?, expectVisible = true, text, clear = true, enter = false})}:
 *     {@code {matched, filled: {tag, type, id?}, typed}}; focus, the value through the prototype's setter,
 *     {@code input} and {@code change} (bubbling), and an {@code Enter} {@code keydown}/{@code keyup} pair when
 *     asked.</li>
 *   <li>{@code domText({selector = "body", maxChars = 10000})}: {@code {text, truncated}} ({@code innerText}).</li>
 *   <li>{@code dispatch({target?, name, detail?})} (or {@code dispatch(target, name, detail)}):
 *     {@code {dispatched: true}}; a {@code CustomEvent} with {@code bubbles: true} on the target ({@code "window"}
 *     by default, {@code "document"}, or a selector).</li>
 *   <li>{@code tap(patterns, target)}: {@code {installed: n}}; replaces the tap on {@code "window"} (default) or
 *     {@code "document"}; {@code tap([])} removes it. {@code drain(maxChars?)}: {@code {events: [{name, detail?,
 *     truncated?}], dropped}} and clears the buffer (at most {@value #MAX_BUFFERED_EVENTS} events; a detail's JSON
 *     text longer than {@value #MAX_DETAIL_CHARS} is kept cut, as a string, with {@code truncated: true}); with
 *     {@code maxChars} it returns events up to about that much JSON, keeps the rest and adds {@code more: true}.</li>
 *   <li>{@code click(el)}, {@code fill(el, text, clear, enter)}, {@code text(el, maxChars)}: the same actions on an
 *     element in hand (a Playwright element handle, for example): {@code {role, name, tag, id?}},
 *     {@code {tag, type, id?, typed}} and {@code {text, truncated}}.</li>
 *   <li>{@code defer(value)} and {@code collect(id)}: see {@link #deferExpression} and {@link #collectExpression}.
 *     </li>
 * </ul>
 *
 * <p>Stateless apart from the cached source and hash; safe to call from any thread.
 */
public final class WebLib {

    /** The class-path resource that holds the library. */
    public static final String RESOURCE = "ai/skillroy/testjava/protocol/web/web-lib.js";

    /** The JavaScript expression of the slot's key: the library lives at {@code window[SLOT]}. */
    public static final String SLOT = "Symbol.for(\"skillroy\")";

    /**
     * The member that marks a library error object, {@code {__error: "NOT_FOUND" | "AMBIGUOUS" | "INVALID_PARAMS",
     * data: {…}}}; {@link #errorOf} turns one into the RPC error of that name.
     */
    public static final String ERROR_KEY = "__error";

    /** The library's functions, the names {@link #callExpression} accepts. */
    public static final Set<String> FUNCTIONS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "stringify", "aria", "find", "click", "fill", "text", "domClick", "domFill", "domText", "dispatch", "tap",
            "drain", "defer", "collect")));

    /** {@code stringify} cuts its inner JSON text at this many UTF-16 code units (1 MiB). */
    public static final int MAX_JSON_CHARS = 1 << 20;

    /** The tap keeps the JSON text of an event's {@code detail} up to this many UTF-16 code units (64 KiB). */
    public static final int MAX_DETAIL_CHARS = 1 << 16;

    /** The tap buffers at most this many events between two drains; older ones are dropped and counted. */
    public static final int MAX_BUFFERED_EVENTS = 1000;

    /**
     * The guard of {@code web.waitFlag} ({@link #flagExpression}) and {@link #errorLine} cut an error's first line at
     * this many UTF-16 code units.
     */
    public static final int MAX_ERROR_CHARS = 2000;

    private static final String LIB = "window[" + SLOT + "]";

    /**
     * The guard of {@link #flagExpression} up to its argument, the expression as a JSON string. One function
     * expression: {@code m} turns a thrown value into the first line of its text ({@code "<name>: <message>"} for an
     * error object, {@code String(value)} otherwise; a line ends at CR, LF, U+2028 or U+2029), cut at
     * {@link #MAX_ERROR_CHARS} without splitting a surrogate pair; the expression is compiled on its own as the body
     * {@code return (<expression>\n)} of a {@code new Function}, then called with the window as {@code this}.
     */
    private static final String FLAG_GUARD = "(function(t){var f,m=function(e){var s,i,c;"
            + "try{s=e!==null&&typeof e===\"object\"&&typeof e.message===\"string\""
            + "?(typeof e.name===\"string\"&&e.name?e.name:\"Error\")+\": \"+e.message:String(e);}"
            + "catch(x){s=\"a value that cannot be converted to text was thrown\";}"
            + "for(i=0;i<s.length;i++){c=s.charCodeAt(i);if(c===10||c===13||c===8232||c===8233){s=s.slice(0,i);break;}}"
            + "if(s.length>" + MAX_ERROR_CHARS + "){s=s.slice(0,(s.charCodeAt(" + (MAX_ERROR_CHARS - 1)
            + ")&64512)===55296?" + (MAX_ERROR_CHARS - 1) + ":" + MAX_ERROR_CHARS + ");}return s;};"
            + "try{f=new Function(\"return (\"+t+\"\\n)\");}catch(e){return {ready:false,compileError:m(e)};}"
            + "try{return f.call(window)?{ready:true}:{ready:false};}catch(e){return {ready:false,error:m(e)};}})(";

    private static volatile String source;
    private static volatile String hash;

    private WebLib() {
    }

    /** The library's text (UTF-8), read once from the class path. */
    public static String source() {
        String s = source;
        if (s == null) {
            s = new String(bytes(), StandardCharsets.UTF_8);
            source = s;
        }
        return s;
    }

    /** Lower-case hexadecimal SHA-256 of the resource's bytes; stable across runs for one build. */
    public static String hash() {
        String h = hash;
        if (h == null) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes());
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                }
                h = sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
            }
            hash = h;
        }
        return h;
    }

    /**
     * An expression that installs the library when it is absent and evaluates to {@code "installed"} or
     * {@code "present"}. Idempotent: evaluating it again changes nothing, an armed tap included. The source is one
     * function expression invoked in place, so the parenthesised text is an expression wherever it is embedded.
     */
    public static String installExpression() {
        return "(" + source() + "\n)";
    }

    /**
     * An expression that installs the library when absent, then calls one of its functions with the given arguments
     * encoded as JSON ({@link Json#write}: {@code null}, strings, numbers, booleans, maps as objects, lists and arrays
     * as arrays) and evaluates to the function's return value, which the caller receives as JSON through its own
     * channel (a library error object included; see {@link #errorOf}). A {@code null} array (a lone {@code null}
     * passed as the varargs) is one {@code null} argument.
     *
     * @throws IllegalArgumentException if {@code function} is not one of {@link #FUNCTIONS}
     * @throws ai.skillroy.testjava.protocol.JsonException if an argument holds NaN, Infinity or a cycle
     */
    public static String callExpression(String function, Object... args) {
        return "(function(){" + source() + ";return " + invocation(function, args) + ";})()";
    }

    /**
     * As {@link #callExpression}, but the function's return value crosses as the JSON text of {@code {json,
     * typeofResult, truncated}} that {@code stringify} makes of it: for a channel that returns strings only (a
     * JxBrowser frame's {@code executeJavaScript}). A library error object arrives inside {@code json} and is read
     * with {@link #errorOf} once parsed. The library is embedded once, unlike {@code wrap(callExpression(...))}.
     *
     * @throws IllegalArgumentException if {@code function} is not one of {@link #FUNCTIONS}
     * @throws ai.skillroy.testjava.protocol.JsonException if an argument holds NaN, Infinity or a cycle
     */
    public static String callJsonExpression(String function, Object... args) {
        return "(function(){" + source() + ";return " + LIB + ".stringify(" + invocation(function, args) + ");})()";
    }

    /** {@code window[SLOT].function(<args as JSON>)}, after checking the name. */
    private static String invocation(String function, Object[] args) {
        if (function == null || !FUNCTIONS.contains(function)) {
            throw new IllegalArgumentException("not a function of the script library: " + function);
        }
        Object[] values = args == null ? new Object[] {null} : args;
        StringBuilder sb = new StringBuilder(LIB).append('.').append(function).append('(');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.write(values[i]));
        }
        return sb.append(')').toString();
    }

    /**
     * The wrapper of {@code web.evaluate} (phase4-contracts.md section 4.2): installs the library, evaluates the
     * caller's expression and returns the JSON text of {@code {json, typeofResult, truncated}} computed by the
     * library's {@code stringify}. With {@code awaitPromise} the expression's value is awaited first (an
     * {@code await} inside the expression is allowed too); the result is then a promise of that text and the caller
     * sets the channel's own await flag. The expression is embedded once, as written, in a non-strict function whose
     * {@code this} is the window; trailing semicolons and whitespace are dropped, so {@code "x;"} works, but
     * statements do not: it must be an expression.
     *
     * @throws IllegalArgumentException if {@code expression} is null or blank
     */
    public static String wrap(String expression, boolean awaitPromise) {
        String body = expressionBody(expression);
        if (awaitPromise) {
            return "(async function(){" + source() + ";return " + LIB + ".stringify(await (" + body + "\n));})()";
        }
        return "(function(){" + source() + ";return " + LIB + ".stringify((" + body + "\n));})()";
    }

    /**
     * For a channel that cannot await a promise (the {@code jxbrowser} adapter, phase4-contracts.md section 4.5): an
     * expression that evaluates the caller's expression as {@link #wrap} does, hands its value (a promise or not) to
     * the library and evaluates at once to an id (a string such as {@code "d7"}). The settled value waits in the
     * library, as the text {@code stringify} gives, until {@link #collectExpression} fetches it; at most 100 wait per
     * document, the oldest forgotten first. A synchronous throw still fails this evaluation itself.
     *
     * @throws IllegalArgumentException if {@code expression} is null or blank
     */
    public static String deferExpression(String expression) {
        return "(function(){" + source() + ";return " + LIB + ".defer((" + expressionBody(expression) + "\n));})()";
    }

    /**
     * An expression that evaluates to the JSON text of {@code {state, text?, message?, stack?}} for a
     * {@link #deferExpression} id: {@code state} is {@code "pending"}, {@code "fulfilled"} (with {@code text}, the
     * JSON text of {@code {json, typeofResult, truncated}}), {@code "rejected"} (with the reason's {@code message} and
     * {@code stack}) or {@code "unknown"} (never deferred here, already collected, forgotten, or the document was
     * replaced). A settled result is forgotten once collected.
     *
     * @throws IllegalArgumentException if {@code id} is null or empty
     */
    public static String collectExpression(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a deferred id is required");
        }
        return callExpression("collect", id);
    }

    /**
     * The page-side guard of {@code web.waitFlag} (phase4-contracts.md sections 4.2 and 16.1, amendment A35): one
     * poll of the flag, the same text on both web paths, so that both read the same shapes. The guard compiles the
     * caller's expression on its own, as the body {@code return (<expression>\n)} of a {@code new Function}, then
     * calls the compiled function with the window as {@code this}, and evaluates to one of:
     * <ul>
     *   <li>{@code {ready: true}}: the value is truthy;</li>
     *   <li>{@code {ready: false}}: the value is falsy;</li>
     *   <li>{@code {ready: false, error: <line>}}: the call threw (a poll that counts as not ready);</li>
     *   <li>{@code {ready: false, compileError: <line>}}: the expression did not compile (a {@code SyntaxError}, or
     *     the {@code EvalError} of a page whose content security policy forbids compiling text where the channel
     *     does not lift that rule), so it can never become ready.</li>
     * </ul>
     * {@code <line>} is the first line of the thrown value's text as {@link #errorLine} cuts it. The expression
     * travels as a JSON string, never as code of the guard, so the guard itself always compiles and never throws;
     * trailing semicolons and whitespace are dropped as {@link #wrap} drops them, and the newline before the closing
     * parenthesis ends a trailing line comment. The library is neither needed nor installed.
     *
     * @throws IllegalArgumentException if {@code expression} is null or blank
     */
    public static String flagExpression(String expression) {
        return FLAG_GUARD + Json.write(expressionBody(expression)) + ")";
    }

    /**
     * The first line of an error's text, cut as the guard of {@link #flagExpression} cuts it: up to the first CR,
     * LF, U+2028 or U+2029, at most {@link #MAX_ERROR_CHARS} UTF-16 code units, never inside a surrogate pair; ""
     * for null. For an error text that did not come from the guard (an adapter's own script error).
     */
    public static String errorLine(String text) {
        if (text == null) {
            return "";
        }
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == (char) 0x2028 || c == (char) 0x2029) {
                end = i;
                break;
            }
        }
        if (end > MAX_ERROR_CHARS) {
            end = Character.isHighSurrogate(text.charAt(MAX_ERROR_CHARS - 1)) ? MAX_ERROR_CHARS - 1 : MAX_ERROR_CHARS;
        }
        return text.substring(0, end);
    }

    /**
     * The source of an init script that installs the library in every new document and arms the tap there for the
     * given event-name patterns on {@code "window"} or {@code "document"} ({@code null} means {@code "window"}). An
     * exact name is heard by a capturing listener on the target (every event of that name whose path reaches it); a
     * pattern with {@code *} matches by a wrapper of the target's own {@code dispatchEvent} (the events dispatched on
     * the target itself). Evaluated in the current document as well, it arms the tap there too.
     *
     * @throws IllegalArgumentException if {@code patterns} is null or holds a null or empty pattern, or the target is
     *     neither {@code "window"} nor {@code "document"}
     */
    public static String tapInstaller(List<String> patterns, String target) {
        if (patterns == null) {
            throw new IllegalArgumentException("patterns are required");
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("a pattern must be a non-empty event name or glob: " + patterns);
            }
        }
        String on = target == null ? "window" : target;
        if (!"window".equals(on) && !"document".equals(on)) {
            throw new IllegalArgumentException("the tap's target must be \"window\" or \"document\": " + target);
        }
        return callExpression("tap", patterns, on);
    }

    /**
     * The RPC error for a library error object ({@code {__error: <name>, data: {…}}}, as the library's functions
     * return them), or {@code null} when {@code value} is not one. {@code NOT_FOUND}, {@code AMBIGUOUS} and
     * {@code INVALID_PARAMS} map to the {@link ErrorCodes} of those names with the object's {@code data}; any other
     * name maps to {@code INTERNAL_ERROR} with the name added as {@code data.error}. Both web paths use this, so
     * that a failed verb carries the same code, message and data on either path.
     */
    public static RpcError errorOf(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Object name = ((Map<?, ?>) value).get(ERROR_KEY);
        if (!(name instanceof String)) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        Object raw = ((Map<?, ?>) value).get("data");
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                data.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        String code = (String) name;
        if ("NOT_FOUND".equals(code)) {
            return new RpcError(ErrorCodes.NOT_FOUND, notFoundMessage(data), data);
        }
        if ("AMBIGUOUS".equals(code)) {
            Object count = data.get("count");
            return new RpcError(ErrorCodes.AMBIGUOUS, (count != null ? count + " elements" : "several elements")
                    + " match; give an index or a narrower locator", data);
        }
        if ("INVALID_PARAMS".equals(code)) {
            Object reason = data.get("reason");
            Object detail = data.get("message");
            String message = reason != null ? String.valueOf(reason) : "invalid parameters";
            return new RpcError(ErrorCodes.INVALID_PARAMS, detail != null ? message + ": " + detail : message, data);
        }
        data.put("error", code);
        return new RpcError(ErrorCodes.INTERNAL_ERROR, "script library error " + code, data);
    }

    private static String notFoundMessage(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("no element matches");
        if (data.containsKey("selector")) {
            sb.append(" the selector ").append(Json.write(data.get("selector")));
        } else if (data.containsKey("role")) {
            sb.append(" the role ").append(Json.write(data.get("role")));
            if (data.get("name") != null) {
                sb.append(" named ").append(Json.write(data.get("name")));
            }
        }
        if (data.containsKey("index")) {
            sb.append(" at index ").append(data.get("index")).append(" (").append(data.get("count"))
              .append(" matched)");
        }
        return sb.toString();
    }

    /** The expression without trailing semicolons and whitespace; refuses null and blank. */
    private static String expressionBody(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("expression is required");
        }
        int end = expression.length();
        while (end > 0 && (expression.charAt(end - 1) == ';' || Character.isWhitespace(expression.charAt(end - 1)))) {
            end--;
        }
        if (end == 0) {
            throw new IllegalArgumentException("expression is blank");
        }
        return expression.substring(0, end);
    }

    private static byte[] bytes() {
        ClassLoader loader = WebLib.class.getClassLoader();
        try (InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(RESOURCE)
                : loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }
}
