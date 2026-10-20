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

package ai.skillroy.testjava.samples.webhost.agent;

import ai.skillroy.testjava.agent.api.spi.web.BrowserMask;

/**
 * Names the web host's back browser by its seed tag (Phase 8, phase8-contracts.md 5.2, 12.3, gap 110): a
 * {@link BrowserMask} answering {@code TRUE} for the tag {@value WebHostModule#TAG_BACK} (a browser with a component in
 * the hidden card, masked on every route whatever its view, without a {@code CaptureMask}) and no opinion on any other
 * browser. Registered by {@link WebHostModule} with the agent option {@code webhost.mask} naming
 * {@value WebHostModule#MASK_TAG_BACK}.
 *
 * <p>Called on agent workers; compares the tag only, never touches the vendor object, holds no state.
 */
final class BackTagMask implements BrowserMask {

    @Override
    public Boolean masks(String tag, Object vendorObject, boolean hasView) {
        return WebHostModule.TAG_BACK.equals(tag) ? Boolean.TRUE : null;
    }
}
