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

package ai.skillroy.testjava.agent.api.spi.hook;

/**
 * Refuses a class by binary name before any module changes its bytecode (phase8-contracts.md section 6.5, gap 114,
 * D562): a hot path, a licensed or timing-sensitive class. Registered by a domain module in its {@code install}
 * ({@code ctx.spi().register(ClassDeny.class, ...)}); the optional hooks module registers one of its own for the
 * catalogue's held deny-list. Asked at every {@code hook.probe}, {@code hook.install}, {@code rule.check} and
 * {@code rule.submit} (and the {@link HookEngine} SPI's {@code probe} and {@code install}), whatever the
 * registration order, after the agent option {@code hooks.deny}: the first refusal wins.
 *
 * <p>Agent worker threads only, under the hook engine's or the Byteman module's lock: cheap, never blocking, never
 * touching the application. A throw counts as a refusal ({@code denied by a module: the deny policy <class> threw
 * <exception>}), logged once. A deny refuses <em>instrumenting</em> a class, not a Byteman {@code DO} clause calling
 * into it; it is policy, not checksum protection (D572).
 */
public interface ClassDeny {

    /**
     * Null to allow, else a short reason (cut to 200 characters by the caller), answered as {@code denied by a module:
     * <reason>}.
     *
     * @param className the binary name ({@code a.b.C$D}) of the class a module would change
     */
    String refuse(String className);
}
