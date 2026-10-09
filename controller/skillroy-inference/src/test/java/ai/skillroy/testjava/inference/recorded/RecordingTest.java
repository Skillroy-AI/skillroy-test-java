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

package ai.skillroy.testjava.inference.recorded;

import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix S49: a recording's {@code request.tools} go through the mask as its other parts do: a tool's
 * description and input schema, a secret-looking property losing its {@code default}; the fingerprint stays the
 * request's as sent.
 */
class RecordingTest {

    @Test
    void aRecordingsToolsAreMasked() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("password", Map.of("type",
                "string", "default", "Rec-Default-3307")), "required", List.of("password"),
                "additionalProperties", false);
        ToolSpec tool = new ToolSpec("lending_sign_on", "signs on; token=Rec-Tok-5521", schema, false, false);
        ModelRequest request = ModelRequest.builder("session", ModelClass.SONNET_CLASS).effort(Effort.MEDIUM)
                .maxTokens(1024).message(Message.user("sign on")).tools(List.of(tool)).build();
        Map<String, Object> json = Recording.json(request, ScriptedGateway.text("done"), PromptMask.keysOnly(),
                Instant.parse("2026-10-09T00:00:00Z"));
        String text = Json.write(json);
        assertFalse(text.contains("Rec-Default-3307"), text);
        assertFalse(text.contains("Rec-Tok-5521"), text);
        assertTrue(text.contains("\"password\""), "the property's name stays: " + text);
        assertEquals(request.fingerprint(), json.get("fingerprint"), "the fingerprint is the request's as sent");
    }
}
