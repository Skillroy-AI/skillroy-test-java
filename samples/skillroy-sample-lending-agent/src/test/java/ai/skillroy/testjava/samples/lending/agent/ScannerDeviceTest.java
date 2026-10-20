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
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.agent.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scanner device and its rung ladder (phase8-contracts.md 7.6, 12.2; 13.1 row 34). */
class ScannerDeviceTest {

    private static DeviceAdapter scanner(LendingFixture lending) {
        for (DeviceAdapter device : lending.ctx.spi.all(DeviceAdapter.class)) {
            if ("scanner".equals(device.id())) {
                return device;
            }
        }
        throw new AssertionError("no scanner device");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static int lines(LendingFixture lending) throws Exception {
        return ((List<Object>) map(lending.ctx.call("lending.loan", args())).get("lines")).size();
    }

    /** The device's action, run on the module's worker as device.act runs it. */
    private static Object act(final LendingFixture lending, final DeviceAdapter scanner, final Map<String, Object> args)
            throws Exception {
        return lending.ctx.threads.call(ai.skillroy.testjava.agent.api.ThreadPolicy.APP,
                new java.util.concurrent.Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return scanner.act("scan", JsonView.of(args));
                    }
                }, 10000);
    }

    @Test
    void withoutTheDriverTheShortcutActsDegradedAndARequiredPhysicalRungIsRefused() throws Exception {
        LendingFixture lending = LendingFixture.start();
        try {
            DeviceAdapter scanner = scanner(lending);
            assertEquals("scanner", scanner.kind());
            assertEquals(Arrays.asList("PHYSICAL", "SEMANTIC"), scanner.rungs("scan"));
            assertTrue(scanner.rungs("other").isEmpty());
            assertEquals(args("driver", Boolean.FALSE, "scans", 0L), scanner.state());
            Capability physical = lending.ctx.capabilities.get("lending.scanner.physical");
            assertEquals(CapabilityState.UNAVAILABLE, physical.state());
            assertEquals("scanner driver not enabled: launch the desk with -Dlending.scannerDriver=true",
                    physical.reason());
            lending.signOn();

            RpcException refused = assertThrows(RpcException.class, () -> act(lending, scanner,
                    args("barcode", "5000000000017", "minRung", "PHYSICAL")));
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, refused.error().code());
            assertEquals(args("rung", "PHYSICAL", "minRung", "PHYSICAL", "capability", "lending.scanner.physical"),
                    refused.error().data());
            assertEquals(0, lines(lending), "nothing posted below the minimum");

            long before = lending.ctx.events.lastSeq();
            Map<String, Object> result = map(act(lending, scanner, args("barcode", "5000000000017")));
            assertEquals("SEMANTIC", result.get("rung"));
            assertEquals("PHYSICAL", result.get("requestedRung"));
            assertEquals("DEGRADED", result.get("fidelity"));
            assertEquals("scanner driver not enabled", result.get("reason"));
            assertEquals(Boolean.TRUE, result.get("accepted"));
            assertEquals("CONSUMED", result.get("consumed"));
            assertTrue(!result.containsKey("minRung"), "no echo without the parameter");
            assertEquals(result.get("inputId"), lending.ctx.events.next("input.acked", before).payload()
                    .get("inputId"));
            lending.ctx.events.next("input.requested", before);
            assertEquals(1, lines(lending));
            Map<String, Object> semantic = map(act(lending, scanner, args("barcode", "5000000000017", "minRung",
                    "SEMANTIC")));
            assertEquals("SEMANTIC", semantic.get("minRung"), "the echo");
            assertEquals(2L, scanner.state().get("scans"));
        } finally {
            lending.close();
        }
    }

    @Test
    void withTheDriverThePhysicalRungActsAndAMalformedFrameIsRefused() throws Exception {
        LendingFixture lending = LendingFixture.startWithScannerDriver();
        try {
            DeviceAdapter scanner = scanner(lending);
            assertEquals(CapabilityState.OK, lending.ctx.capabilities.get("lending.scanner.physical").state());
            assertEquals(Boolean.TRUE, scanner.state().get("driver"));
            lending.signOn();
            long before = lending.ctx.events.lastSeq();
            Map<String, Object> result = map(act(lending, scanner, args("barcode", "5000000000017", "minRung",
                    "PHYSICAL")));
            assertEquals("PHYSICAL", result.get("rung"));
            assertEquals("FULL", result.get("fidelity"));
            assertEquals("PHYSICAL", result.get("minRung"), "the echo");
            lending.ctx.events.next("input.requested", before);
            assertEquals(1, lines(lending));

            RpcException malformed = assertThrows(RpcException.class, () -> act(lending, scanner,
                    args("barcode", "50000\t00017", "minRung", "PHYSICAL")));
            assertEquals(ErrorCodes.INPUT_REJECTED, malformed.error().code());
            assertEquals("malformedFrame", malformed.error().data().get("reason"));
            char[] over = new char[65];
            java.util.Arrays.fill(over, '5');
            assertEquals("malformedFrame", assertThrows(RpcException.class, () -> act(lending, scanner,
                    args("barcode", new String(over)))).error().data().get("reason"));
            assertEquals(1, lines(lending), "no input posted");
            assertEquals(ErrorCodes.INVALID_PARAMS, assertThrows(RpcException.class, () -> act(lending, scanner,
                    args("barcode", "1", "minRung", "ROBOT"))).error().code());
        } finally {
            lending.close();
        }
    }
}
