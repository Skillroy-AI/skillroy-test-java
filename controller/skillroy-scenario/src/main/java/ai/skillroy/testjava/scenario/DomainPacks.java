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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.scenario.pack.BaseDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPackOverlay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds domain packs with {@code ServiceLoader<DomainPack>}, applies the overlays that
 * {@code ServiceLoader<DomainPackOverlay>} finds on the same loader (phase6-contracts.md 11.1, decision D144), and
 * picks the pack a scenario names ({@code pack:} may be omitted when exactly one pack is on the class path).
 *
 * <p>Stateless; safe from any thread (each call loads fresh instances).
 */
public final class DomainPacks {

    private DomainPacks() {
    }

    /**
     * Every pack visible to {@code loader}, sorted by id, each with the overlays of the same loader applied
     * (phase6-contracts.md 11.1): the overlays are sorted by their {@code id()}, and every overlay whose
     * {@code baseId()} names a pack is applied to it in that order, the second to the first's result; the result is
     * listed under the base's id.
     *
     * @throws ServiceConfigurationError if a {@code META-INF/services} entry names a class that cannot be loaded
     * @throws IllegalStateException if an overlay names a base pack that is not on the class path (the message names
     *     both ids), or an overlay returns null or a pack with another id; {@link #forScenario} reports it as a
     *     {@link ScenarioException}
     */
    public static List<DomainPack> discover(ClassLoader loader) {
        List<DomainPack> packs = new ArrayList<>();
        ServiceLoader.load(DomainPack.class, loader).forEach(packs::add);
        packs.sort(Comparator.comparing(DomainPack::id));
        List<DomainPackOverlay> overlays = new ArrayList<>();
        ServiceLoader.load(DomainPackOverlay.class, loader).forEach(overlays::add);
        return overlaid(packs, overlays);
    }

    /**
     * {@code packs} (sorted by id) with {@code overlays} applied as {@link #discover} applies them.
     *
     * @throws IllegalStateException if an overlay's base is not among {@code packs}, or an overlay returns null or a
     *     pack whose id is not its base's
     */
    public static List<DomainPack> overlaid(List<DomainPack> packs, List<DomainPackOverlay> overlays) {
        if (overlays.isEmpty()) {
            return packs;
        }
        List<DomainPackOverlay> sorted = new ArrayList<>(overlays);
        sorted.sort(Comparator.comparing(DomainPackOverlay::id));
        Map<String, DomainPack> byId = new LinkedHashMap<>();
        for (DomainPack pack : packs) {
            byId.putIfAbsent(pack.id(), pack);
        }
        for (DomainPackOverlay overlay : sorted) {
            String baseId = overlay.baseId();
            DomainPack base = byId.get(baseId);
            if (base == null) {
                throw new IllegalStateException("pack overlay '" + overlay.id() + "' overlays pack '" + baseId
                        + "', which is not on the class path; packs found: " + byId.keySet());
            }
            DomainPack result = overlay.overlay(base);
            if (result == null || !baseId.equals(result.id())) {
                throw new IllegalStateException("pack overlay '" + overlay.id() + "' returned "
                        + (result == null ? "no pack" : "a pack with id '" + result.id() + "'") + " for pack '"
                        + baseId + "'");
            }
            byId.put(baseId, result);
        }
        // The overlaid pack takes the place of the first pack of its id; a second pack of the same id stays as it is
        // (the CLI refuses duplicate ids as a real error, the overlay being another kind of service).
        List<DomainPack> out = new ArrayList<>(packs.size());
        Set<String> placed = new HashSet<>();
        for (DomainPack pack : packs) {
            out.add(placed.add(pack.id()) ? byId.get(pack.id()) : pack);
        }
        return out;
    }

    /**
     * The pack with id {@code packId}, the only pack when {@code packId} is null, or {@link BaseDomainPack#INSTANCE}
     * when {@code packId} is {@code "base"} or when {@code packId} is null and {@code available} is empty.
     *
     * @throws ScenarioException if no pack matches, {@code packId} is null and more than one pack is on the class
     *     path, or the selected pack's id is reserved ({@link #reservedId})
     */
    public static DomainPack select(String packId, List<DomainPack> available) throws ScenarioException {
        List<String> ids = available.stream().map(DomainPack::id).toList();
        if (packId == null) {
            if (available.isEmpty()) {
                return BaseDomainPack.INSTANCE;
            }
            if (available.size() == 1) {
                return checked(available.get(0));
            }
            throw new ScenarioException("the scenario names no 'pack:' and several packs are on the class path: "
                    + ids);
        }
        for (DomainPack pack : available) {
            if (packId.equals(pack.id())) {
                return checked(pack);
            }
        }
        if (BaseDomainPack.ID.equals(packId)) {
            return BaseDomainPack.INSTANCE;
        }
        throw new ScenarioException("domain pack '" + packId + "' is not on the class path; packs found: " + ids);
    }

    /**
     * The pack for a loaded scenario, discovered with {@code loader}.
     *
     * @throws ScenarioException if it cannot be selected or a pack cannot be loaded; the message names the scenario
     */
    public static DomainPack forScenario(Scenario scenario, ClassLoader loader) throws ScenarioException {
        try {
            return select(scenario.pack(), discover(loader));
        } catch (ScenarioException e) {
            throw e.locate(scenario.id(), 0);
        } catch (ServiceConfigurationError e) {
            throw new ScenarioException(scenario.id(), 0, "a domain pack on the class path cannot be loaded: "
                    + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ScenarioException(scenario.id(), 0, e.getMessage(), e);
        }
    }

    private static DomainPack checked(DomainPack pack) throws ScenarioException {
        if (reservedId(pack.id())) {
            throw new ScenarioException("domain pack id '" + pack.id() + "': the prefix 'inference' is reserved for"
                    + " what the controller contributes");
        }
        return pack;
    }

    /** True for a pack id the controller reserves: {@code inference} or one starting with {@code inference.}. */
    public static boolean reservedId(String packId) {
        return packId != null && (packId.equals("inference") || packId.startsWith("inference."));
    }
}
