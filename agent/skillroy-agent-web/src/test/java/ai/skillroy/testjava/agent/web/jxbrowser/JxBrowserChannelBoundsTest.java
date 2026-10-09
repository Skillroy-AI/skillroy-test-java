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

package ai.skillroy.testjava.agent.web.jxbrowser;

import ai.skillroy.testjava.agent.api.spi.web.WebScriptException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security fix S43 in the JxBrowser adapter: a channel result over {@link WebLib#MAX_CHANNEL_CHARS} is refused before
 * it is parsed, and a script error's message and stack are cut.
 */
class JxBrowserChannelBoundsTest {

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void aResultOverTheChannelBoundIsRefusedUnparsed() throws Exception {
        CompletableFuture<String> out = new CompletableFuture<String>();
        JxBrowserHandle.decode("s" + repeat('x', WebLib.MAX_CHANNEL_CHARS), out);
        ExecutionException e = assertThrows(ExecutionException.class, out::get);
        assertTrue(e.getCause() instanceof RpcException, String.valueOf(e.getCause()));
        RpcException rpc = (RpcException) e.getCause();
        assertEquals(ErrorCodes.INTERNAL_ERROR, rpc.error().code());
        assertEquals("oversized", rpc.error().data().get("reason"));
        CompletableFuture<String> small = new CompletableFuture<String>();
        JxBrowserHandle.decode("sabc", small);
        assertEquals("\"abc\"", small.get());
    }

    @Test
    void aScriptErrorIsCut() {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("message", "Error: " + repeat('m', 10000) + "\nnext");
        error.put("stack", repeat('s', 100000));
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put("json", Json.write(error));
        CompletableFuture<String> out = new CompletableFuture<String>();
        JxBrowserHandle.decode("e" + Json.write(wrapper), out);
        ExecutionException e = assertThrows(ExecutionException.class, out::get);
        WebScriptException script = (WebScriptException) e.getCause();
        assertEquals(WebLib.MAX_ERROR_CHARS, script.getMessage().length());
        assertEquals(WebLib.MAX_STACK_CHARS, script.stack().length());
    }
}
