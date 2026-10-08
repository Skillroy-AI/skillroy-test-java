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

/**
 * A {@link ResolverContributor} could not offer its resolvers for a bind (phase5-contracts.md 5.5): its settings name
 * a gateway it cannot build, or something it needs is missing. The engine reports it as ENVIRONMENT, with this message,
 * on the slot that needed the contributed resolvers. The message must hold no secret (never anything of the model key).
 *
 * <p>Immutable.
 */
public final class ContributionException extends Exception {

    private static final long serialVersionUID = 1L;

    /** @param message what is missing or wrong, one line, no secret */
    public ContributionException(String message) {
        super(message);
    }

    /**
     * @param message what is missing or wrong, one line, no secret
     * @param cause the underlying failure, or null; its message must hold no secret either
     */
    public ContributionException(String message, Throwable cause) {
        super(message, cause);
    }
}
