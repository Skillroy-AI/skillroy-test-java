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

import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds domain packs with {@code ServiceLoader<DomainPack>} and picks the one a scenario names ({@code pack:} may be
 * omitted when exactly one pack is on the class path).
 *
 * <p>Stateless; safe from any thread (each call loads fresh instances).
 */
public final class DomainPacks {

    private DomainPacks() {
    }

    /**
     * Every pack visible to {@code loader}, sorted by id.
     *
     * @throws ServiceConfigurationError if a {@code META-INF/services} entry names a class that cannot be loaded
     */
    public static List<DomainPack> discover(ClassLoader loader) {
        List<DomainPack> packs = new ArrayList<>();
        ServiceLoader.load(DomainPack.class, loader).forEach(packs::add);
        packs.sort(Comparator.comparing(DomainPack::id));
        return packs;
    }

    /**
     * The pack with id {@code packId}, or the only pack when {@code packId} is null.
     *
     * @throws ScenarioException if no pack matches, {@code packId} is null and there is not exactly one pack, or the
     *     selected pack's id is reserved ({@link #reservedId})
     */
    public static DomainPack select(String packId, List<DomainPack> available) throws ScenarioException {
        List<String> ids = available.stream().map(DomainPack::id).toList();
        if (packId == null) {
            if (available.size() == 1) {
                return checked(available.get(0));
            }
            throw new ScenarioException("the scenario names no 'pack:' and " + (available.isEmpty()
                    ? "no domain pack is on the class path" : "several packs are on the class path: " + ids));
        }
        for (DomainPack pack : available) {
            if (packId.equals(pack.id())) {
                return checked(pack);
            }
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
