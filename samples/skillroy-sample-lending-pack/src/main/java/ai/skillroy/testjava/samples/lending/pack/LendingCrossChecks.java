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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LoanLineRef;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lending pack's oracle cross-checks (phase3-contracts.md 5.1, 10.3, decision D37): each asks the desk to compute,
 * with its own code, what an oracle computed, through a read-only domain primitive. A disagreement is a result, never
 * an exception; the binder reports it as ORACLE_SUSPECT, a verdict on the test.
 *
 * <p>Stateless; shared by every bind in the JVM.
 */
final class LendingCrossChecks {

    private LendingCrossChecks() {
    }

    /**
     * {@code lending.waiver.evaluate} for oracle {@code ExpectedWaiver}: the desk's own dry run of the oracle's loan
     * lines ({@code lending.waiver.evaluate {lines: [{code, qty}]}}, no date: the desk's business date), and the
     * amount it waives under the oracle's rule ({@code 0.00} when the rule is not in the answer). It agrees when that
     * amount differs from the oracle's by at most the tolerance (an absolute amount of money; none means exact).
     *
     * <p>{@code actual} is {@code {ruleCode, amount, date, waivers}}: the rule, the desk's amount, the date the desk
     * evaluated the rules on, and its whole answer, so that a disagreement caused by the date (the rule left its window
     * between resolution and this bind) reads at once in the report. The reason names both amounts and the date.
     */
    static final class WaiverEvaluate implements CrossCheck<ExpectedWaiver.Input, ExpectedWaiver.Waiver> {

        @Override
        public String id() {
            return LendingVerifiers.EVALUATE;
        }

        @Override
        public String oracleFn() {
            return ExpectedWaiver.FN;
        }

        @Override
        public List<String> requires() {
            return List.of(LendingVerifiers.EVALUATE);
        }

        @Override
        public boolean acceptsTolerance() {
            return true;
        }

        /**
         * @throws IllegalArgumentException if the input has no lines or the output no rule or amount (a pack defect,
         *     reported as ENVIRONMENT)
         * @throws IllegalStateException if the desk's answer has not the primitive's shape (ENVIRONMENT)
         */
        @Override
        public CrossCheckResult check(ExpectedWaiver.Input input, ExpectedWaiver.Waiver output, BigDecimal tolerance,
                                      VerifyContext ctx) throws AgentRpcException, AgentLostException {
            if (input == null || input.lines() == null || output == null || output.ruleCode() == null
                    || output.amount() == null) {
                throw new IllegalArgumentException("cross-check " + id() + " needs the oracle's lines, rule and"
                        + " amount");
            }
            List<Object> lines = new ArrayList<>();
            List<String> lots = new ArrayList<>();
            for (LoanLineRef line : input.lines()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("code", line.code());
                entry.put("qty", line.qty());
                lines.add(entry);
                lots.add(LendingVerifiers.units(line.qty(), line.code()));
            }
            List<Object> calls = new ArrayList<>();
            Map<String, Object> answer = LendingVerifiers.call(ctx.agent(), LendingVerifiers.EVALUATE,
                    Map.of("lines", lines), calls);
            Map<String, BigDecimal> waivers = LendingVerifiers.waivers(answer);
            String rule = output.ruleCode();
            BigDecimal desk = LendingResolvers.money(waivers.getOrDefault(rule, BigDecimal.ZERO));
            Object date = answer.get("date");
            BigDecimal allowed = tolerance == null ? BigDecimal.ZERO : tolerance;
            boolean agreed = desk.subtract(output.amount()).abs().compareTo(allowed) <= 0;
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("ruleCode", rule);
            actual.put("amount", desk.toPlainString());
            actual.put("date", date);
            actual.put("waivers", answer.get("waivers"));
            String lot = join(lots);
            String reason = (waivers.containsKey(rule) ? "the desk waives " + LendingVerifiers.text(desk) + " for "
                    + lot + " under " + rule : "the desk waives nothing under " + rule + " for " + lot) + " on " + date
                    + "; the oracle expects " + LendingVerifiers.text(output.amount())
                    + (allowed.signum() == 0 ? "" : " (tolerance " + allowed.toPlainString() + ")");
            Map<String, Object> evidence = LendingVerifiers.evidence(calls);
            return agreed ? CrossCheckResult.agree(reason, actual, evidence)
                    : CrossCheckResult.disagree(reason, actual, evidence);
        }

        /** {@code "3 units of HND-003"}, {@code "1 unit of A, 2 units of B and 3 units of C"}. */
        private static String join(List<String> lots) {
            if (lots.size() < 2) {
                return String.join("", lots);
            }
            return String.join(", ", lots.subList(0, lots.size() - 1)) + " and " + lots.get(lots.size() - 1);
        }
    }
}
