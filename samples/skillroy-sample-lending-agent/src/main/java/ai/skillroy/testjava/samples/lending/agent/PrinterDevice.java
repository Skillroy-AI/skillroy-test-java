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

import ai.skillroy.testjava.agent.api.spi.DeviceAdapter;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.samples.lending.SlipPrinter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The desk's slip printer as device {@value #ID} (kind {@code printer}) for {@code device.state} and
 * {@code device.act}. State: {@code {slips, kept, header, last}} where {@code slips} counts every slip
 * printed since start-up and {@code last} is the most recent one ({@code {loanId, lines, text}} or null). Action
 * {@value #LAST_SLIP} returns the most recent slip. Read-only: printing is the engine's business.
 *
 * <p>Called under {@code ThreadPolicy.APP}; the printer itself is thread-safe.
 */
final class PrinterDevice implements DeviceAdapter {

    /** Device id. */
    static final String ID = "printer";
    /** The one action. */
    static final String LAST_SLIP = "lastSlip";

    private static final Set<String> ACTIONS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(LAST_SLIP)));

    private final LendingAccess access;

    PrinterDevice(LendingAccess access) {
        this.access = access;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String kind() {
        return "printer";
    }

    @Override
    public Map<String, Object> state() {
        SlipPrinter printer = access.requireApp().printer();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("slips", Long.valueOf(printer.count()));
        m.put("kept", Long.valueOf(printer.slips().size()));
        m.put("header", printer.header());
        m.put("last", LendingJson.slip(printer.last()));
        return m;
    }

    @Override
    public Set<String> actions() {
        return ACTIONS;
    }

    @Override
    public Object act(String action, JsonView args) {
        if (!LAST_SLIP.equals(action)) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("param", "action");
            data.put("actions", new ArrayList<String>(ACTIONS));
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "unknown printer action '" + action + "'", data);
        }
        return LendingJson.slip(access.requireApp().printer().last());
    }
}
