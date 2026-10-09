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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branch variant's committed plans (phase6-contracts.md 11.4): {@code variants/branch/plan/<id>.plan.json} for
 * scenarios A, B and E are what the overlaid pack compiles from the same YAML now, byte for byte, and each differs
 * from {@code plan/}'s in {@code packVersion} only (a plan holds query kinds and {@code db} SQL, never a resolver's
 * statement, and the {@code db} checks read no table the branch edition renames). Regenerate with
 * {@code -Dskillroy.plans.update=true}.
 */
class BranchPlansUpToDateTest {

    @Test
    void theVariantsPlansAreTheOverlaidPacksAndDifferInThePackVersionOnly() throws Exception {
        DomainPack pack = BranchVariant.pack();
        assertEquals("lending", pack.id());
        assertEquals(BranchVariant.PACK_VERSION, pack.version());
        boolean update = Boolean.getBoolean("skillroy.plans.update");
        List<String> stale = new ArrayList<>();
        for (String id : BranchVariant.SCENARIOS) {
            Path yaml = ItEnv.scenario(id + ".yaml");
            Path plan = BranchVariant.plan(id);
            if (update) {
                Files.createDirectories(plan.getParent());
                PlanCompiler.compileTo(yaml, pack, plan);
            }
            if (!PlanCompiler.isUpToDate(yaml, plan, pack)) {
                stale.add(ItEnv.moduleDir().relativize(plan).toString());
                continue;
            }
            Map<String, Object> variant = Json.parseObject(Files.readString(plan, StandardCharsets.UTF_8));
            Map<String, Object> standard = Json.parseObject(Files.readString(ItEnv.plan(id), StandardCharsets.UTF_8));
            assertEquals(BranchVariant.PACK_VERSION, variant.get("packVersion"), id);
            variant.remove("packVersion");
            standard.remove("packVersion");
            assertEquals(standard, variant, id + ": the variant's plan differs in packVersion only");
            assertEquals(Plan.read(ItEnv.plan(id)).resolveHash(), Plan.read(plan).resolveHash(), id);
        }
        assertTrue(stale.isEmpty(), "out of date (regenerate with -Dskillroy.plans.update=true): " + stale);
        try (Stream<Path> plans = Files.list(BranchVariant.dir().resolve("plan"))) {
            assertEquals(BranchVariant.SCENARIOS.size(), plans.count(), "one plan per variant scenario, nothing else");
        }
    }
}
