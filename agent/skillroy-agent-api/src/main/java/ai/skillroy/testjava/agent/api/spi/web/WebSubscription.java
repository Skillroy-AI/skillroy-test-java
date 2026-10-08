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

package ai.skillroy.testjava.agent.api.spi.web;

/**
 * A registration made on a {@link WebBrowser}: an event listener ({@link WebBrowser#subscribe}) or an init script
 * ({@link WebBrowser#addInitScript}).
 */
public interface WebSubscription {

    /**
     * Undoes the registration: once this returns, no new delivery to the listener starts, and no new document runs
     * the init script. Idempotent; never throws.
     */
    void cancel();
}
