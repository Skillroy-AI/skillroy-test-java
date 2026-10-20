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

import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebPathOpenException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CdpOptions} validation and copies, {@link CdpEndpoint}, and the kinds of {@link CdpException}. */
class CdpOptionsTest {

    @Test
    void theDefaultsAreTheContractsOnes() {
        CdpOptions d = CdpOptions.defaults();
        assertEquals(Duration.ofSeconds(2), d.probeDeadlinePerTarget());
        assertEquals(Duration.ofSeconds(30), d.connectDeadline());
        assertTrue(d.noDefaults());
        assertTrue(d.isLocal());
        assertNull(d.artifactsDir());
        assertEquals(FrozenTargetPolicy.FAIL, d.frozenTargets());
    }

    @Test
    void everyWithCopiesOneComponent() {
        CdpOptions d = CdpOptions.defaults();
        assertEquals(Duration.ofMillis(300), d.withProbeDeadlinePerTarget(Duration.ofMillis(300))
                .probeDeadlinePerTarget());
        assertEquals(Duration.ofSeconds(5), d.withConnectDeadline(Duration.ofSeconds(5)).connectDeadline());
        assertFalse(d.withNoDefaults(false).noDefaults());
        assertFalse(d.withIsLocal(false).isLocal());
        assertEquals(Path.of("out"), d.withArtifactsDir(Path.of("out")).artifactsDir());
        assertEquals(FrozenTargetPolicy.RELOAD, d.withFrozenTargets(FrozenTargetPolicy.RELOAD).frozenTargets());
        CdpOptions all = d.withConnectDeadline(Duration.ofSeconds(5)).withIsLocal(false);
        assertEquals(Duration.ofSeconds(2), all.probeDeadlinePerTarget(), "the others stay");
        assertTrue(all.noDefaults());
    }

    @Test
    void deadlinesAreValidated() {
        CdpOptions d = CdpOptions.defaults();
        assertEquals("probeDeadlinePerTarget is required", assertThrows(IllegalArgumentException.class,
                () -> d.withProbeDeadlinePerTarget(null)).getMessage());
        assertEquals("probeDeadlinePerTarget must be at least 1 ms: PT0S", assertThrows(
                IllegalArgumentException.class, () -> d.withProbeDeadlinePerTarget(Duration.ZERO)).getMessage());
        assertEquals("probeDeadlinePerTarget must be at least 1 ms: PT-1S", assertThrows(
                IllegalArgumentException.class, () -> d.withProbeDeadlinePerTarget(Duration.ofSeconds(-1)))
                .getMessage());
        assertEquals("probeDeadlinePerTarget must be at least 1 ms: PT0.0005S", assertThrows(
                IllegalArgumentException.class, () -> d.withProbeDeadlinePerTarget(Duration.ofNanos(500_000)))
                .getMessage());
        assertEquals("probeDeadlinePerTarget must be at most 60 s: PT1M1S", assertThrows(
                IllegalArgumentException.class, () -> d.withProbeDeadlinePerTarget(Duration.ofSeconds(61)))
                .getMessage());
        assertEquals("connectDeadline is required", assertThrows(IllegalArgumentException.class,
                () -> d.withConnectDeadline(null)).getMessage());
        assertEquals("connectDeadline must be at most 600 s: PT10M1S", assertThrows(IllegalArgumentException.class,
                () -> d.withConnectDeadline(Duration.ofSeconds(601))).getMessage());
        assertEquals(Duration.ofMillis(1), d.withConnectDeadline(Duration.ofMillis(1)).connectDeadline());
        assertEquals(CdpOptions.MAX_CONNECT_DEADLINE, d.withConnectDeadline(CdpOptions.MAX_CONNECT_DEADLINE)
                .connectDeadline());
        assertEquals("frozenTargets is required", assertThrows(IllegalArgumentException.class,
                () -> d.withFrozenTargets(null)).getMessage());
    }

    @Test
    void anEndpointIsHostAndPortWithLocalhostByDefault() {
        CdpEndpoint e = new CdpEndpoint(null, 9222);
        assertEquals("127.0.0.1", e.host());
        assertEquals(URI.create("http://127.0.0.1:9222"), e.httpUri());
        assertEquals("127.0.0.1:9222", e.toString());
        assertEquals(e, CdpEndpoint.local(9222));
        assertEquals("127.0.0.1", new CdpEndpoint("  ", 1).host());
        assertEquals("localhost:65535", new CdpEndpoint(" localhost ", 65535).authority());
        assertEquals(URI.create("http://[::1]:9222"), new CdpEndpoint("::1", 9222).httpUri());
        assertEquals("port must be between 1 and 65535: 0", assertThrows(IllegalArgumentException.class,
                () -> CdpEndpoint.local(0)).getMessage());
        assertEquals("port must be between 1 and 65535: 65536", assertThrows(IllegalArgumentException.class,
                () -> CdpEndpoint.local(65536)).getMessage());
        for (String bad : List.of("a b", "host/path", "user@host", "h:1:2x")) {
            assertThrows(IllegalArgumentException.class, () -> new CdpEndpoint(bad, 9222), bad);
        }
    }

    @Test
    void exceptionsCarryTheirKindAndTargets() {
        TargetProbe.Target t = new TargetProbe.Target("T", "page", "data:,x", "x", "ws://h/devtools/page/T", false,
                2000, "did not answer");
        List<TargetProbe.Target> targets = new ArrayList<>(List.of(t));
        CdpException e = new CdpException(CdpException.Kind.FROZEN_TARGETS, "frozen", targets);
        targets.clear();
        assertEquals(List.of(t), e.targets(), "a copy");
        assertEquals(CdpException.Kind.FROZEN_TARGETS, e.kind());
        assertTrue(new CdpException(CdpException.Kind.UNREACHABLE, "x", null).targets().isEmpty());
        assertEquals(List.of(CdpException.Kind.UNREACHABLE, CdpException.Kind.TIMEOUT,
                CdpException.Kind.FROZEN_TARGETS, CdpException.Kind.PROTOCOL), List.of(CdpException.Kind.values()));
        assertThrows(IllegalArgumentException.class, () -> new CdpException(null, "x", null));
        assertEquals(Map.of("id", "T", "url", "data:,x", "title", "x"), t.summary());
    }

    @Test
    void everyKindBecomesTheOpenExceptionOfTheSameNameWithTheEndpoint() {
        CdpEndpoint endpoint = CdpEndpoint.local(9333);
        for (CdpException.Kind kind : CdpException.Kind.values()) {
            WebPathOpenException e = CdpWebPath.openFailure(new CdpException(kind, "m", null), endpoint);
            assertEquals(kind.name(), e.kind().name());
            assertEquals("m", e.getMessage());
            assertEquals("127.0.0.1:9333", e.detail().get("endpoint"));
        }
        TargetProbe.Target t = new TargetProbe.Target("T", "page", "u", "ti", "w", false, 1, "e");
        WebPathOpenException frozen = CdpWebPath.openFailure(CdpAttach.frozen(List.of(t)), endpoint);
        assertEquals(WebPathOpenException.Kind.FROZEN_TARGETS, frozen.kind());
        // Phase 8 security fix S179 (FE): without the agent's listing the page is withheld (fail closed), and the
        // message names the target by id only.
        assertEquals(List.of(Map.of("id", "T", "url", "", "masked", true)), frozen.detail().get("frozenTargets"));
        assertEquals("1 page target did not answer Runtime.evaluate: T", frozen.getMessage());
    }
}
