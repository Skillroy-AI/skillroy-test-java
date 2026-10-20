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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.DeviceAdapter;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.InputResult;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RungResult;
import ai.skillroy.testjava.samples.lending.EngineStatus;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingInput;
import ai.skillroy.testjava.samples.lending.ScannerDriver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The desk's barcode scanner as device {@value #ID} (kind {@code scanner}), the sample's ladder device
 * (phase8-contracts.md 7.6, 12.2, decisions D590, D591). Its action {@value #SCAN} {@code {barcode, minRung?,
 * inputId?}} has the ladder {@code [PHYSICAL, SEMANTIC]}: with the desk's scanner driver running it builds the frame
 * a scanner sends, has the driver decode it and post it ({@code PHYSICAL}: where the real device hands data over);
 * without the driver it offers the barcode straight to the inbox ({@code SEMANTIC}, {@code fidelity: DEGRADED}),
 * unless the call asked for {@code minRung: PHYSICAL}, which it then refuses ({@code CAPABILITY_UNAVAILABLE} naming
 * {@value #CAP_PHYSICAL}) before anything is posted. The input is acknowledged as {@code lending.input}'s is, and the
 * result reports {@code {rung, requestedRung, fidelity, reason?, minRung?, accepted, inputId, consumed}}, echoing a
 * {@code minRung} it received. State: {@code {driver, scans}}.
 *
 * <p>A device action has no invocation: its waits are bounded by {@value #ACK_WAIT_MS} ms, it raises no warning, and a
 * late acknowledgement of its input carries no correlation id. Called under {@code ThreadPolicy.APP}.
 */
final class ScannerDevice implements DeviceAdapter {

    /** Device id. */
    static final String ID = "scanner";
    /** The one action. */
    static final String SCAN = "scan";
    /** The capability of the physical rung. */
    static final String CAP_PHYSICAL = "lending.scanner.physical";
    /** The capability's reason without the driver. */
    static final String NO_DRIVER = "scanner driver not enabled: launch the desk with -D"
            + "lending.scannerDriver=true";
    /** The longest wait for the engine to take the scanned input. */
    static final long ACK_WAIT_MS = 5000L;

    private static final Set<String> ACTIONS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(SCAN)));
    private static final List<String> LADDER = Collections.unmodifiableList(Arrays.asList(RungResult.PHYSICAL,
            RungResult.SEMANTIC));

    private final AgentContext ctx;
    private final LendingAccess access;
    private final ListenerSlot slot;
    private final AtomicLong scans = new AtomicLong();

    ScannerDevice(AgentContext ctx, LendingAccess access, ListenerSlot slot) {
        this.ctx = ctx;
        this.access = access;
        this.slot = slot;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String kind() {
        return "scanner";
    }

    @Override
    public Map<String, Object> state() {
        LendingApp app = access.requireApp();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("driver", Boolean.valueOf(app.scannerDriver() != null));
        m.put("scans", Long.valueOf(scans.get()));
        return m;
    }

    @Override
    public Set<String> actions() {
        return ACTIONS;
    }

    @Override
    public List<String> rungs(String action) {
        return SCAN.equals(action) ? LADDER : Collections.<String>emptyList();
    }

    @Override
    public Object act(String action, JsonView args) throws Exception {
        if (!SCAN.equals(action)) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "unknown scanner action '" + action + "'",
                    EngineEvents.map("param", "action", "actions", new ArrayList<String>(ACTIONS)));
        }
        JsonView a = args == null ? JsonView.of(null) : args;
        String barcode = a.requireStr("barcode");
        String minRung = a.str("minRung", null);
        if (minRung != null && !LADDER.contains(minRung)) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "minRung must be one of " + LADDER,
                    EngineEvents.map("param", "minRung", "rungs", new ArrayList<String>(LADDER)));
        }
        String requestedId = InputPosting.requestedId(a.raw().get("inputId"));
        LendingApp app = access.requireApp();
        slot.tryAttach(app);
        EngineStatus status = app.engine().status();
        if (!status.validInputs().contains(LendingInput.BARCODE)) {
            throw LendingPrimitives.notAccepted(app, status, LendingInput.BARCODE);
        }
        final ScannerDriver driver = app.scannerDriver();
        if (driver == null && RungResult.PHYSICAL.equals(minRung)) {
            // phase8-contracts.md 7.6: a required rung is never acted through a shortcut.
            throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "rung PHYSICAL is unavailable: " + NO_DRIVER,
                    EngineEvents.map(RungResult.RUNG, RungResult.PHYSICAL, RungResult.MIN_RUNG, minRung,
                            RungResult.CAPABILITY, CAP_PHYSICAL));
        }
        EngineEvents events = slot.events();
        if (events == null) {
            throw new RpcException(ErrorCodes.NOT_READY, "the lending module's engine listener is not attached");
        }
        Map<String, Object> result;
        InputPosting.Posted posted;
        if (driver != null) {
            byte[] frame = ScannerDriver.frame(barcode);
            LendingInput input = frame == null ? null : ScannerDriver.decode(frame);
            if (input == null) {
                throw new RpcException(ErrorCodes.INPUT_REJECTED, "no scanner frame carries this barcode (1 to "
                        + ScannerDriver.MAX_CODE + " printable ASCII characters)", EngineEvents.map(
                        InputResult.REASON, "malformedFrame", "length", Long.valueOf(barcode.length())));
            }
            posted = InputPosting.post(ctx, events, app, input, requestedId, null, new InputPosting.Target() {
                @Override
                public boolean offer(LendingInput in) {
                    return driver.post(in);
                }
            }, ACK_WAIT_MS);
            result = RungResult.of(RungResult.PHYSICAL, RungResult.PHYSICAL, RungResult.FULL, null);
        } else {
            posted = InputPosting.post(ctx, events, app, LendingInput.barcode(barcode), requestedId, null,
                    InputPosting.inbox(app), ACK_WAIT_MS);
            result = RungResult.of(RungResult.SEMANTIC, RungResult.PHYSICAL, RungResult.DEGRADED,
                    "scanner driver not enabled");
        }
        scans.incrementAndGet();
        if (minRung != null) {
            result.put(RungResult.MIN_RUNG, minRung);
        }
        result.put(InputResult.ACCEPTED, Boolean.TRUE);
        result.put(InputResult.INPUT_ID, posted.inputId);
        result.put(InputResult.CONSUMED, posted.consumed());
        return result;
    }
}
