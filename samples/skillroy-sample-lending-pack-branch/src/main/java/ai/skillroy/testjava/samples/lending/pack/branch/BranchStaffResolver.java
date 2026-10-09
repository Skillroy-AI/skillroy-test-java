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

import ai.skillroy.testjava.samples.lending.pack.LendingResolvers;

/**
 * The base's {@code lending.sql.StaffWithPermission} resolver with the branch edition's statement: the role limit is
 * {@code role_cap.ceiling}, not {@code amount} (phase6-contracts.md 11.3). A subclass with another statement rather
 * than a copy: every rule of the resolver (the conditions in code order, the credentials early, the permissions read)
 * is the base's.
 */
public final class BranchStaffResolver extends LendingResolvers.StaffResolver {

    /** {@link LendingResolvers#STAFF_SQL} with the branch edition's limit column. */
    public static final String SQL = LendingResolvers.STAFF_SQL.replace("rl.amount", "rl.ceiling");

    /** The resolver with {@link #SQL}. */
    public BranchStaffResolver() {
        super(SQL);
    }
}
