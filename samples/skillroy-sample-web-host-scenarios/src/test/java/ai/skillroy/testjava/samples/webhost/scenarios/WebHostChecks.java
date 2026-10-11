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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.cdp.CdpWebPath;
import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.controller.Discovery;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.tools.CallPolicy;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.samples.webhost.WebHostOptions;
import ai.skillroy.testjava.samples.webhost.WebHostPages;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.web.WebPathException;
import ai.skillroy.testjava.scenario.web.WebSettings;
import ai.skillroy.testjava.vision.Capture;
import ai.skillroy.testjava.vision.Captures;
import ai.skillroy.testjava.vision.Grounding;
import ai.skillroy.testjava.vision.Recorder;
import ai.skillroy.testjava.vision.VisionTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The checks of phase4-contracts.md 12.2 against one launch of the web host: items 1 to 5, 7 and 8, and the
 * assembled launcher's checks in the one launch per target Java that runs them (item 6 is {@link CdpAttachSoakIT};
 * item 9, the kit, runs in the web host module); and item 10 of phase7-contracts.md 13.2 ({@code web.hitTest} and
 * {@code web.pointer} on both paths, the browser as a {@code WEB} surface of the Swing capture), with its gap-95 checks
 * (a) to (d), after item 8: a modal dialog over the page, no late click on the CDP path, a middle click a person's only,
 * and a masked browser on every route (the last, on a fresh agent attached with the mask option). A subclass names the
 * load mode, the rendering and whether the host has a remote-debugging port. Windowed rendering on a Java 8 target is skipped (spike BS8 saw no native browser
 * created there). Every wait is an event, a settle or a bounded poll of a socket or a file; nothing sleeps.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class WebHostChecks {

    static final CallOptions CALL = CallOptions.defaults().withTimeoutMs(30_000L);
    static final Duration WAIT = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    WebHostTarget target;
    String java;

    abstract WebHostTarget.Mode mode();

    abstract WebHostOptions.Rendering rendering();

    abstract boolean withPort();

    /** The launch that also runs the assembled launcher's checks: one per target Java. */
    boolean runsTheLauncherChecks() {
        return false;
    }

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        assumeTrue(rendering() == WebHostOptions.Rendering.OSR || ItEnv.targetFeature() >= 9, "windowed JCEF on a"
                + " Java 8 target is unverified (spike BS8 saw no native browser created there): a Java 8 target runs"
                + " off screen only");
        String label = WebHostTarget.label(mode(), rendering(), withPort());
        assumeTrue(ItEnv.selected(label), "not among -Dskillroy.it.launches");
        ItEnv.driverDirectory();
        if (withPort()) {
            ItEnv.warmUpPlaywright();
        }
        target = WebHostTarget.launch(mode(), rendering(), withPort());
        java = String.valueOf(value(client().call("jvm.info", Map.of(), CALL)).get("javaVersion"));
        ItEnv.timing("java=" + java + " launch=" + target.label + " agentAnsweredMs=" + target.agentMs
                + " targetReadyMs=" + target.readyMs);
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

    Path runDir(String what) {
        return ItEnv.runDir(target.label, what);
    }

    // ------------------------------------------------------------------------------------------- item 1

    /** Item 1: the capabilities of the web module against the host's JCEF. */
    @Test
    @Order(1)
    void item1TheWebCapabilities() throws Exception {
        CapabilityMatrix caps = client().capabilities();
        Capability jcef = caps.get("web.adapter.jcef");
        assertEquals(CapabilityState.OK, jcef.state(), String.valueOf(jcef));
        assertTrue(jcef.strategy() != null && jcef.strategy().startsWith("jcef 152"), String.valueOf(jcef));
        Capability jxbrowser = caps.get("web.adapter.jxbrowser");
        assertEquals(CapabilityState.UNAVAILABLE, jxbrowser.state(), String.valueOf(jxbrowser));
        assertEquals("not present", jxbrowser.reason());
        for (String id : List.of("web.browsers", "web.evaluate", "web.events", "web.resize", "web.bitmap")) {
            assertEquals(CapabilityState.OK, caps.get(id).state(), id + ": " + caps.get(id));
        }
        Capability init = caps.get("web.initScript");
        assertEquals(CapabilityState.OK, init.state(), String.valueOf(init));
        assertEquals("DOCUMENT_START", init.strategy());
        Capability cdp = caps.get("web.cdp");
        if (withPort()) {
            assertEquals(CapabilityState.OK, cdp.state(), String.valueOf(cdp));
            assertTrue(String.valueOf(cdp.strategy()).endsWith(":" + target.port), String.valueOf(cdp));
        } else {
            assertEquals(CapabilityState.UNAVAILABLE, cdp.state(), String.valueOf(cdp));
            assertEquals("no remote-debugging endpoint configured", cdp.reason());
        }
        // Phase 7 (phase7-contracts.md 4.4, 4.9): derived, OK where an indexed browser has EVALUATE and INPUT.
        for (String id : List.of("web.hittest", "web.pointer")) {
            assertEquals(CapabilityState.OK, caps.get(id).state(), id + ": " + caps.get(id));
            assertEquals("jcef", caps.get(id).strategy(), id);
        }
    }

    // ------------------------------------------------------------------------------------------- item 2

    /**
     * Item 2: {@code web.browsers} lists the host's browsers once each with their tags, URLs, titles, frames (the
     * {@code data:} iframe), rendering and Swing state; with the port, every browser's {@code cdpTargetId} is a page
     * target of {@code /json/list}.
     */
    @Test
    @Order(2)
    void item2TheBrowsersAreEnumerated() throws Exception {
        List<Map<String, Object>> browsers = browsers(client());
        Map<String, Map<String, Object>> byTag = new LinkedHashMap<>();
        Set<Object> refs = new HashSet<>();
        for (Map<String, Object> b : browsers) {
            assertTrue(refs.add(b.get("ref")), "one ref per browser: " + summary(browsers));
            byTag.put(String.valueOf(b.get("tag")), b);
        }
        // In any order (the contract fixes none): one entry per page, back once although found twice.
        assertEquals(target.pages().size(), browsers.size(), "front, back (one entry, found by the Swing tree and as"
                + " a seed) and, off screen, detached: " + summary(browsers));
        assertEquals(Set.copyOf(target.pages()), byTag.keySet(), "one browser per page: " + summary(browsers));
        String rendering = target.offScreen() ? "OFFSCREEN_HEAVYWEIGHT" : "WINDOWED";
        for (String page : target.pages()) {
            Map<String, Object> b = byTag.get(page);
            assertEquals("jcef", b.get("adapter"));
            assertTrue(String.valueOf(b.get("url")).contains(WebHostPages.marker(page)), page);
            assertEquals(WebHostPages.title(page), b.get("title"), page);
            assertEquals(Boolean.FALSE, b.get("closed"));
            assertEquals(rendering, b.get("rendering"), page);
            assertEquals(page.equals("front"), b.get("visibleInSwing"), page);
            assertEquals(!page.equals("detached"), b.get("component") != null, page + ": component " + b.get(
                    "component"));
            assertTrue(((List<?>) b.get("capabilities")).containsAll(List.of("EVALUATE", "FRAMES", "EVENTS_LOAD",
                    "INIT_SCRIPT_DOCUMENT_START", "RESIZE", "BITMAP", "CDP_TARGET_ID")), page + " " + b);
            List<?> frames = (List<?>) b.get("frames");
            Map<?, ?> main = (Map<?, ?>) frames.get(0);
            assertEquals(Boolean.TRUE, main.get("main"), page + " " + frames);
            assertTrue(frames.stream().anyMatch(f -> "terms".equals(((Map<?, ?>) f).get("name"))
                    && main.get("key").equals(((Map<?, ?>) f).get("parentKey"))), page + ": the iframe " + frames);
        }
        if (withPort()) {
            Set<String> pageTargets = new HashSet<>();
            for (Object t : jsonList(target.port)) {
                if ("page".equals(((Map<?, ?>) t).get("type"))) {
                    pageTargets.add(String.valueOf(((Map<?, ?>) t).get("id")));
                }
            }
            for (Map<String, Object> b : browsers) {
                assertTrue(pageTargets.contains(String.valueOf(b.get("cdpTargetId"))), b.get("tag") + ": "
                        + b.get("cdpTargetId") + " in /json/list " + pageTargets);
            }
        }
        ItEnv.timing("launch=" + target.label + " cdpTargetIds=" + browsers.stream().map(b -> b.get("tag") + ":"
                + (b.containsKey("cdpTargetId") ? "listed" : "absent")).toList());
    }

    // ------------------------------------------------------------------------------------------- item 3

    /**
     * Item 3: W1 passes without sleeping; the path is the agent's without a port and CDP's with one, recorded with
     * its reason or endpoint; every web call names its path; the tapped {@code webhost.borrowed} ended the click's
     * wait and its detail was captured ({@code qty} 2).
     */
    @Test
    @Order(3)
    void item3BorrowInPagePassesWithoutSleeping() throws Exception {
        Path dir = runDir("borrow-in-page");
        Runs.Outcome o = Runs.run(client(), Runs.committed("borrow-in-page"), dir);
        Runs.assertPassedWithoutSleeping(o);
        String expected = withPort() ? "cdp" : "agent";
        Map<String, Object> path = Runs.records(dir, "web.path").get(0);
        assertEquals(expected, path.get("path"), String.valueOf(path));
        if (withPort()) {
            Map<?, ?> cdp = (Map<?, ?>) path.get("cdp");
            assertEquals("127.0.0.1:" + target.port, cdp.get("endpoint"), String.valueOf(path));
            assertTrue(cdp.get("connectMs") instanceof Number, String.valueOf(path));
            ItEnv.timing("java=" + java + " launch=" + target.label + " cdpConnectMs=" + cdp.get("connectMs"));
        } else {
            assertTrue(String.valueOf(path.get("reason")).contains("web.cdp"), String.valueOf(path));
        }
        for (Map<String, Object> call : Runs.runLog(dir)) {
            if ("call".equals(call.get("type")) && String.valueOf(call.get("primitive")).startsWith("web.")) {
                String primitive = String.valueOf(call.get("primitive"));
                boolean agentOnly = List.of("web.browsers", "web.cdp.status", "web.reload").contains(primitive);
                assertEquals(agentOnly ? "agent" : expected, call.get("path"), String.valueOf(call));
            }
        }
        assertEquals(List.of(), Runs.records(dir, "web.fallback"), "no call fell back");
        Map<String, Object> click = Runs.records(dir, "sync").stream().filter(r -> "web.dom.click".equals(
                r.get("primitive"))).findFirst().orElseThrow();
        assertEquals("EVENT", click.get("mode"));
        assertEquals("web.tap", click.get("topic"), String.valueOf(click));
        assertEquals(Map.of("name", "webhost.borrowed"), click.get("match"));
        assertEquals(Boolean.TRUE, click.get("ok"));
        if (withPort()) {
            assertEquals("cdp", click.get("source"), "the CDP path's own tap ended the wait: " + click);
        }
        Map<String, Object> capture = Runs.records(dir, "capture").get(0);
        assertEquals("borrowed", capture.get("slot"));
        assertEquals(Map.of("item", "hammer", "qty", 2L), capture.get("value"), String.valueOf(capture));
        assertEquals(click.get("step"), capture.get("step"));
        if (!withPort()) {
            // The event itself, on the agent's bus.
            Map<String, Object> events = value(client().call("evt.query", Map.of("since", ((Number) click.get(
                    "eventSeq")).longValue() - 1, "topics", "web.tap", "limit", 1L), CALL));
            Map<?, ?> event = (Map<?, ?>) ((List<?>) events.get("events")).get(0);
            Map<?, ?> payload = (Map<?, ?>) event.get("payload");
            assertEquals("webhost.borrowed", payload.get("name"), String.valueOf(event));
            assertEquals(Map.of("item", "hammer", "qty", 2L), payload.get("detail"), String.valueOf(event));
        }
        assertEquals(expected, o.web().get("path"), "the web summary of the run");
    }

    // ------------------------------------------------------------------------------------------- item 4

    /**
     * Item 4: W2 passes on the hidden back browser in both launches; which path served {@code web.resize} is what
     * the run recorded (with a port the CDP path serves it when the back page has a target id, P4 5.2).
     */
    @Test
    @Order(4)
    void item4HiddenBrowserReachablePasses() throws Exception {
        Path dir = runDir("hidden-browser-reachable");
        Runs.Outcome o = Runs.run(client(), Runs.committed("hidden-browser-reachable"), dir);
        Runs.assertPassedWithoutSleeping(o);
        List<Map<String, Object>> resize = Runs.calls(dir, "web.resize");
        assertEquals(1, resize.size(), String.valueOf(resize));
        Object servedBy = resize.get(0).get("path");
        if (!withPort()) {
            assertEquals("agent", servedBy);
        }
        assertEquals(Map.of("applied", "OVERRIDE", "viewport", Map.of("w", 1024L, "h", 768L)),
                resize.get(0).get("value"), String.valueOf(resize));
        ItEnv.timing("java=" + java + " launch=" + target.label + " W2 web.resize path=" + servedBy
                + " web.path=" + Runs.records(dir, "web.path").get(0).get("path"));
    }

    // ------------------------------------------------------------------------------------------- item 5

    /**
     * Item 5: the negative scenarios end as section 9.3 says, with the web evidence of section 6.4 for the failed
     * ones: the aria JSON decodes and names the Borrow button, the PNG decodes with at least 8 colours; a flag that
     * throws to the end is SYNC_TIMEOUT with the last poll's TypeError and one that does not compile SCRIPT_ERROR at
     * once, on either path (A35); pinning {@code cdp} without a port is PRECONDITION_FAILED naming {@code web.cdp} (in
     * the launches without a port).
     */
    @Test
    @Order(5)
    void item5TheNegativeScenariosFailAsSpecified() throws Exception {
        Path dir = runDir("no-such-button");
        Runs.Outcome missing = Runs.run(client(), Runs.negative("no-such-button.yaml"), dir);
        assertEquals(Status.FAIL, missing.result().status(), missing.describe());
        assertEquals(FailureKind.ASSERTION, missing.result().failureKind(), missing.describe());
        assertTrue(missing.result().message().contains("NOT_FOUND"), missing.describe());
        assertWebEvidence(dir.resolve("failure"));

        Runs.Outcome ambiguous = Runs.run(client(), Runs.negative("two-inputs.yaml"), runDir("two-inputs"));
        assertEquals(Status.FAIL, ambiguous.result().status(), ambiguous.describe());
        assertEquals(FailureKind.ASSERTION, ambiguous.result().failureKind(), ambiguous.describe());
        assertTrue(ambiguous.result().message().contains("AMBIGUOUS"), ambiguous.describe());
        assertWebEvidence(ambiguous.dir().resolve("failure"));

        Runs.Outcome script = Runs.run(client(), Runs.negative("script-error.yaml"), runDir("script-error"));
        assertEquals(Status.FAIL, script.result().status(), script.describe());
        assertEquals(FailureKind.ASSERTION, script.result().failureKind(), script.describe());
        assertTrue(script.result().message().contains("SCRIPT_ERROR"), script.describe());
        assertTrue(script.result().message().contains("webhost negative"), script.describe());
        assertWebEvidence(script.dir().resolve("failure"));

        // A35 (section 16.1): both paths poll the same guard, so a flag that throws to the end and one that does not
        // compile end alike with and without a port, on the path the launch takes.
        String path = withPort() ? "cdp" : "agent";
        Runs.Outcome never = Runs.run(client(), Runs.negative("flag-never-ready.yaml"), runDir("flag-never-ready"));
        assertEquals(Status.FAIL, never.result().status(), never.describe());
        assertEquals(FailureKind.SYNC_TIMEOUT, never.result().failureKind(), never.describe());
        assertTrue(never.result().message().contains("(last error: TypeError: "), never.describe());
        Map<?, ?> flag = (Map<?, ?>) never.result().detail().get("waitFlag");
        assertEquals(Boolean.FALSE, flag.get("ready"), never.describe());
        assertTrue(String.valueOf(flag.get("lastError")).startsWith("TypeError: "), never.describe());
        assertTrue(((Number) flag.get("polls")).longValue() >= 2, "polled on through the throws: " + never.describe());
        assertEquals(path, Runs.calls(never.dir(), "web.waitFlag").get(0).get("path"), never.describe());
        assertWebEvidence(never.dir().resolve("failure"));

        Runs.Outcome syntax = Runs.run(client(), Runs.negative("flag-does-not-compile.yaml"),
                runDir("flag-does-not-compile"));
        assertEquals(Status.FAIL, syntax.result().status(), syntax.describe());
        assertEquals(FailureKind.ASSERTION, syntax.result().failureKind(), syntax.describe());
        assertTrue(syntax.result().message().contains("SCRIPT_ERROR"), syntax.describe());
        assertTrue(syntax.result().message().contains("SyntaxError"), syntax.describe());
        Map<String, Object> call = Runs.calls(syntax.dir(), "web.waitFlag").get(0);
        assertEquals(path, call.get("path"), syntax.describe());
        assertTrue(((Number) call.get("durationMs")).longValue() < 10_000, "at once, not after the flag's 60 s: "
                + call);
        ItEnv.timing("java=" + java + " launch=" + target.label + " A35 path=" + path + " neverReadyPolls="
                + flag.get("polls") + " compileErrorMs=" + call.get("durationMs"));

        if (!withPort()) {
            Runs.Outcome pinned = Runs.run(client(), Runs.negative("cdp-without-port.yaml"),
                    runDir("cdp-without-port"));
            assertEquals(Status.ERROR, pinned.result().status(), pinned.describe());
            assertEquals(FailureKind.PRECONDITION_FAILED, pinned.result().failureKind(), pinned.describe());
            assertTrue(pinned.result().message().contains("web.cdp"), pinned.describe());
            assertTrue(Runs.calls(pinned.dir(), "web.dom.click").isEmpty(), "no step ran");
        }
    }

    /** {@code web-browsers.json}, and the front browser's aria JSON (naming Borrow) and PNG (8+ colours). */
    static void assertWebEvidence(Path failure) throws Exception {
        assertTrue(Files.isRegularFile(failure.resolve("web-browsers.json")), "web-browsers.json in " + failure);
        Json.parse(Files.readString(failure.resolve("web-browsers.json")));
        List<Path> aria;
        List<Path> pngs;
        try (Stream<Path> files = Files.list(failure)) {
            List<Path> all = files.sorted().toList();
            aria = all.stream().filter(p -> p.getFileName().toString().matches("web-.+-aria\\.json")).toList();
            pngs = all.stream().filter(p -> p.getFileName().toString().matches("web-.+\\.png")).toList();
        }
        assertEquals(1, aria.size(), "one browser visible in Swing: " + aria);
        String text = Files.readString(aria.get(0));
        Object nodes = Json.parse(text);
        assertNotNull(nodes);
        assertTrue(text.contains("\"Borrow\""), "the aria snapshot names the Borrow button");
        assertEquals(1, pngs.size(), String.valueOf(pngs));
        BufferedImage image = ImageIO.read(pngs.get(0).toFile());
        assertNotNull(image, pngs.get(0) + " decodes");
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                colours.add(image.getRGB(x, y));
            }
        }
        assertTrue(colours.size() >= 8, pngs.get(0) + ": " + colours.size() + " colours");
    }

    // ------------------------------------------------------------------------------------------- item 7

    /**
     * Item 7: a secret from {@code credentials.yaml} filled into the page through {@code webFill} (the page logs the
     * item's value on purpose) is {@code <redacted>} in {@code run.jsonl}, absent from the agent's log, masked in
     * the {@code web.console} events that echo it, and in no evidence file. A call with a secret parameter is the
     * agent's even with a port (the CDP path hands it over: a {@code web.fallback} record).
     */
    @Test
    @Order(6)
    void item7TheSecretFilledIntoThePageIsMaskedEverywhere() throws Exception {
        String secret = secret();
        Path credentialsDir = Files.createTempDirectory("webhost-it-credentials-");
        try {
            Path file = credentialsDir.resolve(Credentials.FILE_NAME);
            Files.writeString(file, "version: 1\ncredentials:\n  members:\n    \"m-1\": { password: \"" + secret
                    + "\" }\n", StandardCharsets.UTF_8);
            ItEnv.ownerOnly(file);
            Credentials credentials = Credentials.load(file);
            Files.delete(file);
            long since = lastSeq();
            Path dir = runDir("fill-a-secret");
            Runs.Outcome o = Runs.run(client(), Runs.negative("fill-a-secret.yaml"), dir,
                    BindOptions.defaults().withCredentials(credentials));
            assertEquals(Status.FAIL, o.result().status(), o.describe());
            assertEquals(FailureKind.ASSERTION, o.result().failureKind(), o.describe());

            Map<String, Object> fill = Runs.calls(dir, "web.dom.fill").get(0);
            assertEquals("<redacted>", ((Map<?, ?>) fill.get("params")).get("text"), String.valueOf(fill));
            assertEquals(List.of("text"), fill.get("secretParams"));
            assertEquals("agent", fill.get("path"), "a call with a secret is the agent's: " + fill);
            if (withPort()) {
                List<Map<String, Object>> fallbacks = Runs.records(dir, "web.fallback");
                assertEquals(1, fallbacks.size(), String.valueOf(fallbacks));
                assertEquals("web.dom.fill", fallbacks.get(0).get("primitive"));
            }
            // The page logged the value: the agent's web.console event masks it.
            Map<String, Object> events = value(client().call("evt.query", Map.of("since", since, "topics",
                    "web.console", "limit", 1000L), CALL));
            List<String> texts = new ArrayList<>();
            for (Object e : (List<?>) events.get("events")) {
                texts.add(String.valueOf(((Map<?, ?>) ((Map<?, ?>) e).get("payload")).get("text")));
            }
            assertTrue(texts.stream().anyMatch(t -> t.startsWith("webhost front item: ") && t.contains("<redacted>")),
                    "the page's echo of the value, masked: " + texts);
            assertTrue(texts.stream().noneMatch(t -> t.contains(secret)), "no web.console text holds the secret");

            List<?> lines = (List<?>) value(client().call("agent.log", Map.of("n", 2000L), CALL)).get("lines");
            for (Object line : lines) {
                assertFalse(String.valueOf(line).contains(secret), "agent.log holds the secret");
            }
            List<Path> outputs;
            try (Stream<Path> walk = Files.walk(dir)) {
                outputs = walk.filter(Files::isRegularFile).toList();
            }
            assertTrue(outputs.stream().anyMatch(p -> p.toString().contains("failure")), "evidence was written: "
                    + outputs);
            byte[] needle = secret.getBytes(StandardCharsets.UTF_8);
            for (Path p : outputs) {
                assertFalse(contains(Files.readAllBytes(p), needle), p + " holds the secret");
            }
            assertTrue(Files.readString(dir.resolve("run.jsonl")).contains("<redacted>"));
        } finally {
            WebHostTarget.deleteTree(credentialsDir);
        }
    }

    /** A made-up secret of 16 characters, fresh per run (8 or more are masked inside longer texts too). */
    static String secret() {
        StringBuilder s = new StringBuilder("tq-");
        for (int i = 0; i < 13; i++) {
            s.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return s.toString();
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------- the launcher

    /**
     * Once per target Java, through the assembled launcher: {@code run --mode replay} of W1 with {@code --pack}
     * naming the {@code skillroy-cdp} jar (with Playwright's) and the pack jar exits 0 and its {@code --json} shows
     * {@code path: cdp}; without the CDP jars the same command exits 0 with {@code path: agent}.
     */
    // ------------------------------------------------------------------------------------------- item 10 (Phase 7)

    /** The Borrow button's centre in the front page's viewport, {x, y} in whole CSS pixels. */
    Map<String, Object> borrowCentre() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("by", Map.of("tag", "front"));
        params.put("expression", "(function () { var r = document.getElementById('borrow').getBoundingClientRect();"
                + " return {x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2)}; })()");
        return Json.parseObject(String.valueOf(value(client().call("web.evaluate", params, CALL)).get("json")));
    }

    /** The front page's pointer-event counter ({@code webhostPointer()}): "type:isTrusted" entries. */
    List<String> pointerLog() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("by", Map.of("tag", "front"));
        params.put("expression", "webhostPointer()");
        String log = (String) Json.parse(String.valueOf(value(client().call("web.evaluate", params, CALL))
                .get("json")));
        return log.isEmpty() ? List.of() : List.of(log.split(","));
    }

    /**
     * Item 10 of phase7-contracts.md 13.2 (P4 12.2's new item): {@code web.hitTest} at the Borrow button's centre answers
     * the button with a unique selector, and {@code web.pointer {action: CLICK}} there makes the page count a trusted
     * {@code pointerdown}, {@code mousedown}, {@code mouseup} and {@code click}, on the agent's path; with a port the same
     * two calls on the CDP path answer the same values (D48) and the page counts four more trusted events.
     */
    @Test
    @Order(7)
    void item10HitTestAndPointerOnBothPathsAnswerTheSameShapes() throws Exception {
        Map<String, Object> front = Map.of("tag", "front");
        Map<String, Object> point = borrowCentre();
        Map<String, Object> hitParams = Map.of("by", front, "point", point);
        Map<String, Object> agentHit = value(client().call("web.hitTest", hitParams, CALL));
        Map<?, ?> element = (Map<?, ?>) agentHit.get("element");
        assertEquals("button", element.get("tag"), String.valueOf(agentHit));
        assertEquals("Borrow", element.get("name"), String.valueOf(agentHit));
        assertEquals("borrow", element.get("id"));
        assertEquals(Boolean.TRUE, agentHit.get("unique"));
        List<?> locators = (List<?>) agentHit.get("locators");
        assertTrue(locators.contains(Map.of("role", "button", "name", "Borrow")), String.valueOf(locators));
        assertTrue(locators.contains(Map.of("selector", "#borrow")), String.valueOf(locators));
        assertEquals(point, agentHit.get("viewportPoint"));

        int before = pointerLog().size();
        Map<String, Object> clickParams = Map.of("by", front, "action", "CLICK", "point", point);
        Map<String, Object> agentClick = value(client().call("web.pointer", clickParams, CALL));
        assertEquals("PROTOCOL", agentClick.get("via"));
        assertEquals(List.of("mouseMoved", "mousePressed", "mouseReleased"), agentClick.get("dispatched"));
        assertEquals(Map.of("role", "button", "name", "Borrow", "tag", "button", "id", "borrow"),
                agentClick.get("hit"));
        List<String> trusted = List.of("pointerdown:true", "mousedown:true", "mouseup:true", "click:true");
        assertEquals(trusted, pointerLog().subList(before, before + 4), "a pointer click, not a DOM click");
        ItEnv.timing("java=" + java + " launch=" + target.label + " item10 agent hit=" + element.get("rect"));

        if (withPort()) {
            CdpWebPath path = new CdpWebPath();
            try {
                assertEquals(null, path.open(client(), WebSettings.DEFAULT, null), "the CDP path opens");
                assertEquals(agentHit, path.call("web.hitTest", hitParams, CallOptions.defaults()).value(),
                        "D48: the hit-test answers the same value on both paths");
                int beforeCdp = pointerLog().size();
                assertEquals(agentClick, path.call("web.pointer", clickParams, CallOptions.defaults()).value(),
                        "D48: the pointer answers the same value on both paths");
                assertEquals(trusted, pointerLog().subList(beforeCdp, beforeCdp + 4), "the CDP path's click");
            } finally {
                path.close();
            }
        }
    }

    /**
     * Item 10's Swing half (phase7-contracts.md 4.8, 13.2 item 10; the Swing side's extended capture and
     * {@code swing.hitTest}, whose {@code OK} it asserts first): {@code swing.capture} of the host's window lists the
     * front browser as a {@code WEB} surface with its ref and {@code redacted: false}; {@code swing.hitTest} at the
     * Borrow button's point in the window answers {@code surface: WEB} with {@code surfaceLocal}, and
     * {@code web.hitTest {space: COMPONENT}} with that point answers the button.
     */
    @Test
    @Order(8)
    void item10TheSwingCaptureListsTheBrowserAndASwingPointReachesThePage() throws Exception {
        // The fix wave of the Phase 7 review (FB, L3-S2): the Swing side has landed, so a web host target without
        // swing.hitTest fails this item instead of skipping it.
        Capability hitTest = client().capabilities().get("swing.hittest");
        assertNotNull(hitTest, "the web host target declares swing.hittest (the Swing side's hit-test)");
        assertEquals(CapabilityState.OK, hitTest.state(), String.valueOf(hitTest));
        String frontRef = null;
        String backRef = null;
        for (Map<String, Object> b : browsers(client())) {
            if ("front".equals(b.get("tag"))) {
                frontRef = String.valueOf(b.get("ref"));
            }
            if ("back".equals(b.get("tag"))) {
                backRef = String.valueOf(b.get("ref"));
            }
        }
        assertNotNull(frontRef);
        Map<String, Object> capture = value(client().call("swing.capture", Map.of(), CALL));
        Map<?, ?> surface = null;
        for (Object o : (List<?>) capture.get("surfaces")) {
            Map<?, ?> m = (Map<?, ?>) o;
            if ("WEB".equals(m.get("kind")) && frontRef.equals(m.get("browser"))) {
                surface = m;
            }
            // Phase 7 security fix, S129 (SG): a browser in a card that is not shown is no surface
            assertFalse("WEB".equals(m.get("kind")) && backRef != null && backRef.equals(m.get("browser")),
                    "the back browser's card is hidden: " + capture.get("surfaces"));
        }
        assertNotNull(surface, "the front browser is a WEB surface: " + capture.get("surfaces"));
        assertEquals(Boolean.FALSE, surface.get("redacted"));
        // Phase 7 security fix, S108 (SG): nothing of the window covers the front page
        assertEquals(List.of(), surface.get("covered"), String.valueOf(surface));
        // Phase 7 security fix, S108 follow-up (SG): the front browser is not clipped, so its bounds are its rect
        assertEquals(surface.get("rect"), surface.get("bounds"), String.valueOf(surface));
        Map<?, ?> rect = (Map<?, ?>) surface.get("rect");
        Map<?, ?> origin = (Map<?, ?>) capture.get("origin");
        Map<String, Object> centre = borrowCentre();
        long wx = number(origin.get("x")) + number(rect.get("x")) + number(centre.get("x"));
        long wy = number(origin.get("y")) + number(rect.get("y")) + number(centre.get("y"));
        Map<String, Object> hit = value(client().call("swing.hitTest", Map.of("point", Map.of("x", wx, "y", wy),
                "space", Map.of("window", capture.get("window"))), CALL));
        assertEquals("WEB", hit.get("surface"), String.valueOf(hit));
        assertEquals(frontRef, hit.get("browser"));
        Object local = hit.get("surfaceLocal");
        Map<String, Object> webHit = value(client().call("web.hitTest", Map.of("browser", frontRef, "point", local,
                "space", "COMPONENT"), CALL));
        assertEquals("button", ((Map<?, ?>) webHit.get("element")).get("tag"), String.valueOf(webHit));
        assertEquals(Boolean.FALSE, webHit.get("scaleAssumed"), "the image's scale is 1");
    }

    private static long number(Object v) {
        return ((Number) v).longValue();
    }

    @Test
    @Order(9)
    void theAssembledLauncherRunsW1OverCdpAndWithoutIt() throws Exception {
        assumeTrue(runsTheLauncherChecks(), "the launcher checks run once per target Java");
        Path launcher = ItEnv.launcher();
        assertTrue(Files.isExecutable(launcher), "the assembled launcher: " + launcher);
        assertTrue(Files.isRegularFile(ItEnv.packJar()), "the pack jar: " + ItEnv.packJar());
        List<Path> cdp = ItEnv.cdpJars();
        assertTrue(cdp.stream().anyMatch(p -> p.getFileName().toString().startsWith("skillroy-cdp")), "" + cdp);
        Path work = runDir("cli");
        WebHostTarget.deleteTree(work);
        Path yaml = work.resolve("module").resolve("scenarios").resolve("borrow-in-page.yaml");
        Files.createDirectories(yaml.getParent());
        Files.copy(ItEnv.scenario("borrow-in-page.yaml"), yaml);
        List<String> connection = List.of("--pid", String.valueOf(target.pid()), "--discovery-dir",
                target.discoveryDir.toString());

        List<String> withCdp = new ArrayList<>(List.of("run", yaml.toString(), "--mode", "replay", "--json",
                "--out", work.resolve("out-cdp").toString()));
        withCdp.addAll(connection);
        for (Path jar : cdp) {
            withCdp.addAll(List.of("--pack", jar.toString()));
        }
        withCdp.addAll(List.of("--pack", ItEnv.packJar().toString()));
        long t0 = System.nanoTime();
        Cli.Result over = Cli.run(launcher, withCdp, work.resolve("logs"));
        ItEnv.timing("java=" + java + " launcher run over cdp ms=" + (System.nanoTime() - t0) / 1_000_000L);
        assertEquals(0, over.exit(), over.toString());
        assertEquals("cdp", webPath(over), over.toString());

        List<String> withoutCdp = new ArrayList<>(List.of("run", yaml.toString(), "--mode", "replay", "--json",
                "--out", work.resolve("out-agent").toString()));
        withoutCdp.addAll(connection);
        withoutCdp.addAll(List.of("--pack", ItEnv.packJar().toString()));
        Cli.Result agentOnly = Cli.run(launcher, withoutCdp, work.resolve("logs"));
        assertEquals(0, agentOnly.exit(), agentOnly.toString());
        assertEquals("agent", webPath(agentOnly), agentOnly.toString());
    }

    /** {@code results[0].web.path} of a {@code run --json} output. */
    static String webPath(Cli.Result result) {
        Map<String, Object> document = Json.parseObject(result.out());
        Map<?, ?> first = (Map<?, ?>) ((List<?>) document.get("results")).get(0);
        Map<?, ?> web = (Map<?, ?>) first.get("web");
        return web == null ? null : String.valueOf(web.get("path"));
    }

    // ------------------------------------------------------------------------------------------- item 8

    /**
     * Item 8: after {@code agent.disable} a fresh attach finds the same three browsers (tag, native id, URL) with the
     * new agent's refs, and nothing of the old agent is left in the page: a tap installed through the old agent
     * (a glob, served by a {@code dispatchEvent} wrapper, and an exact name, served by a listener) is gone, so the
     * page counts its own two {@code webhost.*} listeners again and no wrapper ({@code dispatchWrapped: false}).
     */
    @Test
    @Order(10)
    void item8ASecondAttachAfterDisableFindsTheBrowsersAndNoListenerOfTheOldAgent() throws Exception {
        Map<String, Object> front = Map.of("tag", "front");
        assertEquals(Map.of("listeners", 2L, "dispatchWrapped", false), diagnostics(client(), front),
                "the application's own listeners before the tap");
        Map<String, Object> tap = new LinkedHashMap<>();
        tap.put("by", front);
        tap.put("patterns", List.of("webhost.*", "webhost.borrowed"));
        client().call("web.tap", tap, CALL);
        assertEquals(Map.of("listeners", 3L, "dispatchWrapped", true), diagnostics(client(), front),
                "the glob wraps dispatchEvent, the exact name adds a listener");
        Map<String, Map<String, Object>> before = new LinkedHashMap<>();
        for (Map<String, Object> b : browsers(client())) {
            before.put(String.valueOf(b.get("tag")), b);
        }

        disableAndAwaitStopped();
        target.endpoint = target.attach();
        target.client = AgentClient.connect(target.endpoint);
        WebHostTarget.awaitReady(client());

        Map<String, Map<String, Object>> after = new LinkedHashMap<>();
        for (Map<String, Object> b : browsers(client())) {
            after.put(String.valueOf(b.get("tag")), b);
        }
        assertEquals(before.keySet(), after.keySet(), "the same browsers");
        for (String tag : before.keySet()) {
            assertEquals(before.get(tag).get("nativeId"), after.get(tag).get("nativeId"), tag);
            assertEquals(before.get(tag).get("url"), after.get(tag).get("url"), tag);
            assertNotNull(after.get(tag).get("ref"), tag);
        }
        ItEnv.timing("launch=" + target.label + " refs before " + refs(before) + " after " + refs(after));
        assertEquals(Map.of("listeners", 2L, "dispatchWrapped", false), diagnostics(client(), front),
                "no listener and no wrapper of the old agent");
    }

    private static List<Object> refs(Map<String, Map<String, Object>> byTag) {
        return byTag.values().stream().map(b -> b.get("ref")).toList();
    }

    /** {@code webhostDiagnostics()} of a page through the agent's {@code web.evaluate}. */
    static Map<String, Object> diagnostics(AgentClient agent, Map<String, Object> by) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("by", by);
        params.put("expression", "webhostDiagnostics()");
        Map<String, Object> value = value(agent.call("web.evaluate", params, CALL));
        return Json.parseObject(String.valueOf(value.get("json")));
    }

    /**
     * {@code agent.disable}, then waits (bounded) until the agent is gone: its discovery file deleted and its port
     * closed. There is no event left to wait for.
     */
    void disableAndAwaitStopped() throws Exception {
        Endpoint old = target.endpoint;
        RpcResult disabled = client().call("agent.disable", Map.of(), CALL.withConfirm(true));
        assertEquals(Boolean.TRUE, value(disabled).get("stopping"));
        assertTrue(client().isLost(), "a successful agent.disable latches the client that sent it");
        client().close();
        awaitCondition("the agent to stop", WAIT, () -> Discovery.forPid(target.pid(), target.discoveryDir)
                .isEmpty() && !answers(old));
    }

    static boolean answers(Endpoint endpoint) {
        try (AgentClient c = AgentClient.connect(endpoint)) {
            return true;
        } catch (AgentLostException | RuntimeException e) {
            return false;
        }
    }

    /** Waits for a condition that has no event: at most {@code timeout}, re-checked every 50 ms or on exit. */
    void awaitCondition(String what, Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        CountDownLatch exited = new CountDownLatch(1);
        target.launched.process().onExit().thenRun(exited::countDown);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("timed out after " + timeout.toMillis() + " ms waiting for " + what);
            }
            if (exited.await(50, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("the target exited while waiting for " + what);
            }
        }
    }

    // ------------------------------------------------------------------------- gap 95 (Phase 7 follow-up)

    /** A pointer click as the front page's counter records it: four trusted events. */
    static final List<String> TRUSTED_CLICK = List.of("pointerdown:true", "mousedown:true", "mouseup:true",
            "click:true");
    /** The three events of {@code web.pointer}'s {@code CLICK}. */
    static final List<String> DISPATCHED_CLICK = List.of("mouseMoved", "mousePressed", "mouseReleased");
    /** The solid grey a capture blanks a masked component with (phase7-contracts.md 3.6), as RGB. */
    static final int GREY = 0x808080;
    /** How long the front page stalls its own hit in check (b). */
    static final long STALL_MS = 4_000L;
    /** The budget the CDP path's caller gives up after in check (b). */
    static final long LATE_BUDGET_MS = 1_000L;
    /** The budget of the person's middle click recorded in (e), and how long (e) waits for the page afterwards. */
    static final long MIDDLE_BUDGET_MS = 5_000L;
    static final long MIDDLE_RECOVERY_MS = 20_000L;
    /**
     * The agent option and value with which the sample's module masks the front card (phase4-contracts.md 9.2; the
     * module's {@code OPTION_MASK} and {@code MASK_FRONT}, spelled out: the module's class is not on this side).
     */
    static final String MASK_OPTION = "webhost.mask";
    static final String MASK_FRONT = "front";

    /**
     * Gap 95 (a), phase7-contracts.md 13.2 item 10 (S108 on one desktop, S109, the modal check of 4.3, D404): the
     * host's notice, a modal {@code JOptionPane} over the page. While it shows, {@code web.browsers} names it as the
     * front browser's blocker; the agent's pointer is {@code MODAL_OPEN} and the CDP path hands the same call to the
     * agent; the vision capture reports the dialog's part of the page as {@code covered} and the composite keeps the
     * dialog's own pixels there while it shows the page around it; the recorder refuses a page click under the dialog
     * before it reads anything; the page counts no pointer event. Once the notice is answered the page takes a pointer
     * click again (the control).
     */
    @Test
    @Order(11)
    void gap95aAModalDialogOverThePageKeepsItsPixelsAndTakesNoPointer() throws Exception {
        String frontRef = ref("front");
        Map<String, Object> point = borrowCentre();
        Map<String, Object> click = Map.of("by", Map.of("tag", "front"), "action", "CLICK", "point", point);
        int before = pointerLog().size();
        RpcResult opened = client().call("swing.click", Map.of("by", Map.of("id", "notice")), CALL);
        Event shown = client().await("window.opened", opened.seqBefore(), Map.of("modal", true), WAIT);
        assertNotNull(shown, "the notice opened: " + opened.value());
        String dialog = String.valueOf(shown.payload().get("ref"));
        ReadFacade facade = new ReadFacade(client());
        Grounding grounding = new Grounding(facade);
        try {
            assertAll("gap 95 (a), a modal dialog over the page, " + target.label,
                    () -> {
                        Map<?, ?> blocked = (Map<?, ?>) browser("front").get("blockedBy");
                        assertNotNull(blocked, "web.browsers names the front browser's blocker");
                        assertEquals(dialog, blocked.get("dialog"), String.valueOf(blocked));
                    },
                    () -> {
                        AgentRpcException e = assertThrows(AgentRpcException.class,
                                () -> client().call("web.pointer", click, CALL), "the agent's pointer");
                        assertEquals(ErrorCodes.MODAL_OPEN, e.error().code(), e.getMessage());
                        assertEquals(dialog, e.error().data().get("dialog"), e.getMessage());
                        assertEquals(frontRef, e.error().data().get("browser"), e.getMessage());
                    },
                    () -> {
                        if (withPort()) {
                            CdpWebPath path = new CdpWebPath();
                            try {
                                assertNull(path.open(client(), WebSettings.DEFAULT, null), "the CDP path opens");
                                WebPathException e = assertThrows(WebPathException.class,
                                        () -> path.call("web.pointer", click, CallOptions.defaults()),
                                        "the CDP path hands a blocked browser's pointer to the agent");
                                assertTrue(String.valueOf(e.getMessage()).contains("modal dialog"), e.getMessage());
                            } finally {
                                path.close();
                            }
                        }
                    },
                    () -> theCompositeKeepsTheDialogsPixels(grounding, frontRef, dialog),
                    () -> {
                        Capture capture = grounding.capture(false);
                        Capture.Surface s = surfaceOf(capture, frontRef);
                        assertNotNull(s, "the front browser is a WEB surface: " + capture.surfaces());
                        int[] px = pixel(capture, s, point);
                        Grounding.Grounded g = grounding.ground(capture, px[0], px[1], false);
                        assertEquals("WEB", g.hit().get("surface"), String.valueOf(g.hit()));
                        assertTrue(g.hit().get("blockedBy") instanceof Map<?, ?> m && dialog.equals(m.get("ref")),
                                "the hit names the dialog as its blocker: " + g.hit());
                        int reads = facade.reads.size();
                        Recorder.Recorded r = recorder(facade).pointer(g, px[0], px[1],
                                new Recorder.Click("LEFT", 1, List.of()), null);
                        assertTrue(r.error(), String.valueOf(r));
                        assertEquals(Recorder.PAGE_BLOCKED, r.text());
                        assertEquals(List.of(), facade.reads.subList(reads, facade.reads.size()), "S109: refused "
                                + "before the page is read");
                        assertEquals(List.of(), facade.executed, "nothing acted");
                    },
                    () -> assertEquals(before, pointerLog().size(), "the page counted no pointer event while the"
                            + " notice was open: " + pointerLog()));
        } finally {
            RpcResult dismissed = client().call("swing.dismiss", Map.of("dialog", dialog), CALL);
            assertNotNull(client().await("window.closed", dismissed.seqBefore(), Map.of("ref", dialog), WAIT),
                    "the notice closed: " + dismissed.value());
        }
        assertNull(browser("front").get("blockedBy"), "nothing blocks the front browser once the notice is answered");
        Map<String, Object> clicked = value(client().call("web.pointer", click, CALL));
        assertEquals(DISPATCHED_CLICK, clicked.get("dispatched"), String.valueOf(clicked));
        assertEquals(TRUSTED_CLICK, pointerLog().subList(before, before + 4), "the control: a pointer click again");
    }

    /**
     * S108 on one desktop: in the vision capture ({@code SCREEN}) the notice is the top window and the front
     * surface's {@code covered} is exactly the notice's part of the page; in the model image every pixel of
     * {@code covered} is the capture's own (the dialog), and the rest of the surface shows the page's
     * {@code web.bitmap}. The dialog's pixels differ from the page's there, so the comparison says something.
     */
    void theCompositeKeepsTheDialogsPixels(Grounding grounding, String frontRef, String dialog) throws Exception {
        Capture capture = grounding.capture(false);
        assertEquals(1.0, capture.scale(), "the capture is at scale 1");
        Capture.Region top = capture.windows().get(capture.windows().size() - 1);
        assertEquals(dialog, top.ref(), "the notice is the top window: " + capture.windows());
        Capture.Surface s = surfaceOf(capture, frontRef);
        assertNotNull(s, "the front browser is a WEB surface: " + capture.surfaces());
        assertFalse(s.redacted(), String.valueOf(s));
        Capture.Rect over = top.rect().intersect(s.rect());
        assertNotNull(over, "the notice lies over the page: notice " + top.rect() + ", page " + s.rect());
        assertEquals(List.of(over), s.covered(), "S108: covered is the notice's part of the page");
        BufferedImage shot = Grounding.decode(capture.png());
        BufferedImage model = Grounding.decode(grounding.modelImage(capture, false));
        Map<String, Object> bitmap = value(client().call("web.bitmap", Map.of("browser", frontRef), CALL));
        BufferedImage page = Grounding.decode(Base64.getDecoder().decode(String.valueOf(bitmap.get("base64"))));
        assertNotNull(shot);
        assertNotNull(model);
        assertNotNull(page);
        Capture.Rect at = s.bounds() != null ? s.bounds() : s.rect();
        assertEquals(List.of(at.w(), at.h()), List.of(page.getWidth(), page.getHeight()), "the page's bitmap has the"
                + " component's size, so the composite draws it unscaled");
        long covered = 0;
        long kept = 0;
        long differs = 0;
        long rest = 0;
        long shown = 0;
        Capture.Rect r = s.rect();
        for (int y = r.y(); y < r.y() + r.h(); y++) {
            for (int x = r.x(); x < r.x() + r.w(); x++) {
                int m = model.getRGB(x, y) & 0xFFFFFF;
                int p = page.getRGB(x - at.x(), y - at.y()) & 0xFFFFFF;
                if (over.contains(x, y)) {
                    int c = shot.getRGB(x, y) & 0xFFFFFF;
                    covered++;
                    kept += m == c ? 1 : 0;
                    differs += c != p ? 1 : 0;
                } else {
                    rest++;
                    shown += m == p ? 1 : 0;
                }
            }
        }
        ItEnv.timing("java=" + java + " launch=" + target.label + " gap95a covered=" + over + " kept=" + kept + "/"
                + covered + " dialogDiffersFromPage=" + differs + " pageShown=" + shown + "/" + rest);
        assertEquals(covered, kept, "every pixel of the notice is kept in the model image");
        assertTrue(differs * 2 > covered, "the notice's pixels differ from the page's there: " + differs + " of "
                + covered);
        assertTrue(shown * 100 >= rest * 99, "the page is composited around the notice: " + shown + " of " + rest);
    }

    /**
     * Gap 95 (b), phase7-contracts.md 13.2 item 10 (S133, the probe on a real page): the front page stalls its own
     * hit for {@value #STALL_MS} ms (its next {@code document.elementFromPoint}, once); the CDP path's pointer with a
     * {@value #LATE_BUDGET_MS} ms budget answers {@code TIMEOUT}, and once the path's Playwright thread has returned
     * from the abandoned call the page has counted no event: nothing was dispatched after the caller's deadline. The
     * same call with the default budget then clicks (the control).
     */
    @Test
    @Order(12)
    void gap95bACallerThatGaveUpGetsNoLateClickOnTheCdpPath() throws Exception {
        assumeTrue(withPort(), "the CDP path needs the remote-debugging port");
        String frontRef = ref("front");
        Map<String, Object> click = Map.of("browser", frontRef, "action", "CLICK", "point", borrowCentre());
        CdpWebPath path = new CdpWebPath();
        try {
            assertNull(path.open(client(), WebSettings.DEFAULT, null), "the CDP path opens");
            int before = pointerLog().size();
            Map<String, Object> stall = new LinkedHashMap<>();
            stall.put("by", Map.of("tag", "front"));
            stall.put("expression", "(function () { var own = Document.prototype.elementFromPoint;"
                    + " document.elementFromPoint = function (x, y) { delete document.elementFromPoint;"
                    + " var t = Date.now(); while (Date.now() - t < " + STALL_MS + ") { }"
                    + " return own.call(document, x, y); }; return true; })()");
            client().call("web.evaluate", stall, CALL);
            long t0 = System.nanoTime();
            AgentRpcException late = assertThrows(AgentRpcException.class, () -> path.call("web.pointer", click,
                    CallOptions.defaults().withTimeoutMs(LATE_BUDGET_MS)), "the caller gives up after its budget");
            long answeredMs = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals(ErrorCodes.TIMEOUT, late.error().code(), late.getMessage());
            assertTrue(answeredMs < STALL_MS, "answered before the page was free: " + answeredMs + " ms");
            long t1 = System.nanoTime();
            List<String> after = pointerLogOnceThePathIsFree(path, frontRef);
            ItEnv.timing("java=" + java + " launch=" + target.label + " gap95b timeoutAfterMs=" + answeredMs
                    + " pathFreeAfterMs=" + (answeredMs + (System.nanoTime() - t1) / 1_000_000L) + " events="
                    + after.subList(before, after.size()));
            assertEquals(List.of(), after.subList(before, after.size()), "S133: nothing was dispatched after the "
                    + "caller's deadline");
            Map<String, Object> clicked = value(path.call("web.pointer", click, CallOptions.defaults()));
            assertEquals(DISPATCHED_CLICK, clicked.get("dispatched"), String.valueOf(clicked));
            assertEquals(TRUSTED_CLICK, pointerLog().subList(before, before + 4), "the control: the path clicks");
        } finally {
            path.close();
        }
    }

    /**
     * The front page's pointer log read through the CDP path once its Playwright thread has returned from an
     * abandoned call: while it is busy a call is {@code UNSUPPORTED} at once ("an earlier CDP operation has not
     * returned"), so this re-asks (a bounded poll, {@link #awaitCondition}) until a read is served.
     */
    List<String> pointerLogOnceThePathIsFree(CdpWebPath path, String frontRef) throws Exception {
        Map<String, Object> read = Map.of("browser", frontRef, "expression", "webhostPointer()");
        AtomicReference<String> log = new AtomicReference<>();
        awaitCondition("the CDP path's abandoned call to return", WAIT, () -> {
            try {
                Object v = path.call("web.evaluate", read, CallOptions.defaults()).value();
                log.set(String.valueOf(Json.parse(String.valueOf(((Map<?, ?>) v).get("json")))));
                return true;
            } catch (WebPathException e) {
                if (String.valueOf(e.getMessage()).contains("has not returned")) {
                    return false;
                }
                throw new AssertionError("the CDP path refused the read: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new AssertionError("the read through the CDP path failed: " + e, e);
            }
        });
        return log.get().isEmpty() ? List.of() : List.of(log.get().split(","));
    }

    /**
     * Gap 95 (c), phase7-contracts.md 13.2 item 10 (S110 on a real page): {@code CallPolicy} refuses a middle button
     * on {@code web.pointer} (a model's or a client's call, every route; a left click passes it, the control) and the
     * recorder refuses a middle click on the page before anything is read or sent, while the page counts nothing. What
     * a person's middle click does to the page is item (e)'s record, the launch's last act (it may leave the page
     * unresponsive).
     */
    @Test
    @Order(13)
    void gap95cAMiddleClickIsAPersonsOnly() throws Exception {
        String frontRef = ref("front");
        Map<String, Object> front = Map.of("tag", "front");
        Map<String, Object> point = borrowCentre();
        Map<String, Object> middle = Map.of("by", front, "action", "CLICK", "point", point, "button", "MIDDLE");
        int before = pointerLog().size();
        ReadFacade facade = new ReadFacade(client());
        Grounding grounding = new Grounding(facade);
        assertAll("gap 95 (c), a middle click on the page, " + target.label,
                () -> {
                    String refusal = CallPolicy.arguments("web.pointer", middle);
                    assertNotNull(refusal, "a model's or a client's middle click is refused");
                    assertTrue(refusal.contains("button: MIDDLE"), refusal);
                    assertNull(CallPolicy.arguments("web.pointer", Map.of("by", front, "action", "CLICK", "point",
                            point)), "a left click passes the rule (the control)");
                },
                () -> {
                    Capture capture = grounding.capture(false);
                    Capture.Surface s = surfaceOf(capture, frontRef);
                    assertNotNull(s, "the front browser is a WEB surface: " + capture.surfaces());
                    int[] px = pixel(capture, s, point);
                    Grounding.Grounded g = grounding.ground(capture, px[0], px[1], false);
                    assertEquals("WEB", g.hit().get("surface"), String.valueOf(g.hit()));
                    int reads = facade.reads.size();
                    Recorder.Recorded r = recorder(facade).pointer(g, px[0], px[1],
                            new Recorder.Click("MIDDLE", 1, List.of()), null);
                    assertTrue(r.error(), String.valueOf(r));
                    assertEquals(Recorder.CLIPBOARD, r.text());
                    assertEquals(List.of(), facade.reads.subList(reads, facade.reads.size()), "refused before the"
                            + " page is read");
                    assertEquals(List.of(), facade.executed, "nothing acted");
                },
                () -> assertEquals(before, pointerLog().size(), "the page counted nothing: " + pointerLog()));
    }

    /**
     * Gap 95 (d), phase7-contracts.md 13.2 item 10 (S111, the grey surface of S108's composite, D358 in the
     * recorder; the launch's last item): a fresh agent attached with {@code webhost.mask=front} masks the front card;
     * the browser is then masked on every route a model or a client has: {@code web.browsers} withholds its URL,
     * title and frame names; its four page reads are refused on the agent's path and handed to the agent by the CDP
     * path; the vision capture keeps its surface grey and composites nothing; the recorder refuses a click on it; the
     * session's text snapshot shows one line for it. The controls: a session snapshot before the mask shows the page,
     * a person's {@code redact: false} reads it on both paths, and {@code CallPolicy} refuses that argument to a model
     * or a client.
     */
    @Test
    @Order(14)
    void gap95dAMaskedBrowserIsMaskedOnEveryRoute() throws Exception {
        String unmasked = snapshotText(runDir("gap95d-snapshot-unmasked"));
        assertTrue(unmasked.contains("\"Borrow\""), "the control: before the mask the snapshot shows the page: "
                + unmasked);

        disableAndAwaitStopped();
        target.endpoint = target.attach(Map.of(MASK_OPTION, MASK_FRONT));
        target.client = AgentClient.connect(target.endpoint);
        WebHostTarget.awaitReady(client());
        String frontRef = ref("front");
        Map<String, Object> point = borrowCentre();
        Map<String, Map<String, Object>> reads = new LinkedHashMap<>();
        reads.put("web.bitmap", Map.of("browser", frontRef));
        reads.put("web.dom.text", Map.of("browser", frontRef));
        reads.put("web.ariaSnapshot", Map.of("browser", frontRef));
        reads.put("web.hitTest", Map.of("browser", frontRef, "point", point));
        ReadFacade facade = new ReadFacade(client());
        Grounding grounding = new Grounding(facade);
        assertAll("gap 95 (d), a masked browser, " + target.label,
                () -> {
                    Map<String, Object> f = browser("front");
                    assertEquals(Boolean.TRUE, f.get("masked"), String.valueOf(f));
                    assertEquals("", f.get("url"), "no URL");
                    assertFalse(f.containsKey("title"), "no title: " + f.get("title"));
                    List<?> frames = (List<?>) f.get("frames");
                    assertTrue(frames != null && frames.size() >= 2, "the frames' keys stay: " + frames);
                    for (Object o : frames) {
                        Map<?, ?> frame = (Map<?, ?>) o;
                        assertNotNull(frame.get("key"), String.valueOf(frame));
                        assertEquals("", frame.get("name"), String.valueOf(frame));
                        assertEquals("", frame.get("url"), String.valueOf(frame));
                    }
                    Map<String, Object> b = browser("back");
                    assertNull(b.get("masked"), "the back browser is not masked: " + b);
                    assertEquals(WebHostPages.title("back"), b.get("title"));
                },
                () -> {
                    for (Map.Entry<String, Map<String, Object>> read : reads.entrySet()) {
                        AgentRpcException e = assertThrows(AgentRpcException.class,
                                () -> client().call(read.getKey(), read.getValue(), CALL), read.getKey());
                        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code(), read.getKey() + ": " + e.getMessage());
                        Map<String, Object> data = e.error().data();
                        assertEquals("redact", data.get("param"), read.getKey() + ": " + data);
                        assertEquals("masked", data.get("reason"), read.getKey() + ": " + data);
                        assertEquals(frontRef, data.get("browser"), read.getKey() + ": " + data);
                    }
                },
                () -> {
                    for (Map.Entry<String, Map<String, Object>> read : reads.entrySet()) {
                        assertNull(CallPolicy.arguments(read.getKey(), read.getValue()), read.getKey());
                        assertNotNull(CallPolicy.arguments(read.getKey(), unredacted(read.getValue())),
                                read.getKey() + ": redact: false is a person's argument");
                        assertNotNull(client().call(read.getKey(), unredacted(read.getValue()), CALL).value(),
                                read.getKey() + ": a person reads the page");
                    }
                    String text = String.valueOf(value(client().call("web.dom.text", unredacted(reads.get(
                            "web.dom.text")), CALL)).get("text"));
                    assertTrue(text.contains("Borrow"), "the control: a person's redact: false reads the page");
                },
                () -> {
                    if (withPort()) {
                        CdpWebPath path = new CdpWebPath();
                        try {
                            assertNull(path.open(client(), WebSettings.DEFAULT, null), "the CDP path opens");
                            for (Map.Entry<String, Map<String, Object>> read : reads.entrySet()) {
                                WebPathException e = assertThrows(WebPathException.class, () -> path.call(
                                        read.getKey(), read.getValue(), CallOptions.defaults()),
                                        read.getKey() + " is handed to the agent");
                                assertTrue(String.valueOf(e.getMessage()).contains("masked"), e.getMessage());
                            }
                            Map<?, ?> text = (Map<?, ?>) path.call("web.dom.text", unredacted(reads.get(
                                    "web.dom.text")), CallOptions.defaults()).value();
                            assertTrue(String.valueOf(text.get("text")).contains("Borrow"),
                                    "the control: a person's redact: false reads the page on the CDP path");
                        } finally {
                            path.close();
                        }
                    }
                },
                () -> {
                    int bitmaps = facade.reads.size();
                    Capture capture = grounding.capture(false);
                    Capture.Surface s = surfaceOf(capture, frontRef);
                    assertNotNull(s, "the masked browser is still a WEB surface: " + capture.surfaces());
                    assertTrue(s.redacted(), "redacted: " + s);
                    BufferedImage model = Grounding.decode(grounding.modelImage(capture, false));
                    assertNotNull(model);
                    long other = 0;
                    Capture.Rect r = s.rect();
                    for (int y = r.y(); y < r.y() + r.h(); y++) {
                        for (int x = r.x(); x < r.x() + r.w(); x++) {
                            other += (model.getRGB(x, y) & 0xFFFFFF) != GREY ? 1 : 0;
                        }
                    }
                    assertEquals(0, other, "the masked page is solid grey in the model image: " + other + " of "
                            + (long) r.w() * r.h() + " pixels are not");
                    assertFalse(facade.reads.subList(bitmaps, facade.reads.size()).contains("web.bitmap"),
                            "nothing was composited: " + facade.reads.subList(bitmaps, facade.reads.size()));
                },
                () -> {
                    Capture capture = grounding.capture(false);
                    Capture.Surface s = surfaceOf(capture, frontRef);
                    assertNotNull(s, "the masked browser is still a WEB surface: " + capture.surfaces());
                    int[] px = pixel(capture, s, point);
                    Grounding.Grounded g = grounding.ground(capture, px[0], px[1], false);
                    assertEquals(Boolean.TRUE, g.hit().get("masked"), "the hit is masked: " + g.hit());
                    Recorder.Recorded rec = recorder(facade).pointer(g, px[0], px[1],
                            new Recorder.Click("LEFT", 1, List.of()), null);
                    assertTrue(rec.error(), String.valueOf(rec));
                    assertEquals(Recorder.MASKED, rec.text());
                    assertEquals(List.of(), facade.executed, "nothing acted");
                },
                () -> {
                    String text = snapshotText(runDir("gap95d-snapshot-masked"));
                    assertTrue(text.contains("[web " + frontRef + " masked]"), text);
                    for (String page : List.of(WebHostPages.title("front"), WebHostPages.marker("front"),
                            "\"Borrow\"", "Quantity")) {
                        assertFalse(text.contains(page), "the snapshot shows nothing of the front page (" + page
                                + "): " + text);
                    }
                });
    }

    /**
     * Gap 95 (e), phase7-contracts.md 13.2 item 10 (S110: what Chromium does with a synthetic middle click, which SI
     * stated from its behaviour; recorded in {@code timings.txt}, not asserted; the launch's last act, since it may
     * leave the page unresponsive): a person's {@code web.pointer {action: CLICK, button: MIDDLE}} at the Borrow
     * button through the agent with a {@value #MIDDLE_BUDGET_MS} ms budget, what it answered, how long the page then
     * took to answer an evaluation again (re-asked, bounded at {@value #MIDDLE_RECOVERY_MS} ms), what the page
     * counted, and whether the host's event dispatch thread and the back page still answer.
     */
    @Test
    @Order(15)
    void gap95eWhatAPersonsMiddleClickDoesToTheRealPageIsRecorded() throws Exception {
        Map<String, Object> front = Map.of("tag", "front");
        Map<String, Object> point = borrowCentre();
        int before = pointerLog().size();
        String answered;
        long t0 = System.nanoTime();
        try {
            Map<String, Object> pressed = value(client().call("web.pointer", Map.of("by", front, "action", "CLICK",
                    "point", point, "button", "MIDDLE"), CallOptions.defaults().withTimeoutMs(MIDDLE_BUDGET_MS)));
            answered = "dispatched " + pressed.get("dispatched");
        } catch (AgentRpcException e) {
            answered = ErrorCodes.name(e.error().code());
        }
        long answeredMs = (System.nanoTime() - t0) / 1_000_000L;
        long t1 = System.nanoTime();
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("by", front);
        probe.put("expression", "webhostPointer()");
        AtomicReference<String> log = new AtomicReference<>();
        String page;
        try {
            awaitCondition("the page to answer again", Duration.ofMillis(MIDDLE_RECOVERY_MS), () -> {
                try {
                    log.set(String.valueOf(Json.parse(String.valueOf(value(client().call("web.evaluate", probe,
                            CallOptions.defaults().withTimeoutMs(1_000L))).get("json")))));
                    return true;
                } catch (AgentRpcException e) {
                    return false;
                } catch (AgentLostException e) {
                    throw new AssertionError("the agent is gone", e);
                }
            });
            page = "the page answered again after " + (System.nanoTime() - t1) / 1_000_000L + " ms";
        } catch (AssertionError e) {
            page = "the page did not answer within " + MIDDLE_RECOVERY_MS + " ms";
        }
        List<String> all = log.get() == null ? null : log.get().isEmpty() ? List.of() : List.of(log.get().split(","));
        Object events = all == null ? "nothing it could report" : all.subList(Math.min(before, all.size()), all.size());
        ItEnv.timing("java=" + java + " launch=" + target.label + " gap95e a person's middle click: " + answered
                + " after " + answeredMs + " ms; " + page + "; the page counted " + events + "; the event dispatch"
                + " thread " + answers("swing.snapshot", Map.of("maxDepth", 0L)) + "; the back page "
                + answers("web.evaluate", Map.of("by", Map.of("tag", "back"), "expression", "1")));
    }

    /** "answers" or what a call with a 2 s budget answered instead, for (e)'s record. */
    String answers(String primitive, Map<String, Object> params) {
        try {
            client().call(primitive, params, CallOptions.defaults().withTimeoutMs(2_000L));
            return "answers";
        } catch (AgentRpcException e) {
            return "answers " + ErrorCodes.name(e.error().code()) + " (" + primitive + ")";
        } catch (AgentLostException e) {
            return "lost the agent (" + primitive + ")";
        }
    }

    /** The parameters with {@code redact: false} (a person's argument). */
    static Map<String, Object> unredacted(Map<String, Object> params) {
        Map<String, Object> p = new LinkedHashMap<>(params);
        p.put("redact", false);
        return p;
    }

    /** A session's text snapshot ({@code ExecutorSession.snapshotText}), the session's output under {@code dir}. */
    String snapshotText(Path dir) throws Exception {
        SessionOptions options = SessionOptions.defaults(dir).withRecoverOnClose(false);
        try (ExecutorSession session = ExecutorSession.open(Runs.PACK, client(), options)) {
            return session.snapshotText(20_000);
        }
    }

    /** The vision module's recorder over the check's facade, as a V1 session would hold it. */
    static Recorder recorder(ReadFacade facade) {
        return new Recorder(facade, PromptMask.keysOnly(), Captures.none(), VisionTier.V1);
    }

    /** The ref of the open browser with this tag. */
    String ref(String tag) throws Exception {
        return String.valueOf(browser(tag).get("ref"));
    }

    /** The {@code web.browsers} entry of the open browser with this tag. */
    Map<String, Object> browser(String tag) throws Exception {
        for (Map<String, Object> b : browsers(client())) {
            if (tag.equals(b.get("tag"))) {
                return b;
            }
        }
        throw new AssertionError("no browser tagged " + tag);
    }

    /** The capture's {@code WEB} surface of a browser, or null. */
    static Capture.Surface surfaceOf(Capture capture, String browserRef) {
        for (Capture.Surface s : capture.surfaces()) {
            if ("WEB".equals(s.kind()) && browserRef.equals(s.browser())) {
                return s;
            }
        }
        return null;
    }

    /** The image pixel of a viewport point of a surface's page (scale 1): the component's origin plus the point. */
    static int[] pixel(Capture capture, Capture.Surface s, Map<String, Object> point) {
        assertEquals(1.0, capture.scale(), "the capture is at scale 1");
        Capture.Rect at = s.bounds() != null ? s.bounds() : s.rect();
        return new int[] {at.x() + (int) number(point.get("x")), at.y() + (int) number(point.get("y"))};
    }

    /** {@code web.evaluate} of an expression in a page: the value parsed from its JSON. */
    Object evaluate(Map<String, Object> by, String expression) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("by", by);
        params.put("expression", expression);
        return Json.parse(String.valueOf(value(client().call("web.evaluate", params, CALL)).get("json")));
    }

    /** {@link #evaluate} of an expression whose value is an object. */
    @SuppressWarnings("unchecked")
    Map<String, Object> evaluateObject(Map<String, Object> by, String expression) throws Exception {
        return (Map<String, Object>) evaluate(by, expression);
    }

    // ------------------------------------------------------------------------------------------- helpers

    long lastSeq() throws Exception {
        Map<String, Object> q = value(client().call("evt.query", Map.of("since", Long.MAX_VALUE, "limit", 1L),
                CALL));
        return ((Number) q.get("lastSeq")).longValue();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> browsers(AgentClient agent) throws AgentRpcException, AgentLostException {
        Map<String, Object> value = value(agent.call("web.browsers", Map.of(), CALL));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object b : (List<?>) value.get("browsers")) {
            out.add((Map<String, Object>) b);
        }
        return out;
    }

    /** The browsers without their long {@code data:} URLs, for messages. */
    static List<String> summary(List<Map<String, Object>> browsers) {
        return browsers.stream().map(b -> b.get("ref") + "/" + b.get("tag") + "/" + b.get("title")).toList();
    }

    /** {@code GET /json/list} of the port: the targets Chromium lists. */
    static List<?> jsonList(int port) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/json/list")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return (List<?>) Json.parse(response.body());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> value(RpcResult result) {
        return (Map<String, Object>) result.value();
    }
}
