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

package ai.skillroy.testjava.scenario.resolve;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.data.DataException;

import java.util.Map;

/**
 * Supplies a pack's components of the environment fingerprint (phase2-contracts.md 8.2): what identifies the
 * environment a bindings file was made for (schema checksum, application build, site, configuration version). Never
 * transactional data, secrets or personal data.
 *
 * <p>Called once per bind, before any slot, on the executor's thread. Read-only and bounded; a failure (an exception,
 * a key without the pack prefix, a truncated statement) is ENVIRONMENT. Stateless or thread-safe.
 */
public interface EnvFingerprinter {

    /**
     * Named components. Keys: {@code "<pack>.<name>"}. Values: short texts or {@code "sha256:…"} checksums.
     *
     * @throws DataException a statement failed
     * @throws AgentRpcException a primitive answered with an error
     * @throws AgentLostException the agent is gone
     * @throws InterruptedException the thread was interrupted
     */
    Map<String, String> components(FingerprintContext ctx)
            throws DataException, AgentRpcException, AgentLostException, InterruptedException;
}
