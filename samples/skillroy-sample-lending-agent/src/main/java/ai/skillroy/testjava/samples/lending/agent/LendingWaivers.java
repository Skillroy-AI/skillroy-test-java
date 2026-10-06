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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.samples.lending.Catalogue;
import ai.skillroy.testjava.samples.lending.Item;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LoanWaiver;
import ai.skillroy.testjava.samples.lending.WaiverEngine;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code lending.waiver.evaluate} (Phase 3 contract section 10.2): the desk's own dry run of its waiver rules on
 * synthetic loan lines, so that a test framework can ask the application what it would waive instead of trusting its
 * own reading of the rules. The answer is {@link WaiverEngine#dryRun}'s, the computation the desk's {@code DUE} key
 * makes ({@link WaiverEngine#evaluate} on counted lines), with the items looked up through the desk's
 * {@link Catalogue} and the rules evaluated on the desk's business date or on a given one.
 *
 * <p><b>Parameters.</b> {@code lines}: 1 to {@value #MAX_LINES} loan lines, each exactly one of {@code code} (matched
 * ignoring case, as the desk's catalogue does) and {@code barcode} (matched exactly, as a scan is), plus {@code qty}, a
 * whole number from 1 to {@value #MAX_QTY}. A line is a counted loan line and has no other field, so neither a return
 * nor a number of days can be expressed; a per-day item may be named and is then ignored by the rules, as the desk
 * ignores it. Lines of the same item add up (the dry run takes units per item code). An item the catalogue does not
 * know makes the whole call fail rather than drop out of it, because a silently ignored line would hide a typo; an
 * inactive item is known and counted, as the dry run counts it. {@code date}: an ISO-8601 local date
 * ({@code 2026-10-03}); default the desk's business date. Any other parameter is refused for the same reason as an
 * unknown item ({@code _meta}, the dispatcher's own key, is left alone). As everywhere in the protocol, a parameter or
 * field given as null counts as absent, and a number or numeric text that is whole is accepted as {@code qty}.
 *
 * <p><b>Answer.</b> {@code {date, waivers: [{ruleCode, amount}]}}: the date the rules were evaluated on, and one entry
 * per rule that waives something, in rule-id order, with the amount as a decimal string with two decimals; an empty
 * list when nothing is waived. Errors are {@code INVALID_PARAMS} with {@code data.param} naming the parameter or the
 * line's field ({@code lines[2].qty}) and {@code data.reason} saying what is wrong ({@code "unknown item"} for an
 * item the catalogue does not know).
 *
 * <p><b>Why a public class of its own.</b> {@link #evaluate} uses only the protocol and the application, nothing of
 * the agent API, so that a test connection without an agent (a binder's test double answering from the sample's real
 * {@link WaiverEngine}) can load it and answer exactly as the module does, errors included.
 *
 * <p>Stateless; the work is bounded by {@value #MAX_LINES} lines of at most {@value #MAX_QTY} units. Safe from any
 * thread: the desk's waiver rules and catalogue are immutable.
 */
public final class LendingWaivers {

    /** Most lines one call takes. */
    public static final int MAX_LINES = 50;
    /** Largest quantity of one line: the largest quantity the desk accepts. */
    public static final int MAX_QTY = 999;

    private static final String LINES = "lines";
    private static final String DATE = "date";
    private static final String CODE = "code";
    private static final String BARCODE = "barcode";
    private static final String QTY = "qty";
    /** The dispatcher's per-call options; it removes them before a primitive runs, a test double may not. */
    private static final String META = "_meta";

    private static final String LINE_SHAPE = "{code or barcode, qty}";
    private static final String QTY_RULE = "a whole number from 1 to " + MAX_QTY;

    private LendingWaivers() {
    }

    /**
     * {@code lending.waiver.evaluate}'s answer, computed by the desk's own rules and catalogue.
     *
     * @param rules the desk's waiver rules ({@code LendingApp.waiverRules()})
     * @param catalogue the desk's catalogue ({@code LendingApp.catalogue()}), where the lines' items are looked up
     * @param params the call's parameters, {@code lines} and optionally {@code date}; null counts as none
     * @param businessDate the desk's business date, the default of {@code date}: {@code LocalDate.now(clock)} on the
     *     clock of the desk's engine, the date its {@code DUE} key evaluates the rules on
     * @return {@code {date, waivers: [{ruleCode, amount}]}}
     * @throws RpcException {@code INVALID_PARAMS}, with {@code data.param} and {@code data.reason}, for parameters
     *     that break the rules above, an unknown item included
     */
    public static Map<String, Object> evaluate(WaiverEngine rules, Catalogue catalogue, Map<String, Object> params,
                                               LocalDate businessDate) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(catalogue, "catalogue");
        Objects.requireNonNull(businessDate, "businessDate");
        JsonView args = JsonView.of(params);
        for (Map.Entry<?, ?> e : ((Map<?, ?>) args.raw()).entrySet()) {
            String key = String.valueOf(e.getKey());
            if (e.getValue() != null && !LINES.equals(key) && !DATE.equals(key) && !META.equals(key)) {
                throw invalid(key, "is not a parameter of lending.waiver.evaluate: give lines and, optionally, date");
            }
        }
        Map<String, Integer> unitsByCode = units(args, catalogue);
        LocalDate date = date(args, businessDate);
        List<Object> waivers = new ArrayList<Object>();
        for (LoanWaiver waiver : rules.dryRun(unitsByCode, catalogue, date)) {
            Map<String, Object> w = new LinkedHashMap<String, Object>();
            w.put("ruleCode", waiver.ruleCode());
            w.put("amount", LendingJson.money(waiver.amount()));
            waivers.add(w);
        }
        Map<String, Object> answer = new LinkedHashMap<String, Object>();
        answer.put("date", date.toString());
        answer.put("waivers", waivers);
        return answer;
    }

    /**
     * The desk's business date: the date its engine evaluates the waiver rules on when {@code DUE} is pressed, from
     * the engine's own clock. It is what {@code app.state} reports as {@code today}.
     */
    static LocalDate businessDate(LendingApp app) {
        return LocalDate.now(app.engine().clock());
    }

    /** The lines as units per item code (the catalogue's own code), in the order the items first appear. */
    private static Map<String, Integer> units(JsonView args, Catalogue catalogue) {
        if (!args.has(LINES)) {
            throw invalid(LINES, "is missing: give 1 to " + MAX_LINES + " lines, each " + LINE_SHAPE);
        }
        List<?> lines = list(args.get(LINES));
        if (lines == null) {
            throw invalid(LINES, "must be an array of 1 to " + MAX_LINES + " lines, each " + LINE_SHAPE);
        }
        if (lines.isEmpty() || lines.size() > MAX_LINES) {
            throw invalid(LINES, "must hold 1 to " + MAX_LINES + " lines, not " + lines.size());
        }
        Map<String, Integer> units = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < lines.size(); i++) {
            String at = LINES + "[" + i + "]";
            Object element = lines.get(i);
            if (!(element instanceof Map)) {
                throw invalid(at, "must be an object " + LINE_SHAPE);
            }
            @SuppressWarnings("unchecked")
            JsonView line = JsonView.of((Map<String, Object>) element);
            for (Map.Entry<?, ?> e : ((Map<?, ?>) line.raw()).entrySet()) {
                String field = String.valueOf(e.getKey());
                if (e.getValue() != null && !CODE.equals(field) && !BARCODE.equals(field) && !QTY.equals(field)) {
                    throw invalid(at + "." + field, "is not a field of a line: a line is one counted loan line "
                            + LINE_SHAPE + ", so a return or a number of days cannot be expressed");
                }
            }
            String code = text(line, CODE, at);
            String barcode = text(line, BARCODE, at);
            if (code != null && barcode != null) {
                throw invalid(at + "." + BARCODE, "must not be given together with code: give exactly one of code "
                        + "and barcode");
            }
            if (code == null && barcode == null) {
                throw invalid(at + "." + CODE, "is missing: give exactly one of code and barcode");
            }
            int qty = qty(line, at);
            Item item = code != null ? catalogue.findByCode(code) : catalogue.findByBarcode(barcode);
            if (item == null) {
                throw invalid(at + "." + (code != null ? CODE : BARCODE), "unknown item");
            }
            Integer before = units.get(item.code());
            units.put(item.code(), Integer.valueOf(before == null ? qty : before.intValue() + qty));
        }
        return units;
    }

    /** An array value as a list (a Java array or collection too, as {@link JsonView#list} reads them), else null. */
    private static List<?> list(Object value) {
        if (value instanceof List) {
            return (List<?>) value;
        }
        if (value instanceof Object[]) {
            return Arrays.asList((Object[]) value);
        }
        if (value instanceof Collection) {
            return new ArrayList<Object>((Collection<?>) value);
        }
        return null;
    }

    private static String text(JsonView line, String field, String at) {
        try {
            return line.str(field);
        } catch (RpcException notText) {
            throw invalid(at + "." + field, "must be a string");
        }
    }

    private static int qty(JsonView line, String at) {
        if (!line.has(QTY)) {
            throw invalid(at + "." + QTY, "is missing: give " + QTY_RULE);
        }
        long qty;
        try {
            qty = line.requireNum(QTY);
        } catch (RpcException notWhole) {
            throw invalid(at + "." + QTY, "must be " + QTY_RULE);
        }
        if (qty < 1 || qty > MAX_QTY) {
            throw invalid(at + "." + QTY, "must be " + QTY_RULE);
        }
        return (int) qty;
    }

    private static LocalDate date(JsonView args, LocalDate businessDate) {
        String text;
        try {
            text = args.str(DATE);
        } catch (RpcException notText) {
            throw invalid(DATE, "must be a string: an ISO-8601 local date such as 2026-10-03");
        }
        if (text == null) {
            return businessDate;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw invalid(DATE, "is not an ISO-8601 local date such as 2026-10-03");
        }
    }

    private static RpcException invalid(String param, String reason) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("param", param);
        data.put("reason", reason);
        return new RpcException(ErrorCodes.INVALID_PARAMS, param + " " + reason, data);
    }
}
