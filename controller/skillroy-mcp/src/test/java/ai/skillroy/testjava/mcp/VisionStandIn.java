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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.inference.session.SessionTools;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ProviderTool;
import ai.skillroy.testjava.inference.spi.ToolSpec;

import java.util.List;

/**
 * A stand-in for the vision module on the MCP module's test class path (a {@code SessionTools} of id {@code vision},
 * registered through {@code ServiceLoader}): what the server looks for before it declares a vision session's host
 * features (phase7-contracts.md 8.5, 9.4). It never applies.
 */
public final class VisionStandIn implements SessionTools {

    @Override
    public String id() {
        return "vision";
    }

    @Override
    public boolean applies(SessionToolContext ctx) {
        return false;
    }

    @Override
    public List<ToolSpec> tools(SessionToolContext ctx) {
        return List.of();
    }

    @Override
    public List<ProviderTool> providerTools(SessionToolContext ctx) {
        return List.of();
    }

    @Override
    public String instructions(SessionToolContext ctx) {
        return null;
    }

    @Override
    public boolean handles(String toolName) {
        return false;
    }

    @Override
    public List<Content> atTurnStart(SessionToolContext ctx) {
        return List.of();
    }

    @Override
    public Outcome act(Content.ToolUse use, SessionToolContext ctx) {
        return Outcome.of(new Content.ToolResult(use.id(), "not here", true));
    }

    @Override
    public List<Content> afterBatch(SessionToolContext ctx) {
        return List.of();
    }
}
