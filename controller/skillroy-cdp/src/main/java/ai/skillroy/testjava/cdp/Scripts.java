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
 * <p>{@code web.waitFlag} is the exception (amendment A35): its polls are the shared guard of
 * {@link WebLib#flagExpression}, which turns what the flag's expression throws into a value of its own ({@code
 * {ready: false, error}}, not ready) and tells an expression that does not compile apart ({@code {ready: false,
 * compileError}}), so that both paths read the same shapes; {@link #flagWait} only adapts it to
 * {@code waitForFunction}'s truthiness.
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
     * The predicate of {@code web.waitFlag} for {@code waitForFunction} (amendment A35): one poll of the shared guard
     * ({@link WebLib#flagExpression}, the text the agent path evaluates), whose result object is returned when the
     * flag is ready ({@code {ready: true}}) or the expression did not compile ({@code {ready: false, compileError}});
     * both are truthy, so the wait ends. A falsy flag and a poll that threw ({@code {ready: false, error?}}) give
     * {@code false}, so Playwright polls again; the last poll's error is read after a timed-out wait with one more
     * poll of the guard itself.
     *
     * @throws IllegalArgumentException if {@code expression} is null or blank
     */
    static String flagWait(String expression) {
        return "(function(){var r=" + WebLib.flagExpression(expression)
                + ";return r.ready===true||r.compileError!==undefined?r:false;})()";
    }

    /** {@code ({w: innerWidth, h: innerHeight})} of the page's window. */
    static String innerSize() {
        return "({w:window.innerWidth,h:window.innerHeight})";
    }
}
