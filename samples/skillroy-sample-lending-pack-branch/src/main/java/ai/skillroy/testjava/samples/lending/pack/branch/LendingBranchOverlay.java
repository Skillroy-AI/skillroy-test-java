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

package ai.skillroy.testjava.samples.lending.pack.branch;

import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPackOverlay;

/**
 * The overlay {@value #ID} of pack {@code lending} for the sample desk's branch edition (phase6-contracts.md 11.3,
 * decision D146): found by {@code ServiceLoader<DomainPackOverlay>} when this jar is on the class path beside the base
 * pack's, applied by {@code DomainPacks.discover}. Scenarios keep {@code pack: lending}; the pack they get is
 * {@link BranchPack}, whose version is {@code 0.7.0+lending-branch@0.1.0}.
 *
 * <p>Stateless; one instance serves every scenario in the JVM.
 */
public final class LendingBranchOverlay implements DomainPackOverlay {

    /** The overlay's id, also its hook catalogue's. */
    public static final String ID = "lending-branch";

    /** The overlay's version. */
    public static final String VERSION = "0.1.0";

    /** Public no-argument constructor for {@code ServiceLoader}. */
    public LendingBranchOverlay() {
    }

    @Override
    public String baseId() {
        return LendingPack.ID;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public DomainPack overlay(DomainPack base) {
        return new BranchPack(base);
    }
}
