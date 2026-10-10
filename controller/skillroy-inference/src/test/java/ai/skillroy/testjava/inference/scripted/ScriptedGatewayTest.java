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

package ai.skillroy.testjava.inference.scripted;

import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 7.3 and 13.1 item 7 (D321): the scripted gateway declares {@code IMAGES} and
 * {@code PROVIDER_TOOLS}, so that the vision loop's CI route (12.4) runs on it, and its token count counts an image by
 * the formula of 7.5.
 */
class ScriptedGatewayTest {

    @Test
    void itDeclaresImagesAndProviderTools() {
        try (ScriptedGateway gateway = ScriptedGateway.sequence(java.util.List.of())) {
            assertTrue(gateway.capabilities().contains(GatewayCapability.IMAGES));
            assertTrue(gateway.capabilities().contains(GatewayCapability.PROVIDER_TOOLS));
            assertEquals(EnumSet.allOf(GatewayCapability.class), EnumSet.copyOf(gateway.capabilities()));
        }
    }

    @Test
    void theTokenCountCountsAnImageByItsSize() {
        byte[] png = new byte[33];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'}, 0,
                png, 0, 16);
        png[18] = 0x04; // width 1024
        png[22] = 0x03; // height 768
        ModelRequest request = ModelRequest.builder("vision", ModelClass.SONNET_CLASS)
                .message(Message.user(Content.Text.of("12345678"), new Content.Image("image/png", png, null))).build();
        try (ScriptedGateway gateway = ScriptedGateway.sequence(java.util.List.of())) {
            assertEquals(2 + 1036, gateway.countTokens(request), "8 characters / 4 plus 37 x 28 for the capture");
        }
    }
}
