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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.pack.DelegatingDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPackOverlay;
import ai.skillroy.testjava.scenario.pack.PackSettings;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tenant-pack overlay (phase6-contracts.md 11.1, 13.1 item 5): {@link DomainPackOverlay} discovery (an overlay
 * applied, overlays chained in id order, a missing base refused), {@link DelegatingDomainPack} forwarding every member
 * of {@link DomainPack} (a reflection test that none is left to the interface's default), and {@link PackSettings}
 * through every context a pack call receives.
 */
class PackOverlayTest {

    /** An overlay of the toy pack that tags the version and changes one setting. */
    record Tagging(String id, String key, String value) implements DomainPackOverlay {

        @Override
        public String baseId() {
            return "toy";
        }

        @Override
        public String version() {
            return "0.1." + id.length();
        }

        @Override
        public DomainPack overlay(DomainPack base) {
            return new DelegatingDomainPack(base, id, version()) {
                @Override
                public PackSettings settings() {
                    return base().settings().with(Map.of(key, value));
                }
            };
        }
    }

    @Test
    void overlaysApplyToTheirBaseInIdOrderAndKeepItsId() {
        ToyPack toy = new ToyPack();
        toy.settings = PackSettings.of(Map.of("toy.a", "1", "toy.b", "1"));
        List<DomainPack> packs = DomainPacks.overlaid(List.of(toy), List.of(new Tagging("toy-z", "toy.b", "z"),
                new Tagging("toy-a", "toy.a", "a")));
        assertEquals(1, packs.size());
        DomainPack overlaid = packs.get(0);
        assertEquals("toy", overlaid.id(), "scenarios keep pack: toy");
        assertEquals("1.0.0+toy-a@0.1.5+toy-z@0.1.5", overlaid.version(), "chained: toy-a first, then toy-z");
        assertEquals(Map.of("toy.a", "a", "toy.b", "z"), overlaid.settings().asMap());
        assertSame(toy, DomainPacks.overlaid(List.of(toy), List.of()).get(0));
    }

    @Test
    void anOverlayWithoutItsBaseIsRefusedNamingBothIds() {
        DomainPackOverlay stray = new DomainPackOverlay() {
            @Override
            public String baseId() {
                return "desk";
            }

            @Override
            public String id() {
                return "desk-branch";
            }

            @Override
            public String version() {
                return "0.1.0";
            }

            @Override
            public DomainPack overlay(DomainPack base) {
                return base;
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DomainPacks.overlaid(List.of(new ToyPack()), List.of(stray)));
        assertTrue(e.getMessage().contains("'desk-branch'") && e.getMessage().contains("'desk'"), e.getMessage());
        IllegalStateException renamed = assertThrows(IllegalStateException.class,
                () -> DomainPacks.overlaid(List.of(new ToyPack()), List.of(new DomainPackOverlay() {
                    @Override
                    public String baseId() {
                        return "toy";
                    }

                    @Override
                    public String id() {
                        return "toy-x";
                    }

                    @Override
                    public String version() {
                        return "1";
                    }

                    @Override
                    public DomainPack overlay(DomainPack base) {
                        return new ResourcePack("other");
                    }
                })));
        assertTrue(renamed.getMessage().contains("a pack with id 'other'"), renamed.getMessage());
    }

    /** The overlay a service file names (public, no-argument constructor). */
    public static final class ServiceOverlay implements DomainPackOverlay {

        @Override
        public String baseId() {
            return "toy";
        }

        @Override
        public String id() {
            return "toy-tagged";
        }

        @Override
        public String version() {
            return "0.0.1";
        }

        @Override
        public DomainPack overlay(DomainPack base) {
            return new DelegatingDomainPack(base, id(), version()) {
            };
        }
    }

    /** {@code discover} finds the overlays of the same loader; {@code forScenario} refuses a stray one before a run. */
    @Test
    void discoveryThroughTheLoaderAppliesTheServiceFileOverlays(@TempDir Path dir) throws Exception {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(DomainPackOverlay.class.getName()), ServiceOverlay.class.getName() + "\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()},
                getClass().getClassLoader())) {
            DomainPack toy = DomainPacks.discover(loader).stream().filter(p -> p.id().equals("toy")).findFirst()
                    .orElseThrow();
            assertEquals("1.0.0+toy-tagged@0.0.1", toy.version());
            assertTrue(toy instanceof DelegatingDomainPack);
        }
        Files.writeString(services.resolve(DomainPackOverlay.class.getName()), StrayOverlay.class.getName() + "\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()},
                getClass().getClassLoader())) {
            Scenario scenario = ScenarioLoader.load("scenario: t\npack: toy\nsteps: []\n"
                    .getBytes(StandardCharsets.UTF_8), "t.yaml");
            ScenarioException e = assertThrows(ScenarioException.class, () -> DomainPacks.forScenario(scenario,
                    loader));
            assertTrue(e.getMessage().contains("'desk-branch'") && e.getMessage().contains("'desk'"),
                    e.getMessage());
        }
    }

    /** An overlay of a pack that is not on the class path. */
    public static final class StrayOverlay implements DomainPackOverlay {

        @Override
        public String baseId() {
            return "desk";
        }

        @Override
        public String id() {
            return "desk-branch";
        }

        @Override
        public String version() {
            return "0.1.0";
        }

        @Override
        public DomainPack overlay(DomainPack base) {
            return base;
        }
    }

    /** Every abstract or default member of DomainPack is overridden by DelegatingDomainPack and forwards. */
    @Test
    void theDelegatingPackForwardsEveryMember() throws Exception {
        for (Method m : DomainPack.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Method declared = DelegatingDomainPack.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            assertTrue(!Modifier.isAbstract(declared.getModifiers()), m.getName() + " is not left to a default");
        }
        List<String> called = new CopyOnWriteArrayList<>();
        InvocationHandler handler = (proxy, method, args) -> {
            called.add(method.getName());
            return switch (method.getName()) {
                case "id" -> "base";
                case "version" -> "2.0.0";
                case "hooks" -> HookCatalogue.EMPTY;
                case "settings" -> PackSettings.EMPTY;
                case "facts", "preflight", "storedTables", "rowMasks", "sqlAllowLists", "capabilities" -> List.of();
                case "warningCodes" -> Set.of();
                case "resolution" -> ResolutionCatalogue.EMPTY;
                case "toString" -> "proxy";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            };
        };
        DomainPack base = (DomainPack) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DomainPack.class}, handler);
        DomainPack overlay = new DelegatingDomainPack(base, "base-x", "0.0.1") {
        };
        List<String> expected = new ArrayList<>();
        for (Method m : DomainPack.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            called.clear();
            Object[] args = m.getParameterCount() == 0 ? new Object[0] : new Object[] {null};
            m.invoke(overlay, args);
            assertTrue(called.contains(m.getName()), m.getName() + " reached the base: " + called);
            expected.add(m.getName());
        }
        assertTrue(expected.containsAll(List.of("hooks", "settings", "assistAnswerPrimitive", "recover")),
                String.valueOf(expected));
        // phase8-contracts.md 9.1.1, 7.3: the pack's capabilities and warning codes are forwarded too.
        assertTrue(expected.containsAll(List.of("capabilities", "warningCodes")), String.valueOf(expected));
        assertEquals(List.of(), overlay.capabilities());
        assertEquals(Set.of(), overlay.warningCodes());
        assertEquals("base", overlay.id());
        assertEquals("2.0.0+base-x@0.0.1", overlay.version());
        assertThrows(IllegalArgumentException.class, () -> new DelegatingDomainPack(base, "Bad Id", "1") {
        });
        assertThrows(IllegalArgumentException.class, () -> new DelegatingDomainPack(base, "ok", " ") {
        });
    }

    // ------------------------------------------------------------------------------------- PackSettings everywhere

    /** A query whose resolver and verifier write down the settings their contexts hand out. */
    record Seen() implements Query<String> {
    }

    @Test
    void theSettingsReachEveryContextOfAPackCall() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        ToyPack pack = new ToyPack();
        pack.settings = PackSettings.of(Map.of("toy.where", "here"));
        pack.resolution = ResolutionCatalogue.builder()
                .query(QueryType.of("Seen", Seen.class))
                .resolver(new Resolver<Seen, String>() {
                    @Override
                    public String id() {
                        return "toy.java.Seen";
                    }

                    @Override
                    public Class<Seen> queryClass() {
                        return Seen.class;
                    }

                    @Override
                    public Strategy strategy() {
                        return Strategy.CANONICAL_JAVA;
                    }

                    @Override
                    public List<ResolverCandidate<String>> candidates(Seen query, ResolveContext ctx) {
                        seen.add("resolve=" + ctx.settings().get("toy.where", "?"));
                        return List.of(ResolverCandidate.of("x", "x"));
                    }
                })
                .verifier(new Verifier<Seen, String>() {
                    @Override
                    public String id() {
                        return "toy.seen";
                    }

                    @Override
                    public Class<Seen> queryClass() {
                        return Seen.class;
                    }

                    @Override
                    public VerifierResult verify(Seen query, String candidate, VerifyContext ctx) {
                        seen.add("verify=" + ctx.settings().get("toy.where", "?"));
                        return VerifierResult.pass("ok", Map.of());
                    }
                })
                .build();
        pack.fingerprinter = (EnvFingerprinter) ctx -> {
            seen.add("fingerprint=" + ctx.settings().get("toy.where", "?"));
            return Map.of("toy.x", "1");
        };
        pack.preflight = List.of(new PreflightCheck() {
            @Override
            public String id() {
                return "toy.where";
            }

            @Override
            public String check(ai.skillroy.testjava.controller.AgentConnection agent) {
                return "the context form is the one called";
            }

            @Override
            public String check(PreflightContext ctx) {
                seen.add("preflight=" + ctx.settings().get("toy.where", "?"));
                return null;
            }
        });
        FakeAgentConnection agent = new FakeAgentConnection();
        try (ResolutionEngine engine = new ResolutionEngine(pack, agent, TestCredentials.bind()
                .withMode(ai.skillroy.testjava.scenario.resolve.BindMode.LIVE), Duration.ofSeconds(10), null)) {
            engine.resolve("s", "Seen", Map.of(), List.of(), List.of(), null);
            engine.fingerprint();
        }
        Plan plan = PlanCompiler.compile("scenario: t\npack: toy\nsteps: []\n".getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
        RunResult run = new PlanExecutor(pack, agent, RunOptions.defaults().withBind(TestCredentials.bind()))
                .run(plan);
        assertEquals(Status.PASS, run.status(), run.message());
        assertEquals(List.of("resolve=here", "verify=here", "fingerprint=here", "preflight=here"), seen);
        // The contexts' own defaults, for a test's fake: empty.
        assertEquals("d", new PreflightCheck.PreflightContext() {
            @Override
            public ai.skillroy.testjava.controller.AgentConnection agent() {
                return null;
            }

            @Override
            public ai.skillroy.testjava.data.DataChannel data() {
                return null;
            }

            @Override
            public String envId() {
                return null;
            }
        }.settings().get("toy.where", "d"));
    }

    @Test
    void packSettingsAreAnImmutableSortedMapWithOverrides() {
        PackSettings base = PackSettings.of(Map.of("b", "2", "a", "1"));
        assertEquals(List.of("a", "b"), List.copyOf(base.asMap().keySet()));
        assertEquals("1", base.get("a", "x"));
        assertEquals("x", base.get("c", "x"));
        assertEquals("x", base.get(null, "x"));
        PackSettings over = base.with(Map.of("b", "3", "c", "4"));
        assertEquals(Map.of("a", "1", "b", "3", "c", "4"), over.asMap());
        assertEquals("2", base.get("b", null), "the base is unchanged");
        assertSame(PackSettings.EMPTY, PackSettings.of(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> base.asMap().put("z", "z"));
        assertEquals(base, PackSettings.of(Map.of("a", "1", "b", "2")));
    }

    /** A bare pack with an id (for the renaming overlay). */
    static final class ResourcePack implements DomainPack {

        private final String id;

        ResourcePack(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public ai.skillroy.testjava.scenario.pack.StepLibrary steps() {
            return null;
        }

        @Override
        public ai.skillroy.testjava.scenario.pack.SyncPolicy syncPolicy() {
            return null;
        }

        @Override
        public List<ai.skillroy.testjava.scenario.pack.FactProvider> facts() {
            return List.of();
        }
    }
}
