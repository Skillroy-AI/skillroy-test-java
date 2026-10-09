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

package ai.skillroy.testjava.scenario.pack;

/**
 * An overlay of one deployment variant over a base pack (phase6-contracts.md 11.1, decision D144; design 3.6): found
 * by {@code ServiceLoader<DomainPackOverlay>} on the same loader as the packs (a {@code META-INF/services} entry and a
 * public no-argument constructor) and applied by {@code DomainPacks.discover} to the base whose id it names. The
 * overlay is not a second pack with the same id: scenarios keep {@code pack: <base>}, and the pack they get is the
 * one {@link #overlay} returns, normally a {@link DelegatingDomainPack} that overrides what the variant changes (its
 * settings, a resolver, the allow-list, the hook catalogue).
 *
 * <p>Several overlays of one base are applied in the order of their {@link #id()}, each to the previous one's result.
 * An overlay whose base is not on the class path is an error at discovery, naming both ids. Instances are shared by
 * every scenario run in the JVM, so they must be stateless or thread-safe.
 */
public interface DomainPackOverlay {

    /** The {@link DomainPack#id()} this overlay applies to. */
    String baseId();

    /** The overlay's own id, {@code [a-z][a-z0-9-]*}, for the record and the composite pack version. */
    String id();

    /** The overlay's version, part of the composite pack version {@code <base>+<id>@<version>}. */
    String version();

    /**
     * The pack to use in place of {@code base}.
     *
     * @param base the base pack, or the result of an overlay applied before this one
     * @return the overlaid pack, whose {@link DomainPack#id()} is the base's; never null
     */
    DomainPack overlay(DomainPack base);
}
