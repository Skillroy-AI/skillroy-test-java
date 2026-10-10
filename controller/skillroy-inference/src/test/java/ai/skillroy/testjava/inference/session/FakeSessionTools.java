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

package ai.skillroy.testjava.inference.session;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ProviderTool;
import ai.skillroy.testjava.inference.spi.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tool set for the session service's tests (phase7-contracts.md 13.1 item 7): two custom tools ({@code fake_click},
 * an acting tool, and {@code fake_look}, a read) and, when asked, the computer toolset's {@code left_click}; its
 * instructions hold a {@code key=value} secret the service must mask; its {@code atTurnStart} gives a text and an
 * image, its {@code afterBatch} a text; every call is answered by the test's {@link Handler}. Records what the service
 * asked of it.
 */
final class FakeSessionTools implements SessionTools {

    /** The instructions' secret, which the service masks. */
    static final String SECRET = "Probe-Instr-7731";

    /** One call's answer, written by the test. */
    @FunctionalInterface
    interface Handler {
        Outcome act(Content.ToolUse use, SessionToolContext ctx) throws AgentLostException, InterruptedException;
    }

    boolean applies = true;
    boolean toolset;
    Handler handler = (use, ctx) -> Outcome.of(new Content.ToolResult(use.id(), "ok", false));
    final List<String> asked = new ArrayList<>();
    int turnStarts;
    int batches;
    boolean closed;
    SessionToolContext seen;

    static final byte[] PNG = png(1024, 768);

    /** The first 33 bytes of a PNG of this size (what an image part's size is read from). */
    static byte[] png(int w, int h) {
        byte[] png = new byte[33];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        System.arraycopy(head, 0, png, 0, head.length);
        png[16] = (byte) (w >>> 24);
        png[17] = (byte) (w >>> 16);
        png[18] = (byte) (w >>> 8);
        png[19] = (byte) w;
        png[20] = (byte) (h >>> 24);
        png[21] = (byte) (h >>> 16);
        png[22] = (byte) (h >>> 8);
        png[23] = (byte) h;
        return png;
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public boolean applies(SessionToolContext ctx) {
        seen = ctx;
        return applies;
    }

    @Override
    public List<ToolSpec> tools(SessionToolContext ctx) {
        Map<String, Object> empty = Map.of("type", "object", "properties", Map.of(), "required", List.of(),
                "additionalProperties", false);
        return List.of(new ToolSpec("fake_click", "clicks the thing; token=" + SECRET, empty, false, false),
                new ToolSpec("fake_look", "looks", empty, true, false));
    }

    @Override
    public List<ProviderTool> providerTools(SessionToolContext ctx) {
        return toolset ? List.of(ProviderTool.computerToolset(false)) : List.of();
    }

    @Override
    public String instructions(SessionToolContext ctx) {
        return "Fake instructions: look before you click; secret=" + SECRET;
    }

    @Override
    public boolean handles(String toolName) {
        return toolName.equals("fake_click") || toolName.equals("fake_look") || toolset && toolName.equals("left_click");
    }

    @Override
    public List<Content> atTurnStart(SessionToolContext ctx) {
        turnStarts++;
        return List.of(Content.Text.of("<target-ui>\nmark 1: button fake\n</target-ui>"),
                new Content.Image("image/png", PNG, null));
    }

    @Override
    public Outcome act(Content.ToolUse use, SessionToolContext ctx) throws AgentLostException, InterruptedException {
        asked.add(use.name());
        return handler.act(use, ctx);
    }

    @Override
    public List<Content> afterBatch(SessionToolContext ctx) {
        batches++;
        return List.of(Content.Text.of("after the batch " + batches));
    }

    @Override
    public void close() {
        closed = true;
    }
}
