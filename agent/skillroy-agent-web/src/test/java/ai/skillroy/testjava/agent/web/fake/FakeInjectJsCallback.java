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

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

/**
 * The neutral fake's single-slot document-start callback, shaped like the vendor's ({@code Response on(Params)}, a
 * {@code Params} with {@code frame()}, a {@code Response} with the static {@code proceed()}).
 */
public interface FakeInjectJsCallback {

    Response on(Params params);

    /** The callback's parameters. */
    final class Params {

        static {
            ClassInitialisations.record(FakeInjectJsCallback.Params.class);
        }

        private final FakeFrame frame;

        Params(FakeFrame frame) {
            this.frame = frame;
        }

        public FakeFrame frame() {
            return frame;
        }
    }

    /** The callback's answer. */
    final class Response {

        static {
            ClassInitialisations.record(FakeInjectJsCallback.Response.class);
        }

        private static final Response PROCEED = new Response();

        private Response() {
        }

        public static Response proceed() {
            return PROCEED;
        }
    }
}
