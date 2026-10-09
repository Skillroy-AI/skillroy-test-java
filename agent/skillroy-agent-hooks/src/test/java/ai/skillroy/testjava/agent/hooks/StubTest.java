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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.fixture.hooks.DepositRule;
import ai.skillroy.testjava.fixture.hooks.Returns;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hook.stub} (phase6-contracts.md sections 3.4 and 4.2): the value converted once to every return type, the
 * refusals, the dispatcher's CONFIRM_REQUIRED and READ_ONLY, the SPI's own read-only refusal, and
 * {@code hooks.stubbed} by call and by SPI.
 */
class StubTest {

    private static final String[] METHODS = {"count", "total", "shelf", "bin", "grade", "open", "weight", "rate",
        "amount", "serial", "name", "kind", "boxed", "other", "touch"};

    private Harness harness;
    private final Returns returns = new Returns();
    private final Map<String, Object> confirm = map("confirm", true);

    @BeforeEach
    void install() throws Exception {
        new DepositRule();
        harness = new Harness();
        List<HookSpec> specs = new ArrayList<HookSpec>();
        for (String m : METHODS) {
            specs.add(spec("r." + m, HookMode.STUB, Returns.class, m));
        }
        specs.add(spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"));
        harness.install("toys", specs.toArray(new HookSpec[0]));
    }

    @AfterEach
    void close() throws Exception {
        harness.ctx.call("hook.remove", map());
        harness.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> arm(String method, Object value) throws Exception {
        return (Map<String, Object>) harness.ctx.call("hook.stub", map("id", "r." + method, "value", value), confirm);
    }

    private RpcException refused(String id, Object value) {
        return harness.ctx.error("hook.stub", map("id", id, "value", value), confirm);
    }

    @Test
    void everyReturnTypeGetsItsOwnTypeOnce() throws Exception {
        assertEquals("int", arm("count", 3).get("returnType"));
        assertEquals(3, returns.count());
        arm("total", "70000000000");
        assertEquals(70000000000L, returns.total());
        arm("shelf", 2);
        assertEquals(2, returns.shelf());
        arm("bin", 1);
        assertEquals(1, returns.bin());
        arm("grade", "A");
        assertEquals('A', returns.grade());
        arm("open", false);
        assertFalse(returns.open());
        arm("weight", 2.5);
        assertEquals(2.5f, returns.weight(), 0f);
        arm("rate", "0.5");
        assertEquals(0.5d, returns.rate(), 0d);
        assertEquals("java.math.BigDecimal", arm("amount", "1.00").get("returnType"));
        assertEquals(new BigDecimal("1.00"), returns.amount());
        arm("serial", 99);
        assertEquals(BigInteger.valueOf(99), returns.serial());
        arm("name", "saw");
        assertEquals("saw", returns.name());
        arm("kind", "POWER_TOOL");
        assertEquals(Returns.Kind.POWER_TOOL, returns.kind());
        arm("boxed", 9);
        assertEquals(Integer.valueOf(9), returns.boxed());
        arm("boxed", null);
        assertNull(returns.boxed(), "null for a reference type");
        arm("other", null);
        assertNull(returns.other());
        assertEquals("void", ((Map<?, ?>) harness.ctx.call("hook.stub", map("id", "r.touch"), confirm))
                .get("returnType"));
        returns.touch();
        assertEquals(0, returns.touches);
    }

    @Test
    void whatCannotBeConvertedIsInvalidParamsNamingTheValueAndTheReturnType() {
        RpcException nullForInt = refused("r.count", null);
        assertEquals(ErrorCodes.INVALID_PARAMS, nullForInt.error().code());
        assertEquals("value", nullForInt.error().data().get("param"));
        assertEquals("int", nullForInt.error().data().get("returnType"));
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("r.grade", "AB").error().code());
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("r.bin", 300).error().code());
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("r.count", "3.5").error().code());
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("r.amount", "a lot").error().code());
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("r.kind", "NOPE").error().code());
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("r.other", "x").error().code(),
                "any other return type accepts null only");
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("r.touch", 1).error().code(), "a void method: skip only");
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("r.count", map("a", 1)).error().code());
        RpcException notStub = refused("deposit.computed", "1");
        assertEquals(ErrorCodes.INVALID_PARAMS, notStub.error().code());
        assertTrue(notStub.getMessage().contains("OBSERVE"), notStub.getMessage());
        assertEquals(ErrorCodes.INVALID_PARAMS, refused("not.installed", "1").error().code());
        assertEquals(7, returns.count(), "nothing was armed");
    }

    @Test
    void theDispatcherAsksForConfirmAndRefusesInReadOnlyMode() throws Exception {
        RpcException unconfirmed = harness.ctx.error("hook.stub", map("id", "r.count", "value", 1));
        assertEquals(ErrorCodes.CONFIRM_REQUIRED, unconfirmed.error().code());
        assertEquals(7, returns.count());
        harness.ctx.readOnly = true;
        try {
            assertEquals(ErrorCodes.READ_ONLY, harness.ctx.error("hook.stub", map("id", "r.count", "value", 1),
                    confirm).error().code());
            RpcException spi = null;
            try {
                harness.ctx.hooks().stub("r.count", 1, false);
            } catch (RpcException e) {
                spi = e;
            }
            assertEquals(ErrorCodes.READ_ONLY, spi.error().code(), "the SPI refuses a stub in read-only mode too");
            assertEquals(7, returns.count());
        } finally {
            harness.ctx.readOnly = false;
        }
    }

    @Test
    void everyArmAndClearIsPublishedWithWhoDidItAndNeverTheValue() throws Exception {
        arm("amount", "1.00");
        Event byCall = harness.ctx.events.last(Topics.HOOKS_STUBBED);
        assertEquals("r.amount", byCall.payload().get("id"));
        assertEquals(Boolean.TRUE, byCall.payload().get("armed"));
        assertEquals("call", byCall.payload().get("by"));
        assertFalse(byCall.payload().toString().contains("1.00"));
        assertFalse(harness.ctx.hooks().stub("r.amount", null, true), "cleared through the SPI");
        Event bySpi = harness.ctx.events.last(Topics.HOOKS_STUBBED);
        assertEquals(Boolean.FALSE, bySpi.payload().get("armed"));
        assertEquals("spi", bySpi.payload().get("by"));
        assertTrue(harness.ctx.hooks().stub("r.amount", "2.00", false));
        assertEquals(new BigDecimal("2.00"), returns.amount());
        assertEquals(new java.util.ArrayList<Object>(java.util.Arrays.asList("r.amount")),
                harness.ctx.map("hook.health", map()).get("stubs"));
    }
}
