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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.samples.lending.AccessControl;
import ai.skillroy.testjava.samples.lending.AccessDecision;
import ai.skillroy.testjava.samples.lending.Catalogue;
import ai.skillroy.testjava.samples.lending.Deposits;
import ai.skillroy.testjava.samples.lending.Item;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Staff;
import ai.skillroy.testjava.samples.lending.WaiverEngine;
import ai.skillroy.testjava.samples.lending.agent.LendingWaivers;
import ai.skillroy.testjava.samples.lending.pack.LendingPack;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A test connection for the binder that answers from the sample application's own code, without a target process
 * (phase2-contracts.md 14.1 item 8, phase3-contracts.md 11.1 item 6): {@code /ping}, {@code schema},
 * {@code lending.access.check}, {@code lending.deposit} and {@code lending.limits} from the real {@link AccessControl}
 * and {@link Catalogue} loaded from the given database, in the answer shapes of the lending module's primitives;
 * {@code lending.waiver.evaluate} from the real {@link WaiverEngine} through the lending module's own
 * {@link LendingWaivers#evaluate} (its {@code INVALID_PARAMS} errors as the agent sends them); {@code app.state} with
 * the database's seed and the desk's business date ({@code today}, the date the database was seeded on, which the
 * desk's rule windows are relative to); {@code swing.find} from the pack's fixed list of key actions. Every other
 * method is {@code METHOD_NOT_FOUND} ({@code data.sources} included: the controller-side JDBC channel serves the
 * database). Records the primitives it was asked, in order. Its capability matrix is empty unless a test scripts one
 * ({@link #capability}).
 *
 * <p>Single-threaded test code.
 */
final class DeskConnection implements AgentConnection {

    private final AccessControl access;
    private final Catalogue catalogue;
    private final WaiverEngine waivers;
    private final String seed;
    private final LocalDate today;
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();

    /**
     * @param db the seeded database
     * @param today the desk's business date: the date {@code db} was seeded on
     */
    DeskConnection(LendingDb db, LocalDate today) throws SQLException {
        this.access = AccessControl.load(db.connection());
        this.catalogue = Catalogue.load(db.connection());
        this.waivers = WaiverEngine.load(db.connection());
        this.seed = db.seed().name();
        this.today = today;
    }

    /** Scripts one capability of the matrix {@link #capabilities()} reports (a test double: the desk has them all). */
    DeskConnection capability(String id, CapabilityState state, String reason) {
        capabilities.put(id, new Capability(id, state, reason, "scripted"));
        return this;
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException {
        return call(method, params, CallOptions.defaults());
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params, CallOptions options) throws AgentRpcException {
        calls.add(method);
        Map<String, Object> p = params == null ? Map.of() : params;
        Object value = switch (method) {
            case "lending.access.check" -> accessCheck(p);
            case "lending.deposit" -> deposit(p);
            case "lending.limits" -> map("maxReturn", money(access.systemMaxReturn()));
            case "lending.waiver.evaluate" -> evaluate(p);
            case "app.state" -> Map.of("lending", map("seed", seed, "today", today.toString()));
            case "swing.find" -> find(p);
            default -> throw new AgentRpcException(method, new RpcError(ErrorCodes.METHOD_NOT_FOUND,
                    "unknown method " + method, null));
        };
        return new RpcResult(value, List.of(), 0, 0, 1, "desk");
    }

    private Map<String, Object> accessCheck(Map<String, Object> p) {
        String staff = String.valueOf(p.get("staff"));
        BigDecimal amount = p.get("amount") == null ? null : new BigDecimal(String.valueOf(p.get("amount")));
        AccessDecision decision = access.check(staff, String.valueOf(p.get("permission")), amount);
        Staff member = access.findStaff(staff);
        return map("allowed", decision.allowed(), "limit", money(decision.limit()), "reason", decision.reason(),
                "autoSignOn", member != null && member.autoSignOn());
    }

    private Map<String, Object> deposit(Map<String, Object> p) throws AgentRpcException {
        if (p.containsKey("barcode") == p.containsKey("code")) {
            throw new AgentRpcException("lending.deposit", new RpcError(ErrorCodes.INVALID_PARAMS,
                    "give exactly one of barcode and code", map("param", p.containsKey("code") ? "code" : "barcode")));
        }
        Item item = p.containsKey("barcode") ? catalogue.findByBarcode(String.valueOf(p.get("barcode")))
                : catalogue.findByCode(String.valueOf(p.get("code")));
        if (item == null) {
            return map("found", false);
        }
        return map("found", true, "code", item.code(), "barcode", item.barcode(), "title", item.title(), "deposit",
                money(item.unitDeposit()), "active", item.active(), "perDay", item.perDay(), "inductionRequired",
                item.inductionRequired());
    }

    /** The lending module's own dry run, its {@code RpcException} turned into what the client throws. */
    private Map<String, Object> evaluate(Map<String, Object> p) throws AgentRpcException {
        try {
            return LendingWaivers.evaluate(waivers, catalogue, p, today);
        } catch (RpcException e) {
            throw new AgentRpcException("lending.waiver.evaluate", e.error());
        }
    }

    private static Map<String, Object> find(Map<String, Object> p) {
        Object action = p.get("by") instanceof Map<?, ?> by ? by.get("action") : null;
        List<Object> matches = new ArrayList<>();
        if (action != null && LendingPack.KEY_ACTIONS.contains(String.valueOf(action))) {
            matches.add(map("name", action, "class", "IconKey"));
        }
        return map("matches", matches);
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : Deposits.format(amount);
    }

    /** A map that may hold null values, in the order given. */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return m;
    }

    @Override
    public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
        throw new UnsupportedOperationException("a bind awaits nothing");
    }

    @Override
    public CapabilityMatrix capabilities() {
        calls.add("capabilities");
        return new CapabilityMatrix(capabilities);
    }

    /** The read-only primitives it answers, and the desk's input, which is mutating. */
    @Override
    public List<PrimitiveDescriptor> schema() {
        List<PrimitiveDescriptor> list = new ArrayList<>();
        for (String name : List.of("lending.access.check", "lending.deposit", "lending.limits",
                "lending.waiver.evaluate", "app.state", "swing.find")) {
            list.add(new PrimitiveDescriptor(name, name, Map.of(), Map.of(), "APP", 5000, name, false, false));
        }
        list.add(new PrimitiveDescriptor("lending.input", "input", Map.of(), Map.of(), "APP", 15000, "lending.input",
                true, false));
        return list;
    }

    @Override
    public Map<String, Object> ping() {
        return map("ready", true, "protocol", Protocol.VERSION);
    }

    @Override
    public void close() {
    }
}
