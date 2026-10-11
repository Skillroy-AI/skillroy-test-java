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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.scenario.web.WebPathException;
import ai.skillroy.testjava.scenario.web.WebSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CDP path's halves of the Phase 7 security fixes: S111 (a browser the agent reports {@code masked} has its four
 * masked reads handed to the agent, which refuses them, unless {@code redact: false}), S131 (the inner-size read is
 * built from {@code typeof} and two numbers), S133 (nothing is dispatched after the call's deadline: a page that stalls
 * its own hit never receives the click late), S143 (the PNG codec never writes into ImageIO's disk cache).
 */
class CdpSecurityFixTest {

    private FakeAgent agent;
    private FakeWorld world;
    private FakeWorld.FakePage front;
    private CdpWebPath path;

    @BeforeEach
    void open() throws Exception {
        world = new FakeWorld();
        front = world.page("T1", "data:text/html,front", "Front page");
        FakeWorld.FakePage back = world.page("T2", "data:text/html,back", "Back page");
        agent = new FakeAgent().value("web.cdp.status", FakeAgent.status(9222))
                .value("web.browsers", Map.of("browsers", List.of(
                        FakeAgent.browser("b-front", "T1", "front", true, "data:text/html,front"),
                        FakeAgent.browser("b-back", "T2", "back", false, "data:text/html,back"))));
        TargetProbe probe = new TargetProbe(CdpEndpoint.local(9222), "FakeChrome/152", "1.3", List.of(
                new TargetProbe.Target("T1", "page", front.url, "Front page", "ws://t1", true, 2, null),
                new TargetProbe.Target("T2", "page", back.url, "Back page", "ws://t2", true, 2, null)), 4);
        path = new CdpWebPath((e, o, ids) -> probe, (e, o, p) -> world.attach(p, o), false);
        assertEquals(null, path.open(agent, WebSettings.DEFAULT, null));
    }

    @AfterEach
    void close() {
        path.close();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> hitValue() {
        return map("element", map("role", "button", "name", "Go", "tag", "button", "rect", map("x", 1L, "y", 2L, "w",
                30L, "h", 20L)), "locators", List.of(map("selector", "#go")), "unique", true);
    }

    /** S111: the agent reports the front browser masked; its four reads go to the agent, a person's are served. */
    @Test
    void aBrowserTheAgentReportsMaskedHasItsMaskedReadsHandedToTheAgent() throws Exception {
        Map<String, Object> masked = FakeAgent.browser("b-front", "T1", "front", true, "data:text/html,front");
        masked.put("masked", true);
        agent.value("web.browsers", Map.of("browsers", List.of(masked)));
        front.engine = a -> FakeWorld.ok(Map.of("text", "Ada Example, 12 Lending Lane", "truncated", false));
        front.png = ShapesTest.png(20, 10, 0xff102030);
        for (Map.Entry<String, Map<String, Object>> c : List.of(
                Map.entry("web.dom.text", map()),
                Map.entry("web.ariaSnapshot", map()),
                Map.entry("web.bitmap", map()),
                Map.entry("web.hitTest", map("point", map("x", 10, "y", 12))))) {
            int evaluated = front.evaluated.size();
            String why = assertThrows(WebPathException.class, () -> path.call(c.getKey(), c.getValue(),
                    CallOptions.defaults())).getMessage();
            assertTrue(why.contains("masked") && why.contains("the agent answers " + c.getKey()), why);
            assertEquals(evaluated, front.evaluated.size(), c.getKey() + ": the page is never evaluated");
        }
        assertTrue(front.screenshots.isEmpty(), "no screenshot of a masked page");
        assertEquals("Ada Example, 12 Lending Lane", ((Map<?, ?>) path.call("web.dom.text", map("redact", false),
                CallOptions.defaults()).value()).get("text"), "a person's redact: false is served");
    }

    /** S131: the inner-size read answers null unless both sizes are numbers, so a page chooses no long text. */
    @Test
    void theInnerSizeReadIsBuiltFromTypeofAndTwoNumbers() {
        String script = Scripts.innerSize();
        assertTrue(script.contains("typeof w===\"number\"&&typeof h===\"number\""), script);
        assertTrue(script.contains("catch(e){return null;}"), script);
    }

    /**
     * S133 (the L4 sceptic's probe): a page that holds its own hit past the call's budget gets a {@code TIMEOUT} answer
     * and never receives the click later, whatever the modal state became meanwhile.
     */
    @Test
    void aPointerWhoseCallerTimedOutDispatchesNothingLater() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        front.engine = a -> {
            entered.countDown();
            released.await(5, TimeUnit.SECONDS);
            answered.countDown();
            return FakeWorld.ok(hitValue());
        };
        AgentRpcException e = assertThrows(AgentRpcException.class, () -> path.call("web.pointer", map("action",
                "CLICK", "point", map("x", 10, "y", 12)), CallOptions.defaults().withTimeoutMs(300L)));
        assertEquals(ErrorCodes.TIMEOUT, e.error().code());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        released.countDown();
        assertTrue(answered.await(5, TimeUnit.SECONDS));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until && front.mouse.isEmpty()) {
            Thread.onSpinWait();
        }
        assertTrue(front.mouse.isEmpty(), "no event after the TIMEOUT answer: " + front.mouse);
    }

    /**
     * S143 (P2 11.3): with ImageIO's disk cache pointed at a directory nobody may write into, the scaling still decodes
     * and encodes: no pixel data goes through {@code java.io.tmpdir} (the old {@code ImageIO.read}/{@code write} of a
     * stream failed there, "Can't create cache file!").
     */
    @Test
    void thePngCodecNeverUsesImageIosDiskCache(@TempDir Path tmp) throws Exception {
        Path locked = Files.createDirectory(tmp.resolve("locked"));
        byte[] png = ShapesTest.png(200, 100, 0xff102030);
        File before = ImageIO.getCacheDirectory();
        boolean useCache = ImageIO.getUseCache();
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
            ImageIO.setUseCache(true);
            ImageIO.setCacheDirectory(locked.toFile());
            byte[] scaled = Png.scaleToWidth(png, 50);
            assertEquals(50, Png.size(scaled)[0]);
            try (var listed = Files.list(locked)) {
                assertEquals(0L, listed.count());
            }
        } finally {
            ImageIO.setCacheDirectory(before);
            ImageIO.setUseCache(useCache);
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
