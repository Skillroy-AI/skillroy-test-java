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

import ai.skillroy.testjava.agent.web.jxbrowser.JxBrowserAdapter;
import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;
import ai.skillroy.testjava.agent.web.kit.KitScripts;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.web.WebLib;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fakes' table-driven evaluator: no JavaScript engine, a fixed set of expressions recognised by their text and
 * evaluated over a {@link FakeDocument}. It recognises:
 * <ul>
 *   <li>the script library's expressions as {@link WebLib} builds them: {@code installExpression()};
 *       {@code wrap(expression, awaitPromise)} (the inner expression is extracted and evaluated, then stringified;
 *       awaited, it gives a {@link Promise}); {@code callExpression(function, args...)} and
 *       {@code callJsonExpression(...)} (the function name and the JSON arguments are parsed);
 *       {@code boundedCallExpression(...)} (P2-L4-F2: the guard around the call, its rule applied here);
 *       {@code deferExpression(expression)} and {@code collectExpression(id)}; {@code flagExpression(expression)},
 *       the guard of {@code web.waitFlag} (A35): an expression whose brackets do not balance, or that ends in an
 *       operator, does not "compile" ({@code {ready: false, compileError}}), any other is evaluated through this
 *       table, a {@link Thrown} giving {@code {ready: false, error}} cut by {@link WebLib#errorLine}, a value
 *       {@code {ready: <truthy>}};</li>
 *   <li>the {@code jxbrowser} adapter's tagged wrapper ({@link JxBrowserAdapter#wrap}), by its fixed opening and
 *       closing text;</li>
 *   <li>the kit's expressions ({@link KitScripts}), and a few of the module's: {@code document.title},
 *       {@code location.href}, {@code [window.innerWidth, window.innerHeight]} (also as the {@code jcef} adapter's
 *       promise of the restored size), {@code location.reload(...)}, {@code <n> * <m>},
 *       numbers, quoted strings, {@code undefined}, {@code null}, {@code true}, {@code false},
 *       {@code console.log('text')}, {@code window.flag === true} for a global named {@code flag}, and
 *       {@code window.flag.key === true}, which throws "TypeError: Cannot read properties of undefined (reading
 *       'key')" while the global is absent (a page that is not ready yet);</li>
 *   <li>the library functions the fakes serve: {@code stringify}, {@code tap}, {@code drain}, {@code dispatch},
 *       {@code domText} (body or {@code #id}), {@code aria} (the title as a heading and the body's text),
 *       {@code domClick} and {@code domFill} on {@code #id} selectors present in the HTML, {@code defer},
 *       {@code collect}.</li>
 * </ul>
 * Anything else throws a {@link Thrown} "ReferenceError: the fake cannot evaluate ...", so that a test shows at once
 * what it sent. Values are Java objects: null, {@link #UNDEFINED}, Boolean, Long, Double, String, List, Map,
 * {@link Promise}.
 */
public final class FakeScript {

    static {
        ClassInitialisations.record(FakeScript.class);
    }

    /** JavaScript's {@code undefined}. */
    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    private static final String SLOT_EXPR = "window[" + WebLib.SLOT + "]";
    private static final Pattern PRODUCT = Pattern.compile("^(-?\\d+) \\* (-?\\d+)$");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern QUOTED = Pattern.compile("^'([^']*)'$|^\"([^\"]*)\"$");
    private static final Pattern CONSOLE_LOG = Pattern.compile("^console\\.log\\('([^']*)'\\)$");
    private static final Pattern FLAG = Pattern.compile("^window\\.([A-Za-z_$][A-Za-z0-9_$]*) === true$");
    private static final Pattern PROPERTY_FLAG = Pattern.compile(
            "^window\\.([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*) === true$");
    private static final Pattern ID_SELECTOR = Pattern.compile("^#([A-Za-z][A-Za-z0-9_-]*)$");
    /** The {@code jcef} adapter's read of the restored viewport after {@code clearResize} (a promise of the size). */
    private static final Pattern RESTORED_SIZE = Pattern.compile("^\\(function \\(w, h\\) \\{ return new Promise\\("
            + "function \\(resolve\\) \\{.*\\}\\)\\(-?\\d+, -?\\d+\\)$", Pattern.DOTALL);
    private static final long PROMISE_MS = 30;

    private FakeScript() {
    }

    /** A thrown JavaScript error. */
    public static final class Thrown extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String stack;

        public Thrown(String message, String stack) {
            super(message);
            this.stack = stack;
        }

        public String stack() {
            return stack;
        }
    }

    /** A promise: settles at a time, with a value or an error. */
    public static final class Promise {
        final long settleAtNanos;
        final Object value;
        final Thrown error;

        Promise(long delayMs, Object value, Thrown error) {
            this.settleAtNanos = System.nanoTime() + delayMs * 1000000L;
            this.value = value;
            this.error = error;
        }

        public boolean settled() {
            return System.nanoTime() - settleAtNanos >= 0;
        }

        public long remainingMs() {
            return Math.max(0, (settleAtNanos - System.nanoTime()) / 1000000L);
        }

        public Object value() {
            return value;
        }

        public Thrown error() {
            return error;
        }

        /** A promise of {@code f(value)} settling at the same time. */
        Promise map(java.util.function.Function<Object, Object> f) {
            return new Promise(remainingMs(), error == null ? f.apply(value) : null, error);
        }
    }

    /** Evaluates one expression in the document. */
    public static Object evaluate(FakeDocument doc, String expression) {
        synchronized (doc) {
            return eval(doc, expression.trim());
        }
    }

    private static Object eval(FakeDocument doc, String e) {
        String src = WebLib.source();
        if (e.equals(WebLib.installExpression())) {
            return install(doc);
        }
        if (e.startsWith(WebLib.boundedOpen()) && e.endsWith(WebLib.boundedClose())) {
            return bounded(doc, e.substring(WebLib.boundedOpen().length(), e.length()
                    - WebLib.boundedClose().length()).trim());
        }
        String flagPrefix = flagPrefix();
        if (e.startsWith(flagPrefix) && e.endsWith(")")) {
            return flag(doc, (String) Json.parse(e.substring(flagPrefix.length(), e.length() - 1)));
        }
        String jxPrefix = jxPrefix();
        if (e.startsWith(jxPrefix) && e.endsWith(JxBrowserAdapter.EXPRESSION_CLOSE)) {
            String inner = e.substring(jxPrefix.length(), e.length() - JxBrowserAdapter.EXPRESSION_CLOSE.length());
            install(doc);
            try {
                Object v = eval(doc, inner.trim());
                if (v instanceof String) {
                    return "s" + v;
                }
                return "j" + stringify(v);
            } catch (Thrown t) {
                Map<String, Object> error = new LinkedHashMap<String, Object>();
                error.put("message", t.getMessage());
                error.put("stack", t.stack());
                return "e" + stringify(error);
            }
        }
        String asyncPrefix = "(async function(){" + src + ";return " + SLOT_EXPR + ".stringify(await (";
        if (e.startsWith(asyncPrefix) && e.endsWith("\n));})()")) {
            install(doc);
            Object v = eval(doc, e.substring(asyncPrefix.length(), e.length() - "\n));})()".length()).trim());
            if (v instanceof Promise) {
                return ((Promise) v).map(new java.util.function.Function<Object, Object>() {
                    @Override
                    public Object apply(Object value) {
                        return stringify(value);
                    }
                });
            }
            return new Promise(0, stringify(v), null);
        }
        String fnPrefix = "(function(){" + src + ";return " + SLOT_EXPR + ".";
        if (e.startsWith(fnPrefix)) {
            install(doc);
            String rest = e.substring(fnPrefix.length());
            if (rest.startsWith("stringify((") && rest.endsWith("\n));})()")) {
                return stringify(eval(doc, rest.substring("stringify((".length(), rest.length()
                        - "\n));})()".length()).trim()));
            }
            if (rest.startsWith("defer((") && rest.endsWith("\n));})()")) {
                Object v = eval(doc, rest.substring("defer((".length(), rest.length() - "\n));})()".length()).trim());
                return defer(doc, v);
            }
            String jsonCall = "stringify(" + SLOT_EXPR + ".";
            if (rest.startsWith(jsonCall) && rest.endsWith(");})()")) {
                String call = rest.substring(jsonCall.length(), rest.length() - ");})()".length());
                return stringify(callText(doc, call));
            }
            if (rest.endsWith(";})()")) {
                return callText(doc, rest.substring(0, rest.length() - ";})()".length()));
            }
        }
        return plain(doc, e);
    }

    /**
     * The guard of {@link WebLib#boundedCallExpression} (P2-L4-F2) around a library call: the call's {@code stringify}
     * text when it is a string of at most {@link WebLib#MAX_JSON_CHARS}, else a {@link WebLib#GUARD_PREFIX} text; a
     * {@link Thrown} becomes {@code "#skillroy-guard:threw:<n>:<message><stack>"}, as the guard's own text does in a
     * browser.
     */
    private static Object bounded(FakeDocument doc, String call) {
        Object t;
        try {
            t = eval(doc, call);
        } catch (Thrown thrown) {
            return guardThrew(thrown.getMessage(), thrown.stack());
        }
        if (!(t instanceof String)) {
            return WebLib.GUARD_PREFIX + "not text";
        }
        return ((String) t).length() > WebLib.MAX_JSON_CHARS ? WebLib.GUARD_PREFIX + "oversized" : t;
    }

    /** The guard's text of a thrown value: its message and stack cut at their bounds (P2-L4-F2). */
    public static String guardThrew(String message, String stack) {
        String m = message == null ? "" : message;
        String s = stack == null ? "" : stack;
        m = m.length() > WebLib.MAX_ERROR_CHARS ? m.substring(0, WebLib.MAX_ERROR_CHARS) : m;
        s = s.length() > WebLib.MAX_STACK_CHARS ? s.substring(0, WebLib.MAX_STACK_CHARS) : s;
        return WebLib.GUARD_PREFIX + "threw:" + m.length() + ":" + m + s;
    }

    /** {@code fn(args)} of a library call. */
    private static Object callText(FakeDocument doc, String call) {
        int open = call.indexOf('(');
        if (open < 0 || !call.endsWith(")")) {
            throw unknown(call);
        }
        String fn = call.substring(0, open);
        String args = call.substring(open + 1, call.length() - 1);
        List<?> list = (List<?>) Json.parse("[" + args + "]");
        return library(doc, fn, list);
    }

    private static String jxPrefix() {
        String probe = JxBrowserAdapter.wrap("X");
        return probe.substring(0, probe.length() - ("X" + JxBrowserAdapter.EXPRESSION_CLOSE).length());
    }

    /** The guard of {@link WebLib#flagExpression} up to its argument, the expression as a JSON string. */
    private static String flagPrefix() {
        String probe = WebLib.flagExpression("X");
        return probe.substring(0, probe.length() - (Json.write("X") + ")").length());
    }

    /** One poll of the guard of {@code web.waitFlag} (A35), with the fake's notion of compiling. */
    private static Map<String, Object> flag(FakeDocument doc, String expression) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String compileError = compileError(expression);
        if (compileError != null) {
            out.put("ready", Boolean.FALSE);
            out.put("compileError", compileError);
            return out;
        }
        try {
            out.put("ready", Boolean.valueOf(truthy(eval(doc, expression.trim()))));
        } catch (Thrown t) {
            out.put("ready", Boolean.FALSE);
            out.put("error", WebLib.errorLine(t.getMessage()));
        }
        return out;
    }

    /**
     * The fake's compiler: brackets outside quotes must balance and the text must not end in an operator; null when
     * it "compiles", else the {@code SyntaxError} line.
     */
    private static String compileError(String expression) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth < 0) {
                    return "SyntaxError: Unexpected token '" + c + "'";
                }
            }
        }
        String trimmed = expression.trim();
        char last = trimmed.isEmpty() ? ' ' : trimmed.charAt(trimmed.length() - 1);
        if (depth != 0 || quote != 0 || "=+-*/&|<>!?:.,".indexOf(last) >= 0) {
            return "SyntaxError: Unexpected token '}'";
        }
        return null;
    }

    private static Object install(FakeDocument doc) {
        if (doc.libraryInstalled) {
            return "present";
        }
        doc.libraryInstalled = true;
        doc.hidden.put("Symbol(skillroy)", "library");
        return "installed";
    }

    // ------------------------------------------------------------------------------------------------ plain

    private static Object plain(FakeDocument doc, String e) {
        if (KitScripts.NUMBER.equals(e)) {
            return Long.valueOf(42);
        }
        if (KitScripts.STRING.equals(e)) {
            return "kit-string";
        }
        if (KitScripts.OBJECT.equals(e)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("a", Long.valueOf(1));
            m.put("b", Arrays.asList(Boolean.TRUE, null));
            m.put("c", "x");
            return m;
        }
        if (KitScripts.UNDEFINED.equals(e) || "undefined".equals(e)) {
            return UNDEFINED;
        }
        if (KitScripts.THROWS.equals(e)) {
            throw new Thrown("Error: kit boom", "Error: kit boom\n    at <anonymous>:1:22");
        }
        if (KitScripts.PROMISE.equals(e)) {
            return new Promise(PROMISE_MS, Long.valueOf(7), null);
        }
        if (KitScripts.BIG_STRING.equals(e)) {
            char[] x = new char[2 * 1024 * 1024];
            Arrays.fill(x, 'x');
            return new String(x);
        }
        if (KitScripts.TITLE.equals(e)) {
            return doc.title;
        }
        if (KitScripts.INNER_SIZE.equals(e)) {
            int[] size = doc.host.innerSize();
            return size[0] + "x" + size[1];
        }
        if ("[window.innerWidth, window.innerHeight]".equals(e)) {
            int[] size = doc.host.innerSize();
            return Arrays.asList(Long.valueOf(size[0]), Long.valueOf(size[1]));
        }
        if (RESTORED_SIZE.matcher(e).matches()) {
            int[] size = doc.host.innerSize();
            return new Promise(0, Arrays.asList(Long.valueOf(size[0]), Long.valueOf(size[1])), null);
        }
        if (KitScripts.WINDOW_KEYS.equals(e)) {
            StringBuilder sb = new StringBuilder();
            for (String k : doc.globals.keySet()) {
                sb.append(sb.length() == 0 ? "" : ",").append(k);
            }
            return sb.toString();
        }
        if (KitScripts.INIT_SOURCE.equals(e)) {
            doc.hidden.put("__kitInit", "ran");
            return UNDEFINED;
        }
        if (e.startsWith("Object.defineProperty(window, '__kitInit2'")) {
            doc.hidden.put("__kitInit2", "ran");
            return UNDEFINED;
        }
        if (KitScripts.INIT_NOW.equals(e)) {
            return doc.hidden.containsKey("__kitInit") ? "string" : "undefined";
        }
        if ("typeof window.__kitInit2".equals(e)) {
            return doc.hidden.containsKey("__kitInit2") ? "string" : "undefined";
        }
        if (KitScripts.INIT_AT_PARSE.equals(e)) {
            return doc.initAtParse;
        }
        if (KitScripts.DOCUMENT_MARK.equals(e)) {
            doc.hidden.put("__kitMark", "set");
            return "set";
        }
        if (KitScripts.REPLACED_LIBRARY_BIG.equals(e) || KitScripts.REPLACED_LIBRARY_THROWS.equals(e)) {
            doc.replacedLibrary = KitScripts.REPLACED_LIBRARY_BIG.equals(e) ? "big" : "throws";
            return "replaced";
        }
        if (KitScripts.RESTORE_LIBRARY.equals(e)) {
            doc.replacedLibrary = null;
            return "restored";
        }
        if (KitScripts.CONSOLE_FLOOD.equals(e)) {
            char[] chars = new char[1 << 20];
            Arrays.fill(chars, 'x');
            String s = new String(chars);
            for (int i = 0; i < 24; i++) {
                doc.host.console(doc, "log", s);
            }
            return Long.valueOf(24);
        }
        if (KitScripts.DOCUMENT_MARK_NOW.equals(e)) {
            return doc.hidden.containsKey("__kitMark") ? "string" : "undefined";
        }
        if ("location.href".equals(e)) {
            return doc.url;
        }
        if (e.startsWith("location.reload(")) {
            doc.host.reload(e.contains("true"));
            return UNDEFINED;
        }
        if ("null".equals(e)) {
            return null;
        }
        if ("true".equals(e) || "false".equals(e)) {
            return Boolean.valueOf(e);
        }
        Matcher m = PRODUCT.matcher(e);
        if (m.matches()) {
            return Long.valueOf(Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)));
        }
        if (NUMBER.matcher(e).matches()) {
            return e.contains(".") ? (Object) Double.valueOf(e) : (Object) Long.valueOf(e);
        }
        m = QUOTED.matcher(e);
        if (m.matches()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        m = CONSOLE_LOG.matcher(e);
        if (m.matches()) {
            doc.host.console(doc, "log", m.group(1));
            return UNDEFINED;
        }
        m = FLAG.matcher(e);
        if (m.matches()) {
            return Boolean.valueOf(Boolean.TRUE.equals(doc.globals.get(m.group(1))));
        }
        m = PROPERTY_FLAG.matcher(e);
        if (m.matches()) {
            Object global = doc.globals.get(m.group(1));
            if (global == null) {
                throw new Thrown("TypeError: Cannot read properties of " + (doc.globals.containsKey(m.group(1))
                        ? "null" : "undefined") + " (reading '" + m.group(2) + "')",
                        "TypeError: Cannot read properties of undefined\n    at <anonymous>:1:" + (m.end(1) + 1));
            }
            return Boolean.valueOf(global instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) global).get(m.group(2))));
        }
        throw unknown(e);
    }

    private static Thrown unknown(String e) {
        String shown = e.length() > 80 ? e.substring(0, 80) + "..." : e;
        return new Thrown("ReferenceError: the fake cannot evaluate " + shown, null);
    }

    static boolean truthy(Object v) {
        if (v == null || v == UNDEFINED) {
            return false;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0;
        }
        if (v instanceof String) {
            return !((String) v).isEmpty();
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ library

    /** The JSON text of {@code {json, typeofResult, truncated}}, as the library's stringify makes it. */
    public static String stringify(Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String json = Json.write(plainJson(value));
        boolean truncated = json.length() > WebLib.MAX_JSON_CHARS;
        out.put("json", truncated ? json.substring(0, WebLib.MAX_JSON_CHARS) : json);
        out.put("typeofResult", typeOf(value));
        out.put("truncated", Boolean.valueOf(truncated));
        return Json.write(out);
    }

    private static Object plainJson(Object value) {
        if (value == UNDEFINED) {
            return null;
        }
        if (value instanceof Promise) {
            return new LinkedHashMap<String, Object>();
        }
        return value;
    }

    private static String typeOf(Object v) {
        if (v == UNDEFINED) {
            return "undefined";
        }
        if (v == null || v instanceof Map || v instanceof List || v instanceof Promise) {
            return "object";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Number) {
            return "number";
        }
        return "string";
    }

    private static Object library(FakeDocument doc, String fn, List<?> args) {
        Object a0 = args.isEmpty() ? null : args.get(0);
        if ("stringify".equals(fn)) {
            return stringify(a0);
        }
        if ("tap".equals(fn)) {
            List<String> patterns = new ArrayList<String>();
            if (a0 instanceof List) {
                for (Object p : (List<?>) a0) {
                    patterns.add(String.valueOf(p));
                }
            }
            doc.tapPatterns = patterns;
            doc.tapTarget = args.size() > 1 && args.get(1) != null ? String.valueOf(args.get(1)) : "window";
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("installed", Long.valueOf(new LinkedHashSet<String>(patterns).size()));
            return out;
        }
        if ("drain".equals(fn) && "big".equals(doc.replacedLibrary)) {
            char[] chars = new char[WebLib.MAX_JSON_CHARS + 1];
            Arrays.fill(chars, 'x');
            return new String(chars); // a replaced library's drain (item 13); 50 Mi in a browser, past the bound here
        }
        if ("drain".equals(fn) && "throws".equals(doc.replacedLibrary)) {
            char[] chars = new char[1 << 20];
            Arrays.fill(chars, 'm');
            throw new Thrown("Error: " + new String(chars), "Error: " + new String(chars) + "\n    at drain");
        }
        if ("drain".equals(fn)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            List<Object> events = new ArrayList<Object>();
            for (Iterator<Map<String, Object>> it = doc.tapBuffer.iterator(); it.hasNext(); ) {
                events.add(it.next());
                it.remove();
            }
            out.put("events", events);
            out.put("dropped", Long.valueOf(doc.tapDropped));
            doc.tapDropped = 0;
            return out;
        }
        if ("dispatch".equals(fn)) {
            Map<?, ?> o = a0 instanceof Map ? (Map<?, ?>) a0 : new LinkedHashMap<Object, Object>();
            record(doc, String.valueOf(o.get("name")), o.get("detail"));
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("dispatched", Boolean.TRUE);
            return out;
        }
        if ("domText".equals(fn)) {
            Map<?, ?> o = a0 instanceof Map ? (Map<?, ?>) a0 : new LinkedHashMap<Object, Object>();
            String selector = o.get("selector") == null ? "body" : String.valueOf(o.get("selector"));
            String text = "body".equals(selector) ? doc.bodyText : elementText(doc, selector);
            if (text == null) {
                return notFound("selector", selector);
            }
            long max = o.get("maxChars") instanceof Number ? ((Number) o.get("maxChars")).longValue() : 10000;
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            boolean cut = text.length() > max;
            out.put("text", cut ? text.substring(0, (int) max) : text);
            out.put("truncated", Boolean.valueOf(cut));
            return out;
        }
        if ("aria".equals(fn)) {
            List<Object> nodes = new ArrayList<Object>();
            Map<String, Object> heading = new LinkedHashMap<String, Object>();
            heading.put("role", "heading");
            heading.put("name", doc.title);
            heading.put("level", Long.valueOf(1));
            nodes.add(heading);
            Map<String, Object> text = new LinkedHashMap<String, Object>();
            text.put("role", "text");
            text.put("name", doc.bodyText);
            nodes.add(text);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("nodes", nodes);
            out.put("truncated", Boolean.FALSE);
            return out;
        }
        if ("domClick".equals(fn) || "domFill".equals(fn)) {
            Map<?, ?> o = a0 instanceof Map ? (Map<?, ?>) a0 : new LinkedHashMap<Object, Object>();
            Object selector = o.get("selector");
            Matcher m = selector == null ? null : ID_SELECTOR.matcher(String.valueOf(selector));
            if (m == null || !m.matches() || !doc.html.contains("id=\"" + m.group(1) + "\"")) {
                return selector == null ? notFound("role", o.get("role")) : notFound("selector", selector);
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("matched", Long.valueOf(1));
            Map<String, Object> element = new LinkedHashMap<String, Object>();
            if ("domClick".equals(fn)) {
                element.put("role", "button");
                element.put("name", m.group(1));
                element.put("tag", "button");
                element.put("id", m.group(1));
                out.put("clicked", element);
                out.put("fidelity", "DOM");
            } else {
                element.put("tag", "input");
                element.put("type", "text");
                element.put("id", m.group(1));
                out.put("filled", element);
                out.put("typed", Long.valueOf(String.valueOf(o.get("text")).length()));
            }
            return out;
        }
        if ("defer".equals(fn)) {
            return defer(doc, a0);
        }
        if ("collect".equals(fn)) {
            return collect(doc, String.valueOf(a0));
        }
        throw new Thrown("TypeError: the fake library has no " + fn, null);
    }

    private static String elementText(FakeDocument doc, String selector) {
        Matcher m = ID_SELECTOR.matcher(selector);
        if (!m.matches()) {
            return null;
        }
        Matcher el = Pattern.compile("id=\"" + Pattern.quote(m.group(1)) + "\"[^>]*>([^<]*)<").matcher(doc.html);
        return el.find() ? el.group(1).trim() : null;
    }

    private static Map<String, Object> notFound(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put(key, value);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(WebLib.ERROR_KEY, "NOT_FOUND");
        out.put("data", data);
        return out;
    }

    /** Records a page event into the tap's buffer when a pattern matches its name. */
    static void record(FakeDocument doc, String name, Object detail) {
        boolean match = false;
        for (String p : doc.tapPatterns) {
            String regex = "^" + Pattern.quote(p).replace("*", "\\E.*\\Q") + "$";
            if (name.matches(regex)) {
                match = true;
            }
        }
        if (!match) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("name", name);
        if (detail != null && detail != UNDEFINED) {
            event.put("detail", detail);
        }
        doc.tapBuffer.add(event);
        while (doc.tapBuffer.size() > WebLib.MAX_BUFFERED_EVENTS) {
            doc.tapBuffer.poll();
            doc.tapDropped++;
        }
    }

    private static String defer(FakeDocument doc, Object value) {
        String id = "d" + (++doc.nextDefer);
        doc.deferred.put(id, value);
        return id;
    }

    /** The JSON text of {@code {state, text?, message?, stack?}}. */
    private static String collect(FakeDocument doc, String id) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!doc.deferred.containsKey(id)) {
            out.put("state", "unknown");
            return Json.write(out);
        }
        Object v = doc.deferred.get(id);
        if (v instanceof Promise) {
            Promise p = (Promise) v;
            if (!p.settled()) {
                out.put("state", "pending");
                return Json.write(out);
            }
            doc.deferred.remove(id);
            if (p.error() != null) {
                out.put("state", "rejected");
                out.put("message", p.error().getMessage());
                out.put("stack", p.error().stack());
            } else {
                out.put("state", "fulfilled");
                out.put("text", stringify(p.value()));
            }
            return Json.write(out);
        }
        doc.deferred.remove(id);
        out.put("state", "fulfilled");
        out.put("text", stringify(v));
        return Json.write(out);
    }
}
