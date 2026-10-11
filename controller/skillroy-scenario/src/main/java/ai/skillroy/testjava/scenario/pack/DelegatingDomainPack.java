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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.StoredTable;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A pack that forwards every {@link DomainPack} member to a base pack (phase6-contracts.md 11.1, decision D144): an
 * overlay ({@link DomainPackOverlay}) subclasses it and overrides what its variant changes. The id stays the base's,
 * so that scenarios keep {@code pack: <base>}; the version is the composite {@code <base version>+<overlay id>@<overlay
 * version>}, which goes into {@code plan.json} as {@code packVersion}.
 *
 * <p>A subclass must keep the base's thread-safety: instances are shared by every scenario run in the JVM.
 */
public abstract class DelegatingDomainPack implements DomainPack {

    private static final Pattern OVERLAY_ID = Pattern.compile("[a-z][a-z0-9-]*");

    private final DomainPack base;
    private final String overlayId;
    private final String overlayVersion;

    /**
     * @param base the pack overlaid; never null
     * @param overlayId the overlay's id, {@code [a-z][a-z0-9-]*}
     * @param overlayVersion the overlay's version, not blank
     * @throws IllegalArgumentException if the id or the version is malformed
     */
    protected DelegatingDomainPack(DomainPack base, String overlayId, String overlayVersion) {
        this.base = Objects.requireNonNull(base, "base");
        if (overlayId == null || !OVERLAY_ID.matcher(overlayId).matches()) {
            throw new IllegalArgumentException("an overlay id must match [a-z][a-z0-9-]*, found \"" + overlayId
                    + "\"");
        }
        if (overlayVersion == null || overlayVersion.isBlank()) {
            throw new IllegalArgumentException("overlay " + overlayId + " has no version");
        }
        this.overlayId = overlayId;
        this.overlayVersion = overlayVersion;
    }

    /** The pack overlaid. */
    protected final DomainPack base() {
        return base;
    }

    /** The overlay's id. */
    public final String overlayId() {
        return overlayId;
    }

    /** The overlay's version. */
    public final String overlayVersion() {
        return overlayVersion;
    }

    /** The base's id: scenarios keep {@code pack: <base>}. */
    @Override
    public final String id() {
        return base.id();
    }

    /** {@code <base version>+<overlay id>@<overlay version>}, written into {@code plan.json}. */
    @Override
    public String version() {
        return base.version() + "+" + overlayId + "@" + overlayVersion;
    }

    @Override
    public StepLibrary steps() {
        return base.steps();
    }

    @Override
    public SyncPolicy syncPolicy() {
        return base.syncPolicy();
    }

    @Override
    public List<FactProvider> facts() {
        return base.facts();
    }

    @Override
    public PromptPolicy promptPolicy() {
        return base.promptPolicy();
    }

    @Override
    public List<PreflightCheck> preflight() {
        return base.preflight();
    }

    @Override
    public void recover(AgentConnection agent) throws Exception {
        base.recover(agent);
    }

    @Override
    public ResolutionCatalogue resolution() {
        return base.resolution();
    }

    @Override
    public EnvFingerprinter fingerprinter() {
        return base.fingerprinter();
    }

    @Override
    public List<StoredTable> storedTables() {
        return base.storedTables();
    }

    @Override
    public List<RowMask> rowMasks() {
        return base.rowMasks();
    }

    @Override
    public List<SqlAllowList> sqlAllowLists() {
        return base.sqlAllowLists();
    }

    @Override
    public String assistAnswerPrimitive() {
        return base.assistAnswerPrimitive();
    }

    @Override
    public HookCatalogue hooks() {
        return base.hooks();
    }

    @Override
    public PackSettings settings() {
        return base.settings();
    }

    @Override
    public String screenLegend() {
        return base.screenLegend();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + version() + "]";
    }
}
