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

package ai.skillroy.testjava.inference.claude;

import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * phase5-contracts.md 15.3 items 1 and 2 against the live API, under the profile {@code inference-live} (2.3, D111):
 * the Claude gateway opened as a run opens it ({@link ClaudeGateway#open} on the default settings, the key from the
 * environment). Item 1: one structured completion on {@code SONNET_CLASS} whose system part, the desk's rules (about
 * 720 tokens counted as characters / 4, above the 512-token minimum of the Sonnet line), is marked as the cache
 * breakpoint, made twice in a row: the first call writes the cache (or reads it, when an earlier run left the prefix
 * warm; the test prints which), the second reads it, each call costs something and the pair less than USD 0.05, and
 * {@code countTokens} of the same request is within 10 % of the input tokens the second call reported. Item 2: a batch
 * of two requests with distinct questions, polled every 15 s for at most 20 minutes (a batch that has not ended by then
 * is cancelled and the test fails with the last status), collected, each result succeeded with its own question's
 * answer, matched by index and not by order of arrival.
 *
 * <p>Each test begins with the two assumptions of 2.3 ({@link Live#assumeLive}): without the profile, or with it and
 * without the key, it is reported skipped, never failed. Each call prints one line on stdout: the request id, the
 * model, the tokens, the cost and the elapsed time; never a prompt and never a key. It spends money: item 1 less than
 * USD 0.05, item 2 two short requests at the batch rate.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClaudeGatewayLiveIT {

    /** The cached system part: the desk's rules, about 2900 characters. */
    static final String RULES = """
            You answer questions about the rules of a tool-library lending desk: the desk lends items to its members \
            and to walk-in borrowers, takes deposits, applies its waiver rules and prints a slip for every loan and \
            every return. The desk's rules, as the live gateway test states them:
            Rule 1. Every item of the library has a catalogue number, a category (hand tool, power tool, ladder, \
            garden tool or measuring tool) and a condition grade from A to D.
            Rule 2. A member may have up to four items on loan at once; a walk-in borrower may have one item and \
            shows a proof of address.
            Rule 3. A loan lasts seven days for hand tools and measuring tools, three days for power tools and two \
            days for ladders.
            Rule 4. A loan may be extended once, by its own length, when no other borrower has reserved the item.
            Rule 5. Every loan prints a slip naming the catalogue number, the borrower's card number, the due date \
            and the deposit held.
            Rule 6. Power tools and ladders carry a refundable deposit: 20.00 for a power tool and 30.00 for a ladder.
            Rule 7. Garden tools carry a deposit of 10.00; hand tools and measuring tools carry none.
            Rule 8. A member with three completed loans and no late return in the past year qualifies for the \
            deposit waiver.
            Rule 9. The deposit waiver never covers a ladder, and the supervisor countersigns every slip on which it \
            is used.
            Rule 10. A late return costs 0.50 per item per day, capped at the item's deposit, or at 5.00 when it \
            has none.
            Rule 11. A returned item is inspected at the desk; a drop of one condition grade is normal wear and \
            costs nothing.
            Rule 12. A drop of two or more grades is damage: the desk keeps the deposit and records the damage \
            against the item.
            Rule 13. An item graded D is withdrawn from lending until a volunteer has repaired it and graded it again.
            Rule 14. Batteries and chargers go out with their power tool and appear on the same slip as lines of \
            their own.
            Rule 15. A reservation holds an item for two days after its return; a reservation nobody claims lapses \
            at no charge.
            Rule 16. Children's tool kits are lent to members only, for fourteen days, with no deposit.
            Rule 17. A deposit is paid back the way it was taken, and the slip of the return records the amount \
            paid back.
            Rule 18. A lost item is charged at its replacement value less any deposit held, and its slip records \
            the loss.
            Rule 19. The desk ends each day with a count of items out, items due and deposits held, printed on a \
            day slip.
            Rule 20. A mistake on a slip is corrected by voiding the slip and printing a new one; the voided slip is \
            kept for audit.
            Rule 21. A volunteer never lends to themselves; another volunteer or the supervisor handles their loans.
            Rule 22. When two rules disagree the stricter one applies, and the supervisor is told before the loan is \
            made.
            Answer with the number of the one rule that answers the question, as digits only, and nothing else.""";

    /** Item 1's question (rule 10 answers it). */
    static final String FEE_QUESTION = "Which rule sets the fee for a late return?";

    /** Item 2's questions, answered by rules whose numbers cannot be mistaken for each other. */
    static final List<String> BATCH_QUESTIONS = List.of("Which rule sets the deposit that a ladder carries?",
            "Which rule says how a mistake on a slip is corrected?");

    /** The rule that answers each of {@link #BATCH_QUESTIONS}, by index. */
    static final List<String> BATCH_RULES = List.of("6", "20");

    /** The structured answer: {@code {answer: string}}, every property required, nothing else allowed. */
    static final Map<String, Object> ANSWER_SCHEMA = Map.of("type", "object", "properties",
            Map.of("answer", Map.of("type", "string")), "required", List.of("answer"), "additionalProperties", false);

    /** What item 1's two completions may cost together. */
    static final BigDecimal PAIR_BOUND_USD = new BigDecimal("0.05");
    /** How often item 2 reads the batch's status. */
    static final Duration BATCH_POLL = Duration.ofSeconds(15);
    /** How long item 2 waits for the batch to end, from its submission. */
    static final Duration BATCH_BOUND = Duration.ofMinutes(20);

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** The settings of a run with the gateway {@code claude} and no file, property or option: the defaults of 4.4. */
    private static InferenceSettings defaults() {
        return new InferenceSettings(InferenceSettings.GATEWAY_CLAUDE, null, false, null, null, null, null, null, null,
                true, false, null, false, null);
    }

    /** A question about the rules: the rules cached (the default five-minute TTL), low effort, 64 tokens. */
    private static ModelRequest request(String question) {
        return ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).effort(Effort.LOW).maxTokens(64)
                .cache(CacheHint.SHORT).system(Content.Text.cached(RULES)).message(Message.user(question))
                .outputSchema(ANSWER_SCHEMA).build();
    }

    /** 15.3 item 1. */
    @Test
    @Order(1)
    void aCachedSystemPartIsWrittenThenReadAndTheTokenCountAgrees() throws Exception {
        Live.assumeLive();
        ModelRequest request = request(FEE_QUESTION);
        try (ClaudeGateway gateway = ClaudeGateway.open(defaults())) {
            long started = System.nanoTime();
            ModelResponse first = gateway.complete(request);
            print("item 1, call 1", first, first.usage().elapsed());
            long gap = System.nanoTime() - started;
            ModelResponse second = gateway.complete(request);
            print("item 1, call 2 (" + TimeUnit.NANOSECONDS.toMillis(gap) + " ms after the first)", second,
                    second.usage().elapsed());

            Usage one = first.usage();
            Usage two = second.usage();
            boolean wrote = one.cacheWrite5mTokens() > 0;
            assertTrue(wrote || one.cacheReadTokens() > 0, "the first call wrote the cached prefix, or read it when an"
                    + " earlier run left it warm: " + one);
            System.out.println("ClaudeGatewayLiveIT item 1: the first call " + (wrote ? "wrote the cache ("
                    + one.cacheWrite5mTokens() + " tokens, five-minute TTL)" : "read the cache an earlier run left"
                    + " warm (" + one.cacheReadTokens() + " tokens)"));
            assertTrue(two.cacheReadTokens() > 0, "the second call read the cached prefix: " + two);
            answer(first, "call 1");
            answer(second, "call 2");
            BigDecimal pair = cost(one, "call 1").add(cost(two, "call 2"));
            assertTrue(pair.compareTo(PAIR_BOUND_USD) < 0, "the two calls cost less than USD " + PAIR_BOUND_USD
                    + " together: USD " + pair.toPlainString());

            long counting = System.nanoTime();
            long counted = gateway.countTokens(request);
            long reported = two.inputTokens() + two.cacheReadTokens() + two.cacheWriteTokens();
            System.out.println("ClaudeGatewayLiveIT item 1, countTokens: " + counted + " input tokens (the second call"
                    + " reported " + reported + "), elapsed " + millis(counting) + " ms");
            assertTrue(counted > 0, "the token count is known: " + counted);
            assertTrue(Math.abs(counted - reported) * 10 <= reported, "countTokens " + counted + " is within 10 % of"
                    + " the " + reported + " input tokens the second call reported");
        }
    }

    /** 15.3 item 2. */
    @Test
    @Order(2)
    void aBatchOfTwoIsSubmittedPolledAndCollectedByIndex() throws Exception {
        Live.assumeLive();
        List<ModelRequest> requests = new ArrayList<>();
        for (String question : BATCH_QUESTIONS) {
            requests.add(request(question));
        }
        try (ClaudeGateway gateway = ClaudeGateway.open(defaults())) {
            long submitted = System.nanoTime();
            BatchHandle handle = gateway.submitBatch(requests);
            System.out.println("ClaudeGatewayLiveIT item 2, submit: batch " + handle.id() + ", " + handle.requests()
                    + " requests, elapsed " + millis(submitted) + " ms");
            awaitEnd(gateway, handle, submitted);
            long collecting = System.nanoTime();
            List<BatchResult> results = gateway.collectBatch(handle);
            Duration batchTime = Duration.ofNanos(System.nanoTime() - submitted);
            System.out.println("ClaudeGatewayLiveIT item 2, collect: " + results.size() + " results, elapsed "
                    + millis(collecting) + " ms; the batch took " + batchTime.toSeconds() + " s from its submission");

            assertEquals(2, results.size(), "one result per request");
            Set<Integer> indices = new TreeSet<>();
            for (BatchResult result : results) {
                int i = result.index();
                indices.add(i);
                assertTrue(result.succeeded(), "batch request " + i + " succeeded: " + (result.error() == null ? ""
                        : result.error().kind() + " " + result.error().getMessage()));
                ModelResponse response = result.response();
                print("item 2, batch request " + i, response, batchTime);
                assertTrue(response.usage().batch(), "batch request " + i + " is priced as a batch call");
                String answer = answer(response, "batch request " + i);
                String own = BATCH_RULES.get(i);
                String other = BATCH_RULES.get(1 - i);
                Set<String> named = numbers(answer);
                assertTrue(named.contains(own) && !named.contains(other), "batch request " + i + " answers its own"
                        + " question (rule " + own + "), not the other request's (rule " + other + "): " + answer);
            }
            assertEquals(Set.of(0, 1), indices, "the results are matched to their requests by index");
        }
    }

    /**
     * Reads the batch's status every {@link #BATCH_POLL} until it has ended, for at most {@link #BATCH_BOUND} from its
     * submission: a batch that has not ended by then is cancelled and the test fails with the last status. A status
     * read that fails, or an interrupt, cancels it too, so that nothing is left running behind a failed test.
     */
    private static void awaitEnd(ClaudeGateway gateway, BatchHandle handle, long submitted)
            throws InferenceException, InterruptedException {
        long deadline = submitted + BATCH_BOUND.toNanos();
        try {
            BatchStatus status = status(gateway, handle, submitted);
            while (status.state() != BatchStatus.State.ENDED) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    cancel(gateway, handle);
                    fail("batch " + handle.id() + " did not end within " + BATCH_BOUND.toMinutes() + " minutes and"
                            + " was cancelled; its last status: " + status);
                }
                // The contract's bounded poll of a live test (15 s, at most 20 minutes); the main code never sleeps.
                Thread.sleep(Math.max(1, Math.min(BATCH_POLL.toMillis(), TimeUnit.NANOSECONDS.toMillis(left))));
                status = status(gateway, handle, submitted);
            }
        } catch (InferenceException | InterruptedException | RuntimeException e) {
            cancel(gateway, handle);
            throw e;
        }
    }

    private static BatchStatus status(ClaudeGateway gateway, BatchHandle handle, long submitted)
            throws InferenceException {
        long reading = System.nanoTime();
        BatchStatus status = gateway.batchStatus(handle);
        System.out.println("ClaudeGatewayLiveIT item 2, status: " + status.state() + " (succeeded "
                + status.succeeded() + ", errored " + status.errored() + ", expired " + status.expired()
                + ", canceled " + status.canceled() + ", processing " + status.processing() + ") "
                + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - submitted) + " s after the submission, elapsed "
                + millis(reading) + " ms");
        return status;
    }

    /** Cancels the batch, best effort: a cancel that fails is printed, never thrown over the test's own failure. */
    private static void cancel(ClaudeGateway gateway, BatchHandle handle) {
        try {
            gateway.cancelBatch(handle);
            System.out.println("ClaudeGatewayLiveIT item 2, cancel: batch " + handle.id() + " cancelled");
        } catch (InferenceException e) {
            System.out.println("ClaudeGatewayLiveIT item 2, cancel: batch " + handle.id() + " could not be cancelled: "
                    + e.kind() + " " + e.getMessage());
        }
    }

    /** One line per call: the request id, the model, the tokens, the cost and the elapsed time; never a prompt. */
    private static void print(String call, ModelResponse response, Duration elapsed) {
        Usage u = response.usage();
        System.out.println("ClaudeGatewayLiveIT " + call + ": request " + u.requestId() + ", model " + u.model()
                + (Objects.equals(u.model(), u.requestedModel()) ? "" : " (asked for " + u.requestedModel() + ")")
                + ", stop " + response.stop() + ", tokens in " + u.inputTokens() + " out " + u.outputTokens()
                + " cache read " + u.cacheReadTokens() + " cache write " + u.cacheWrite5mTokens() + " (5 min) "
                + u.cacheWrite1hTokens() + " (1 h)" + (u.batch() ? ", batch" : "") + ", cost USD " + u.costUsd()
                + ", elapsed " + elapsed.toMillis() + " ms" + (u.note() == null ? "" : ", note: " + u.note()));
    }

    /** The structured answer of a response: a map whose {@code answer} is a text that is not blank. */
    private static String answer(ModelResponse response, String what) {
        Map<?, ?> structured = assertInstanceOf(Map.class, response.structured(), what + " has a structured answer"
                + " (stop " + response.stop() + ")");
        Object answer = structured.get("answer");
        assertTrue(answer instanceof String text && !text.isBlank(), what + " has a non-blank answer: " + structured);
        return (String) answer;
    }

    /** A call's cost, which must be a positive decimal. */
    private static BigDecimal cost(Usage usage, String what) {
        BigDecimal cost = new BigDecimal(usage.costUsd());
        assertTrue(cost.signum() > 0, what + " cost something: USD " + usage.costUsd());
        return cost;
    }

    /** The whole numbers a text names, without leading zeros ("Rule 06." names 6). */
    private static Set<String> numbers(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            out.add(m.group().replaceFirst("^0+(?=\\d)", ""));
        }
        return out;
    }

    private static long millis(long sinceNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos);
    }
}
