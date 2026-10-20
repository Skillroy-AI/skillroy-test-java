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

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.CALL;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.PASSWORD;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.READY_TOPICS;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.WAIT;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The scanner leg (phase8-contracts.md 13.2 item 30, ES): the desk launched with its scanner driver
 * ({@link LendingTarget.Mode#JAVAAGENT_SCANNER}, {@code -Dlending.scannerDriver=true}), once per target Java. The
 * capability {@code lending.scanner.physical} is OK; {@code scanner/device-scan-physical} passes on the PHYSICAL rung
 * with the device's echo of {@code minRung} in its call record and no {@code fidelity} record; {@code device-scan}
 * passes at full fidelity; and a raw {@code device.act} with a barcode no scanner frame carries is refused
 * {@code INPUT_REJECTED malformedFrame} before any input is posted. The Java 8 target proves the module's Java 8 code
 * (the driver's decoding, the device, the acknowledgement) there (item 31).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class ScannerChecks {

    LendingTarget target;

    /** The leg's mode: {@link LendingTarget.Mode#JAVAAGENT_SCANNER}. */
    abstract LendingTarget.Mode mode();

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        target = LendingTarget.launch(mode());
    }

    @AfterAll
    void close() {
        if (target != null) {
            target.close();
        }
    }

    AgentClient client() {
        return target.client;
    }

    private Event send(String source, String value) throws Exception {
        RpcResult result = client().call("lending.input", value == null ? Map.of("source", source)
                : Map.of("source", source, "value", value), CALL);
        assertEquals(Boolean.TRUE, value(result).get("acked"), source);
        Event done = client().await(READY_TOPICS, result.seqBefore(), null, WAIT);
        assertNotNull(done);
        return done;
    }

    private void assertSignedOff() throws Exception {
        Map<String, Object> state = value(client().call("lending.state", Map.of(), CALL));
        assertEquals("SIGNED_OFF", state.get("name"), String.valueOf(state));
    }

    @Test
    @Order(10)
    void thePhysicalRungIsThere() throws Exception {
        Capability physical = client().capabilities().get("lending.scanner.physical");
        assertEquals(CapabilityState.OK, physical.state(), String.valueOf(physical));
        Map<String, Object> devices = value(client().call("device.list", Map.of(), CALL));
        assertTrue(String.valueOf(devices).contains("rungs={scan=[PHYSICAL, SEMANTIC]}"), String.valueOf(devices));
    }

    @Test
    @Order(20)
    void aRequiredPhysicalRungActsThroughTheDriver() throws Exception {
        Runs.Outcome physical = Runs.run(client(), Runs.committed("device-scan-physical"), ItEnv.runDir(mode().slug,
                "device-scan-physical"));
        Runs.assertPassedWithoutSleeping(physical);
        List<Map<String, Object>> acts = Runs.calls(physical.dir(), "device.act");
        assertEquals(1, acts.size());
        Map<?, ?> act = (Map<?, ?>) acts.get(0).get("value");
        assertEquals("PHYSICAL", act.get("rung"));
        assertEquals("FULL", act.get("fidelity"));
        assertEquals("PHYSICAL", act.get("minRung"), "the device's echo of minRung");
        assertEquals("PHYSICAL", ((Map<?, ?>) ((Map<?, ?>) acts.get(0).get("params")).get("args")).get("minRung"));
        List<Map<String, Object>> rungs = Runs.runLog(physical.dir()).stream().filter(r -> "rung".equals(r.get("type")))
                .toList();
        assertEquals(1, rungs.size());
        assertEquals("PHYSICAL", rungs.get(0).get("minRung"));
        assertSignedOff();

        Runs.Outcome scan = Runs.run(client(), Runs.committed("device-scan"), ItEnv.runDir(mode().slug,
                "device-scan"));
        Runs.assertPassedWithoutSleeping(scan);
        assertEquals(Status.PASS, scan.result().status(), "full fidelity with the driver");
        assertSignedOff();
    }

    @Test
    @Order(30)
    void aMalformedFrameIsRefusedBeforeAnyInputIsPosted() throws Exception {
        send("Entry", "100");
        assertEquals("IDLE", send("Entry", PASSWORD).payload().get("state"));
        Object before = value(client().call("lending.loan", Map.of(), CALL)).get("lines");
        AgentRpcException refused = assertThrows(AgentRpcException.class, () -> client().call("device.act", Map.of(
                "id", "scanner", "action", "scan", "args", Map.of("barcode", "5000\t000017", "minRung", "PHYSICAL")),
                CALL));
        assertEquals(ErrorCodes.INPUT_REJECTED, refused.error().code());
        assertEquals("malformedFrame", refused.error().data().get("reason"));
        assertEquals(before, value(client().call("lending.loan", Map.of(), CALL)).get("lines"), "nothing posted");
        assertFalse(String.valueOf(refused.error().data()).contains("5000"), "the frame's data is not echoed");
        send("Key.SIGN_OFF", null);
        assertSignedOff();
    }
}
