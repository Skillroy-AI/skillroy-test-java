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

import ai.skillroy.testjava.samples.lending.pack.LendingAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;

/**
 * What a model may read from the branch desk's database (phase6-contracts.md 11.3): the base pack's allow-list with
 * {@code role_cap.ceiling} in place of {@code amount} and the system maximum named {@code return_ceiling}. The MCP
 * server's start check against the database (decision D100) refuses a list that does not match the schema.
 */
public final class BranchAllowList {

    /** The list of source {@code lending} for the branch edition. */
    public static final SqlAllowList LIST = LendingAllowList.of("ceiling", BranchPack.MAX_RETURN_SETTING);

    private BranchAllowList() {
    }
}
