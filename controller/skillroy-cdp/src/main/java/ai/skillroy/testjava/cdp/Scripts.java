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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.web.WebLib;

/**
 * The scripts the CDP path evaluates through Playwright: the shared library's calls ({@link WebLib#callExpression})
 * and the {@code web.evaluate} wrapper ({@link WebLib#wrap}) inside a guard that turns a thrown script error into a
 * value, so that a script error is told apart from a failure of Playwright or of the protocol without parsing
 * Playwright's messages.
 *
 * <p>A guarded script evaluates to {@code {ok: <value>}} or to {@code {__scriptError: {message, stack}}}, where
 * {@code message} is {@code "<name>: <message>"} for a thrown error object (the first line of Chromium's exception
 * description, for example {@code "Error: boom"} or {@code "SyntaxError: Unexpected token ')'"}) and the value's text
 * otherwise, capped at 2000 characters, and {@code stack} is the error's {@code stack} or "" (capped at 8000).
 *
 * <p>The caller's {@code web.evaluate} text is compiled at run time by an indirect {@code eval} of the exact text of
 * {@link WebLib#wrap}, so that a syntax error in it is a script error too and the wrapper's own semantics ({@code
 * await} inside an awaited expression, global scope) stay exactly those of the agent path, which evaluates the same
 * text with {@code Runtime.evaluate}. Playwright itself evaluates every expression with {@code eval}, so this adds no
 * new requirement on a page's content security policy.
 *
 * <p>Stateless.
 */
final class Scripts {

    /** Turns a thrown value into {@code {message, stack}}; never throws. */
    static final String ERROR_FUNCTION = "function(e){var m,s=\"\";"
            + "try{m=(e!==null&&typeof e===\"object\"&&typeof e.message===\"string\")"
            + "?((typeof e.name===\"string\"&&e.name?e.name:\"Error\")+\": \"+e.message):String(e);}"
            + "catch(x){m=\"a value that cannot be converted to text was thrown\";}"
            + "try{if(e!==null&&typeof e===\"object\"&&e.stack){s=String(e.stack);}}catch(x){}"
            + "return {message:m.length>2000?m.slice(0,2000):m,stack:s.length>8000?s.slice(0,8000):s};}";

    private Scripts() {
    }

    /** A guarded call of a library function with JSON-encoded arguments (see {@link WebLib#callExpression}). */
    static String call(String function, Object... args) {
        return "(function(){var E=" + ERROR_FUNCTION + ";try{return {ok:(" + WebLib.callExpression(function, args)
                + ")};}catch(e){return {__scriptError:E(e)};}})()";
    }

    /**
     * The guarded {@code web.evaluate} wrapper: evaluates to {@code {ok: <the wrapper's JSON text>}}; with {@code
     * awaitPromise} to a promise of it (Playwright awaits a returned promise).
     */
    static String evaluate(String expression, boolean awaitPromise) {
        String wrapper = Json.write(WebLib.wrap(expression, awaitPromise));
        if (awaitPromise) {
            return "(async function(){var E=" + ERROR_FUNCTION + ";try{return {ok:await (0,eval)(" + wrapper
                    + ")};}catch(e){return {__scriptError:E(e)};}})()";
        }
        return "(function(){var E=" + ERROR_FUNCTION + ";try{return {ok:(0,eval)(" + wrapper
                + ")};}catch(e){return {__scriptError:E(e)};}})()";
    }

    /**
     * The predicate of {@code web.waitFlag} for {@code waitForFunction}: {@code true} when the caller's expression is
     * truthy, {@code false} when it is not (Playwright polls again), {@code {__scriptError}} (truthy, so the wait ends)
     * when it throws. The expression is compiled at run time like {@link #evaluate}'s, in parentheses, so that a
     * syntax error is a script error and an object literal stays an expression.
     */
    static String flag(String expression) {
        String source = Json.write("(" + expression + "\n)");
        return "(function(){var E=" + ERROR_FUNCTION + ";try{return (0,eval)(" + source
                + ")?true:false;}catch(e){return {__scriptError:E(e)};}})()";
    }

    /** {@code ({w: innerWidth, h: innerHeight})} of the page's window. */
    static String innerSize() {
        return "({w:window.innerWidth,h:window.innerHeight})";
    }
}
