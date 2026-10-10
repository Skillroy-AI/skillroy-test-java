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

import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CredentialRef;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Tier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The export of a session's journal as a scenario (phase5-contracts.md 12.3, decisions D80, D86), a function of the
 * journal's entries and the pack:
 *
 * <ol>
 *   <li><b>Steps.</b> Every step that passed (PASS or DEGRADED) becomes one scenario step in journal order; with
 *       {@code includeFailed}, every step up to and including the first that did not pass, and none after it. A step
 *       journaled with a verb is written as that verb with its arguments as given (references intact); a raw call as
 *       the base verb {@code call} as built ({@code {primitive, args?, sync, await?}}, the capture inside
 *       {@code await}), its arguments as sent except that an argument given with a reference keeps it and a value
 *       equal to a bound slot's field (a resolved slot, an oracle, a slot an earlier exported step captured) is written
 *       back as the reference; an argument the journal masked is refused, naming the step. A raw call of a primitive
 *       that changes nothing (the {@code reads} of the journal's {@code session-start}) is an observation, not a step:
 *       it is left out and listed in the report unless {@link ExportOptions#includeReads()} (or it captures a
 *       slot). Arguments (a step's, a slot's, an oracle's) and a check's matcher are written with their keys sorted
 *       at every depth, so that the text does not depend on the order a person or a model gave them in.</li>
 *   <li><b>Expectations.</b> The checks that held after a step become an {@code expect} step after it (those that did
 *       not hold are listed in the report); an observed value becomes an expectation of that value with the comment
 *       {@code # observed in session <id> at step <n>, not derived} on its line and a line in the report's
 *       "Observed expectations: derive or confirm" (D86).</li>
 *   <li><b>Resolution.</b> Every {@code resolve} becomes a {@code resolve:} slot (kind, arguments as given, strategy
 *       tokens, verifier ids, credential) in the order resolved, every {@code oracle} an {@code oracle:} entry; the
 *       bindings file holds their journaled values as {@code approve} would write them: a tier (a) slot
 *       {@code NOT_REQUIRED}, a tier (b) slot {@code REQUIRED} by nobody, or {@code APPROVED} by {@code approvedBy}
 *       when given.
 *       A tier (b) slot whose tokens do not name {@code llm-sql} gets the pack's tokens for the kind and
 *       {@code llm-sql}, so that the plan's {@code inference} is {@code bind}.</li>
 *   <li><b>Head.</b> {@code scenario}, {@code pack}, {@code requires} (the capability ids the exported calls'
 *       primitives name), {@code timeouts} from the step timeout, {@code ui} and {@code web} from the session's
 *       options; a leading comment block with the description, the session id and date, the origins that acted and
 *       the model cost.</li>
 *   <li><b>The check.</b> The YAML is compiled with {@link PlanCompiler} against the pack and {@link Binder#check} is
 *       run with the exported bindings (a slot's credential fields stand in as the run would join them); a problem is
 *       an {@link ExportException}.</li>
 *   <li><b>Vision-derived steps</b> (phase7-contracts.md 6.6, decisions D309, D355, D356). A step with provenance is
 *       written as the verb step it was journaled as, with one comment on its line: {@code aiAct "<intent, cut at
 *       80>" by <model>: <role> "<name>" (<class>)}; no capture hash, handle or mark (they differ between Java lines,
 *       runs and agent starts), which go into the report's "Vision-derived steps: review the locator and the rung".
 *       A step that acted through Robot, and a raw {@code swing.pointer} or {@code web.pointer} call, is left out
 *       and listed under "Live-only". An anchored step's anchor (its provenance's {@code anchor}) becomes the
 *       {@code images} entry of the export's bindings file under its render key, and its masked PNG, read from
 *       {@code captures/<hash>.png} in {@link ExportOptions#captures()}, goes to {@link ExportResult#images()}; a
 *       missing file is an {@link ExportException} naming the step.</li>
 * </ol>
 *
 * <p>One export; not thread-safe.
 */
final class JournalExport {

    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern STEP_ID = Pattern.compile("step (s\\d+)");

    private final List<StepJournal.Entry> entries;
    private final DomainPack pack;
    private final ExportOptions options;

    private StepJournal.SessionStart start;
    private final Map<String, StepJournal.ResolveEntry> resolves = new LinkedHashMap<>();
    private final Map<String, StepJournal.OracleEntry> oracles = new LinkedHashMap<>();
    /** What a value of an exported call may be written back as: a reference and the value it stands for. */
    private final Map<String, Object> bound = new LinkedHashMap<>();
    private final List<YamlStep> steps = new ArrayList<>();
    private List<Expect> group = new ArrayList<>();
    private final Set<String> requires = new TreeSet<>();
    private final List<String> observed = new ArrayList<>();
    private final List<String> notHeld = new ArrayList<>();
    private final List<String> excluded = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final Map<String, Integer> origins = new TreeMap<>();
    private String cost;
    /** The report's "Vision-derived steps" lines (phase7-contracts.md 6.6). */
    private final List<String> visionDerived = new ArrayList<>();
    /** The report's "Live-only" lines (phase7-contracts.md 6.6). */
    private final List<String> liveOnly = new ArrayList<>();
    /** The anchors of exported steps: label to its provenance's {@code anchor} and the step it came from. */
    private final Map<String, Map<?, ?>> anchors = new TreeMap<>();
    private final Map<String, String> anchorSteps = new TreeMap<>();

    /** The longest intent an exported step's comment carries (phase7-contracts.md 6.6). */
    static final int COMMENT_INTENT_MAX = 80;

    /** One scenario step: its verb and arguments, the session step it came from, a comment. */
    private record YamlStep(String verb, Object args, String sessionStep, String comment, List<Expect> expects) {
    }

    /** One line of an {@code expect} step. */
    private record Expect(String path, Object matcher, String comment, String sessionStep) {
    }

    JournalExport(List<StepJournal.Entry> entries, DomainPack pack, ExportOptions options) {
        this.entries = entries;
        this.pack = Objects.requireNonNull(pack, "pack");
        this.options = options;
    }

    ExportResult run() throws ExportException {
        if (entries.isEmpty() || !(entries.get(0) instanceof StepJournal.SessionStart first)) {
            throw new ExportException(null, "the journal does not start with session-start");
        }
        start = first;
        if (!pack.id().equals(start.pack())) {
            throw new ExportException(null, "the journal is of pack " + start.pack() + ", the export uses pack "
                    + pack.id());
        }
        walk();
        String yaml = yaml();
        Plan plan = compile(yaml);
        BindingsFile file = bindingsFile(plan);
        Map<String, byte[]> images = new TreeMap<>();
        if (file != null && !anchors.isEmpty()) {
            file = file.withImages(anchorEntries(images));
        }
        check(plan, file);
        ExportResult.Report report = new ExportResult.Report(options.scenarioId(), start.id(), plan.steps().size(),
                plan.inference(), observed, notHeld, excluded, notes, visionDerived, liveOnly);
        return new ExportResult(yaml, file, report, images);
    }

    // ------------------------------------------------------------------------------------------------ the walk

    private void walk() throws ExportException {
        int stepNumber = 0;
        boolean cut = false;
        for (StepJournal.Entry entry : entries) {
            switch (entry) {
                case StepJournal.ResolveEntry r -> {
                    if (resolves.containsKey(r.slot())) {
                        notes.add("slot " + r.slot() + " was resolved more than once; the last resolution is"
                                + " exported");
                        resolves.remove(r.slot());
                    }
                    resolves.put(r.slot(), r);
                    Object value = r.resolution().get("value");
                    bound.put("$" + r.slot(), value);
                }
                case StepJournal.OracleEntry o -> {
                    oracles.remove(o.name());
                    oracles.put(o.name(), o);
                    bound.put("$oracle." + o.name(), o.value());
                }
                case StepJournal.StepEntry s -> {
                    stepNumber++;
                    String origin = originLabel(s.origin());
                    origins.merge(origin, 1, Integer::sum);
                    String line = s.stepId() + " (" + (s.verb() == null ? "call" : s.verb()) + "): "
                            + s.outcome().get("status") + (s.outcome().get("message") == null ? ""
                            : ": " + s.outcome().get("message"));
                    if (cut) {
                        excluded.add(line + "; after the first failure");
                        continue;
                    }
                    boolean passed = s.succeeded();
                    if (!passed && !options.includeFailed()) {
                        excluded.add(line);
                        continue;
                    }
                    if (passed && !options.includeReads() && isRead(s)) {
                        excluded.add(line + "; a read of " + s.calls().get(0).get("primitive") + " is an"
                                + " observation, not a step (exported with includeReads)");
                        continue;
                    }
                    String live = liveOnly(s);
                    if (live != null) {
                        liveOnly.add(line + "; " + live);
                        continue;
                    }
                    flushGroup();
                    steps.add(step(s));
                    for (Map<String, Object> call : s.calls()) {
                        String capability = start.primitives().get(String.valueOf(call.get("primitive")));
                        if (capability != null && !capability.isBlank()) {
                            requires.add(capability);
                        }
                    }
                    if (passed && s.outcome().get("detail") instanceof Map<?, ?> detail
                            && detail.get("captured") instanceof Map<?, ?> captured) {
                        captured.forEach((slot, value) -> bound.put("$" + slot, value));
                    }
                    if (!passed) {
                        cut = true;
                    }
                }
                case StepJournal.CheckEntry c -> {
                    if (cut) {
                        continue;
                    }
                    if (!c.held()) {
                        notHeld.add("\"" + c.path() + "\" " + Values.safeWrite(c.matcher()) + ": " + c.reason()
                                + " (after step " + stepNumber + ")");
                        continue;
                    }
                    expect(new Expect(c.path(), Values.sortKeys(c.matcher()), null, "s" + stepNumber));
                }
                case StepJournal.ObserveEntry o -> {
                    if (cut) {
                        continue;
                    }
                    if (masked(o.value())) {
                        notes.add("the value observed at " + o.path() + " after step " + stepNumber + " was masked in"
                                + " the journal; it is not exported");
                        continue;
                    }
                    Object matcher = observedMatcher(o.value());
                    expect(new Expect(o.path(), matcher, "observed in session " + start.id() + " at step "
                            + stepNumber + ", not derived", "s" + stepNumber));
                    observed.add("\"" + o.path() + "\": " + YamlText.flow(matcher) + " (step " + stepNumber + ")");
                }
                case StepJournal.BudgetEntry b -> cost = b.spentUsd();
                case StepJournal.SessionEnd e -> cost = e.cost();
                default -> {
                }
            }
        }
        flushGroup();
    }

    /**
     * Why a step is live-only and not exported (phase7-contracts.md 6.5, 6.6, decision D307), or null: it acted through
     * Robot (no CI target has it), or it is a raw {@code swing.pointer} or {@code web.pointer} call (a hover, a press
     * by halves, a page pointer the recorder could not turn into a locator step).
     */
    private static String liveOnly(StepJournal.StepEntry s) {
        if (s.provenance() != null && "ROBOT".equals(s.provenance().get("via"))) {
            return "acted through Robot (V3): live-only, never exported as a step";
        }
        for (Map<String, Object> call : s.calls()) {
            if (call.get("args") instanceof Map<?, ?> args && "ROBOT".equals(args.get("via"))) {
                return "acted through Robot (via: ROBOT): live-only, never exported as a step";
            }
        }
        if (s.verb() == null && s.calls().size() == 1) {
            Object primitive = s.calls().get(0).get("primitive");
            if ("swing.pointer".equals(primitive) || "web.pointer".equals(primitive)) {
                return "a raw " + primitive + " call is live-only";
            }
        }
        return null;
    }

    /**
     * The comment of a step a vision tool chose (phase7-contracts.md 6.6, D356): {@code aiAct "<intent, cut at 80>"
     * by <model>: <role> "<name>" (<class>)}; the journal already masked it. No hash, handle or mark.
     */
    private static String visionComment(StepJournal.StepEntry s) {
        Map<String, Object> p = s.provenance();
        String intent = p.get("intent") instanceof String text ? text : s.origin().intent();
        intent = intent == null ? "" : Values.printableLine(intent);
        if (intent.length() > COMMENT_INTENT_MAX) {
            intent = intent.substring(0, COMMENT_INTENT_MAX);
        }
        intent = intent.replace("\"", "'");
        String model = p.get("model") instanceof String m ? m : s.origin().by();
        Map<?, ?> hit = p.get("hitTest") instanceof Map<?, ?> h ? h : Map.of();
        StringBuilder comment = new StringBuilder("aiAct \"").append(intent).append("\" by ")
                .append(model == null ? "(unnamed model)" : Values.printableLine(model)).append(':');
        if (hit.get("role") != null) {
            comment.append(' ').append(Values.printableLine(String.valueOf(hit.get("role"))));
        }
        if (hit.get("name") != null) {
            comment.append(" \"").append(Values.printableLine(String.valueOf(hit.get("name"))).replace("\"", "'"))
                    .append('"');
        }
        if (hit.get("className") != null) {
            comment.append(" (").append(Values.printableLine(String.valueOf(hit.get("className")))).append(')');
        }
        return comment.toString();
    }

    /** The report line of a vision-derived step: the step, the capture's hash and file, the ref, the mark, the point. */
    private static String visionLine(StepJournal.StepEntry s, YamlStep step) {
        Map<String, Object> p = s.provenance();
        StringBuilder line = new StringBuilder(s.stepId()).append(' ').append(step.verb()).append(' ')
                .append(YamlText.flow(step.args()));
        if (p.get("tier") != null) {
            line.append("; tier ").append(p.get("tier"));
        }
        if (p.get("capture") instanceof Map<?, ?> capture) {
            line.append("; capture ").append(capture.get("hash"));
            if (capture.get("file") != null) {
                line.append(" (").append(capture.get("file")).append(')');
            }
        }
        if (p.get("hitTest") instanceof Map<?, ?> hit) {
            if (hit.get("ref") != null) {
                line.append("; hit-test ").append(hit.get("ref"));
            }
            if (hit.get("mark") != null) {
                line.append(", mark ").append(hit.get("mark"));
            }
        }
        if (p.get("point") instanceof Map<?, ?> point) {
            line.append("; point (").append(point.get("x")).append(", ").append(point.get("y")).append(')');
        }
        if (p.get("anchor") instanceof Map<?, ?> anchor) {
            line.append("; anchor ").append(anchor.get("label")).append(" ").append(anchor.get("sha256"));
        }
        return Values.printableLine(line.toString());
    }

    /** The {@code images} section of the export's bindings file from the anchors, their PNGs into {@code images}. */
    private Map<String, Map<String, BindingsFile.ImageEntry>> anchorEntries(Map<String, byte[]> images)
            throws ExportException {
        Map<String, Map<String, BindingsFile.ImageEntry>> out = new TreeMap<>();
        for (Map.Entry<String, Map<?, ?>> e : anchors.entrySet()) {
            String label = e.getKey();
            Map<?, ?> anchor = e.getValue();
            String step = anchorSteps.get(label);
            String key = anchor.get("renderKey") instanceof String k ? k : null;
            String sha = anchor.get("sha256") instanceof String h ? h : null;
            ai.skillroy.testjava.scenario.image.ImageCompare.Rect rect =
                    ai.skillroy.testjava.scenario.image.ImageCompare.Rect.of(anchor.get("rect"));
            if (key == null || !BindingsFile.RENDER_KEY.matcher(key).matches() || sha == null
                    || !sha.matches("sha256:[0-9a-f]{64}") || rect == null) {
                throw new ExportException(step, "the anchor " + label + " has no render key, hash or rectangle in the"
                        + " journal");
            }
            if (options.captures() == null) {
                throw new ExportException(step, "the anchor " + label + " needs its capture file, and the session kept"
                        + " none (it ran without an output directory)");
            }
            java.nio.file.Path file = options.captures().resolve("captures").resolve(sha.substring(7) + ".png");
            byte[] png;
            java.awt.image.BufferedImage raster;
            try {
                if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new java.io.IOException("no file captures/" + file.getFileName());
                }
                png = java.nio.file.Files.readAllBytes(file);
                raster = ai.skillroy.testjava.scenario.image.ImageCompare.decodePng(png);
            } catch (java.io.IOException failure) {
                throw new ExportException(step, "the anchor " + label + "'s capture file cannot be read: "
                        + failure.getMessage(), failure);
            }
            if (!ai.skillroy.testjava.scenario.image.ImageCompare.rasterHash(raster).equals(sha)) {
                throw new ExportException(step, "the anchor " + label + "'s capture file is not the recorded raster");
            }
            Map<?, ?> focusJson = anchor.get("focus") instanceof Map<?, ?> f ? f : Map.of();
            Object owner = focusJson.containsKey("owner") ? focusJson.get("owner") : focusJson.get("ownerLocator");
            @SuppressWarnings("unchecked")
            BindingsFile.Focus focus = new BindingsFile.Focus(owner instanceof Map<?, ?> o ? (Map<String, Object>) o
                    : null, ai.skillroy.testjava.scenario.image.ImageCompare.Rect.of(focusJson.get("ownerRect")),
                    Boolean.TRUE.equals(focusJson.get("active")));
            @SuppressWarnings("unchecked")
            BindingsFile.ImageEntry entry = new BindingsFile.ImageEntry(sha, raster.getWidth(), raster.getHeight(),
                    rects(anchor.get("masks")), rects(anchor.get("carets")), focus,
                    BindingsFile.ImageEntry.fileName(options.scenarioId(), label, key),
                    anchor.get("rendering") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of(),
                    anchor.get("window") instanceof Map<?, ?> w ? (Map<String, Object>) w : null,
                    new BindingsFile.Anchor(rect.x(), rect.y()));
            out.computeIfAbsent(label, k -> new TreeMap<>()).put(key, entry);
            images.put(label, png);
        }
        return out;
    }

    private static List<ai.skillroy.testjava.scenario.image.ImageCompare.Rect> rects(Object value) {
        List<ai.skillroy.testjava.scenario.image.ImageCompare.Rect> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Object rect = item instanceof Map<?, ?> m && m.containsKey("rect") ? m.get("rect") : item;
                ai.skillroy.testjava.scenario.image.ImageCompare.Rect r =
                        ai.skillroy.testjava.scenario.image.ImageCompare.Rect.of(rect);
                if (r != null) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /**
     * True for a raw call (no verb) of one primitive that changes nothing, by the {@code reads} of the journal's
     * {@code session-start}, and that captures no slot (a later step may refer to a capture).
     */
    private boolean isRead(StepJournal.StepEntry s) {
        if (s.verb() != null || s.calls().size() != 1) {
            return false;
        }
        Map<String, Object> call = s.calls().get(0);
        return call.get("capture") == null && start.reads().contains(String.valueOf(call.get("primitive")));
    }

    private static String originLabel(Origin origin) {
        return switch (origin.kind()) {
            case PERSON -> "person";
            case MODEL -> "model " + (origin.by() == null ? "(unnamed)" : origin.by());
            case SYSTEM -> "system";
            case CLIENT -> "client " + (origin.by() == null ? "(unnamed)" : origin.by());
        };
    }

    /** Adds an expectation to the pending {@code expect} step; a path it already has starts a new one. */
    private void expect(Expect line) {
        for (Expect pending : group) {
            if (pending.path().equals(line.path())) {
                flushGroup();
                break;
            }
        }
        group.add(line);
    }

    private void flushGroup() {
        if (!group.isEmpty()) {
            steps.add(new YamlStep("expect", null, group.get(0).sessionStep(), null, List.copyOf(group)));
            group = new ArrayList<>();
        }
    }

    /** One exported step: the verb with its arguments as given, or a raw call as the base verb {@code call}. */
    private YamlStep step(StepJournal.StepEntry s) throws ExportException {
        if (s.verb() != null) {
            if (masked(s.args())) {
                throw new ExportException(s.stepId(), "an argument of " + s.verb() + " was masked in the journal (it"
                        + " held a secret); give it as a reference to the slot's credential field instead");
            }
            YamlStep step = new YamlStep(s.verb(), Values.sortKeys(s.args()), s.stepId(),
                    s.provenance() == null ? null : visionComment(s), List.of());
            if (s.provenance() != null) {
                visionDerived.add(visionLine(s, step));
                if (s.provenance().get("anchor") instanceof Map<?, ?> anchor && anchor.get("label") instanceof String l) {
                    anchors.put(l, anchor);
                    anchorSteps.put(l, s.stepId());
                }
            }
            return step;
        }
        if (s.calls().size() != 1) {
            throw new ExportException(s.stepId(), "a raw call step has " + s.calls().size() + " calls");
        }
        Map<String, Object> call = s.calls().get(0);
        String primitive = String.valueOf(call.get("primitive"));
        Map<?, ?> template = s.args() instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> args = new LinkedHashMap<>();
        if (call.get("args") instanceof Map<?, ?> sent) {
            for (Map.Entry<?, ?> e : sent.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object given = template.get(key);
                if (given != null && holdsReference(given)) {
                    args.put(key, given);
                } else if (masked(e.getValue())) {
                    throw new ExportException(s.stepId(), "argument '" + key + "' of " + primitive + " was masked in"
                            + " the journal (it held a secret); a call step can carry a secret only as a reference to"
                            + " a slot's credential field");
                } else {
                    args.put(key, writeBack(e.getValue()));
                }
            }
        }
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("primitive", primitive);
        if (!args.isEmpty()) {
            yaml.put("args", Values.sortKeys(args));
        }
        yaml.put("sync", String.valueOf(call.get("sync")));
        Object capture = call.get("capture");
        if (call.get("await") instanceof Map<?, ?> await) {
            Map<String, Object> awaitYaml = new LinkedHashMap<>();
            awaitYaml.put("topics", await.get("topics"));
            if (await.get("match") != null) {
                awaitYaml.put("match", await.get("match"));
            }
            if (await.get("timeoutMs") instanceof Number ms && ms.longValue() > 0) {
                awaitYaml.put("within", ms.longValue() + "ms");
            }
            if (capture != null) {
                awaitYaml.put("capture", capture);
            }
            yaml.put("await", awaitYaml);
        } else if (capture != null) {
            throw new ExportException(s.stepId(), "a call of " + primitive + " that captures a slot without an"
                    + " await has no form as a call step (the base verb call captures inside 'await')");
        }
        long stepTimeoutMs = stepTimeoutMs();
        if (call.get("timeoutMs") instanceof Number timeout && timeout.longValue() != stepTimeoutMs) {
            notes.add("step " + s.stepId() + ": the call's own timeout of " + timeout + " ms has no form in a call"
                    + " step; the exported call takes the step timeout (" + stepTimeoutMs + " ms)");
        }
        return new YamlStep("call", yaml, s.stepId(), null, List.of());
    }

    /**
     * A value as a scenario writes it: equal to a bound slot's value or field, the reference; a text, with its
     * {@code $} written {@code $$} (a literal, not a reference); a structure, element by element.
     */
    private Object writeBack(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        String reference = referenceTo(value);
        if (reference != null) {
            return reference;
        }
        if (value instanceof String text) {
            return text.replace("$", "$$");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), writeBack(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(writeBack(item));
            }
            return out;
        }
        return value;
    }

    /** The first reference (in binding order, then depth first) whose value equals {@code value}; null if none. */
    private String referenceTo(Object value) {
        if (value instanceof String text && text.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> e : bound.entrySet()) {
            String found = search(e.getValue(), e.getKey(), value, 0);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String search(Object candidate, String reference, Object value, int depth) {
        if (depth > 20 || candidate == null || candidate instanceof Boolean) {
            return null;
        }
        if (candidate.equals(value)) {
            return reference;
        }
        if (candidate instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (FIELD.matcher(key).matches()) {
                    String found = search(e.getValue(), reference + "." + key, value, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } else if (candidate instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String found = search(list.get(i), reference + "." + i, value, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** An observed value as an expectation: a mapping as {@code {equals: …}}, texts with {@code $$}. */
    private static Object observedMatcher(Object value) {
        Object literal = literal(value);
        if (literal instanceof Map<?, ?>) {
            Map<String, Object> matcher = new LinkedHashMap<>();
            matcher.put("equals", literal);
            return matcher;
        }
        return literal;
    }

    private static Object literal(Object value) {
        if (value instanceof String text) {
            return text.replace("$", "$$");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), literal(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(literal(item));
            }
            return out;
        }
        return value;
    }

    private static boolean masked(Object value) {
        if (value instanceof String text) {
            return text.contains(SecretKeys.REDACTED);
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (masked(item)) {
                    return true;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (masked(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean holdsReference(Object value) {
        if (value instanceof String text) {
            return References.containsReference(text);
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (holdsReference(item)) {
                    return true;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (holdsReference(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private long stepTimeoutMs() {
        Object ms = start.options().get("stepTimeoutMs");
        return ms instanceof Number n && n.longValue() > 0 ? n.longValue() : Scenario.DEFAULT_STEP_TIMEOUT_MS;
    }

    // ------------------------------------------------------------------------------------------------ the YAML

    /** The scenario's text and, per scenario step, the line it starts on and the session step it came from. */
    private final List<int[]> stepLines = new ArrayList<>();
    private final List<String> stepOrigins = new ArrayList<>();

    private String yaml() throws ExportException {
        YamlText yaml = new YamlText();
        String date = start.startedAt() == null || start.startedAt().length() < 10 ? "an unknown date"
                : start.startedAt().substring(0, 10);
        String description = options.description() != null && !options.description().isBlank()
                ? options.description() : "Exported from session " + start.id() + " on " + date + ".";
        yaml.comment(0, description);
        yaml.comment(0, "Session " + start.id() + " on " + date + ", pack " + start.pack() + " "
                + start.packVersion() + ", environment " + start.envId() + ".");
        List<String> acted = new ArrayList<>();
        Integer person = origins.get("person");
        if (person != null) {
            acted.add("person: " + person + plural(person));
        }
        origins.forEach((origin, count) -> {
            if (!"person".equals(origin)) {
                acted.add(origin + ": " + count + plural(count));
            }
        });
        yaml.comment(0, "Acted: " + (acted.isEmpty() ? "nobody" : String.join(", ", acted)) + ".");
        yaml.comment(0, "Model cost: " + (cost == null ? "none recorded" : "$" + cost) + ".");
        yaml.entry(0, "scenario", options.scenarioId(), null);
        yaml.entry(0, "pack", start.pack(), null);
        if (!requires.isEmpty()) {
            yaml.entry(0, "requires", new ArrayList<>(requires), null);
        }
        if (!resolves.isEmpty()) {
            yaml.line("resolve:");
            for (StepJournal.ResolveEntry r : resolves.values()) {
                yaml.entry(2, r.slot(), slot(r), null);
            }
        }
        if (!oracles.isEmpty()) {
            yaml.line("oracle:");
            for (StepJournal.OracleEntry o : oracles.values()) {
                if (masked(o.args())) {
                    throw new ExportException(null, "the arguments of oracle " + o.name() + " were masked in the"
                            + " journal");
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("fn", o.fn());
                if (o.args() != null) {
                    entry.put("args", Values.sortKeys(o.args()));
                }
                yaml.entry(2, o.name(), entry, null);
            }
        }
        if (steps.isEmpty()) {
            yaml.line("steps: []");
        } else {
            yaml.line("steps:");
            for (YamlStep step : steps) {
                stepLines.add(new int[] {yaml.lines() + 1});
                stepOrigins.add(step.sessionStep());
                if (!"expect".equals(step.verb()) || step.expects().isEmpty()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put(step.verb(), step.args());
                    yaml.item(2, item, step.comment());
                } else if (step.expects().size() == 1) {
                    Expect only = step.expects().get(0);
                    Map<String, Object> checks = new LinkedHashMap<>();
                    checks.put(only.path(), only.matcher());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("expect", checks);
                    yaml.item(2, item, only.comment());
                } else {
                    yaml.line("  - expect:");
                    for (Expect line : step.expects()) {
                        yaml.entry(6, line.path(), line.matcher(), line.comment());
                    }
                }
            }
        }
        yaml.entry(0, "timeouts", Map.of("step", duration(stepTimeoutMs())), null);
        if (start.options().get("ui") instanceof Map<?, ?> ui) {
            yaml.entry(0, "ui", ui, null);
        }
        if (start.options().get("web") instanceof Map<?, ?> web) {
            yaml.entry(0, "web", web, null);
        }
        return yaml.text();
    }

    private static String plural(int count) {
        return count == 1 ? " step" : " steps";
    }

    /** A {@code resolve:} slot from its journal entry. */
    private Map<String, Object> slot(StepJournal.ResolveEntry r) throws ExportException {
        if (masked(r.args())) {
            throw new ExportException(null, "the arguments of slot " + r.slot() + " were masked in the journal");
        }
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("query", r.kind());
        if (!r.args().isEmpty()) {
            slot.put("args", Values.sortKeys(r.args()));
        }
        List<String> tokens = new ArrayList<>(r.strategy());
        if (tier(r) == Tier.B && !tokens.contains(Strategy.LLM_SQL.token())) {
            // The plan's inference must say bind when a tier (b) value is exported: name the token, after the
            // pack's own (an empty list meant "any", which the pack's tokens and llm-sql still say).
            if (tokens.isEmpty()) {
                tokens.addAll(packTokens(r.kind()));
            }
            tokens.add(Strategy.LLM_SQL.token());
        }
        if (!tokens.isEmpty()) {
            slot.put("strategy", tokens);
        }
        if (!r.verify().isEmpty()) {
            slot.put("verify", r.verify());
        }
        if (r.credential() != null) {
            Map<String, Object> credential = new LinkedHashMap<>();
            credential.put("realm", r.credential().realm());
            credential.put("key", r.credential().key());
            slot.put("credential", credential);
        }
        return slot;
    }

    private List<String> packTokens(String kind) {
        Set<String> tokens = new LinkedHashSet<>();
        try {
            ResolutionCatalogue catalogue = pack.resolution();
            if (catalogue != null) {
                for (Resolver<?, ?> resolver : catalogue.resolvers(kind)) {
                    Strategy strategy = resolver.strategy();
                    if (strategy != null && strategy.token() != null && !strategy.needsModel()) {
                        tokens.add(strategy.token());
                    }
                }
            }
        } catch (RuntimeException e) {
            // The compile check reports a broken pack.
        }
        return new ArrayList<>(tokens);
    }

    private static Tier tier(StepJournal.ResolveEntry r) {
        Object strategy = r.resolution().get("strategy");
        try {
            Strategy parsed = Strategy.valueOf(String.valueOf(strategy));
            return parsed.tier();
        } catch (IllegalArgumentException e) {
            return Tier.A;
        }
    }

    /** A duration as a scenario writes it: {@code 2m}, {@code 30s} or {@code 1500ms}. */
    static String duration(long ms) {
        if (ms % 60_000L == 0) {
            return ms / 60_000L + "m";
        }
        if (ms % 1000L == 0) {
            return ms / 1000L + "s";
        }
        return ms + "ms";
    }

    // ------------------------------------------------------------------------------------------------ the check

    private Plan compile(String yaml) throws ExportException {
        try {
            return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), options.scenarioId() + ".yaml", pack);
        } catch (ScenarioException e) {
            throw new ExportException(sessionStepAtLine(e.line()), "the exported scenario does not compile: "
                    + e.reason(), e);
        }
    }

    /** The session step whose scenario step starts at or before {@code line}; null for the head. */
    private String sessionStepAtLine(int line) {
        String found = null;
        for (int i = 0; i < stepLines.size(); i++) {
            if (stepLines.get(i)[0] <= line) {
                found = stepOrigins.get(i);
            }
        }
        return line > 0 ? found : null;
    }

    /** The bindings file of the exported slots and oracles, as {@code approve} writes one; null without any. */
    private BindingsFile bindingsFile(Plan plan) throws ExportException {
        if (!plan.needsBindings()) {
            return null;
        }
        Map<String, Resolution> resolutions = new LinkedHashMap<>();
        Map<String, OracleValue> values = new LinkedHashMap<>();
        for (String node : plan.resolutionGraph()) {
            if (node.startsWith("oracle.")) {
                StepJournal.OracleEntry o = oracles.get(node.substring("oracle.".length()));
                values.put(o.name(), new OracleValue(o.name(), o.fn(), o.inputsHash(), o.value(), null, null));
                continue;
            }
            StepJournal.ResolveEntry r = resolves.get(node);
            if (r == null) {
                continue;
            }
            Resolution resolution;
            try {
                resolution = r.toResolution();
            } catch (IllegalArgumentException e) {
                throw new ExportException(null, e.getMessage(), e);
            }
            resolutions.put(node, resolution.withApproval(approval(resolution)));
        }
        Map<String, String> components = new LinkedHashMap<>();
        if (start.env().get("components") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> components.put(String.valueOf(k), String.valueOf(v)));
        }
        Object fingerprint = start.env().get("fingerprint");
        EnvFingerprint env = new EnvFingerprint(EnvFingerprint.VERSION, fingerprint == null ? "" :
                String.valueOf(fingerprint), components);
        BindingsFile file = new BindingsFile(BindingsFile.VERSION, options.scenarioId(), start.envId(), plan.pack(),
                plan.packVersion(), plan.hash(), plan.resolveHash(), env, resolutions, values);
        if (file.toJsonText().contains(SecretKeys.REDACTED)) {
            throw new ExportException(null, "a recorded value was masked in the journal: it equals a secret, and a"
                    + " bindings file never holds one");
        }
        return file;
    }

    /**
     * {@code approve}'s approval of an exported slot: tier (a) {@code NOT_REQUIRED}, by the exporter's label or else
     * the mode's ({@code live}), as the binder labels what {@code live} writes; tier (b) {@code APPROVED} by the
     * exporter when one is named, else {@code REQUIRED} by nobody ({@code by: null}, as the binder writes a value a
     * model found in {@code live}; a later {@code replay} refuses it until a person approves).
     */
    private Approval approval(Resolution resolution) throws ExportException {
        if (resolution.strategy().tier() != Tier.B) {
            return new Approval(Approval.State.NOT_REQUIRED, options.approvedBy() != null ? options.approvedBy()
                    : BindMode.LIVE.label());
        }
        if (options.approvedBy() != null) {
            return new Approval(Approval.State.APPROVED, options.approvedBy());
        }
        return new Approval(required(), null);
    }

    /** {@code Approval.State.REQUIRED} (phase5-contracts.md 7.1): what a tier (b) slot gets until a person approves. */
    private static Approval.State required() {
        return Approval.State.REQUIRED;
    }

    /**
     * {@link Binder#check} with the exported bindings: the slots' and oracles' values, the captured slots by their
     * declarations, and a slot's credential fields that a call names standing in as the run would join them.
     */
    private void check(Plan plan, BindingsFile file) throws ExportException {
        Map<String, Object> valueMap = new LinkedHashMap<>();
        Map<String, String> strategies = new LinkedHashMap<>();
        Map<String, Resolution> resolutions = file == null ? Map.of() : file.resolutions();
        Map<String, CredentialRef> credentials = new LinkedHashMap<>();
        for (Resolution r : resolutions.values()) {
            valueMap.put(r.slot(), r.value());
            strategies.put(r.slot(), r.strategy().name());
            if (r.credential() != null) {
                credentials.put(r.slot(), r.credential());
            }
        }
        Bindings bindings = Bindings.of(valueMap, strategies).withBind(start.envId(), BindMode.LIVE,
                file == null ? null : file.env(), Map.of(), resolutions, file == null ? Map.of() : file.oracles(),
                credentials, Map.of());
        for (Map.Entry<String, Set<String>> e : credentialFields(plan, valueMap, credentials).entrySet()) {
            Map<String, String> standIns = new LinkedHashMap<>();
            for (String field : e.getValue()) {
                standIns.put(field, "");
            }
            bindings = bindings.withCredential(e.getKey(), standIns);
        }
        try {
            Binder.check(plan, bindings);
        } catch (ResolutionException e) {
            String step = null;
            Matcher m = STEP_ID.matcher(String.valueOf(e.getMessage()));
            if (m.find()) {
                int index = Integer.parseInt(m.group(1).substring(1)) - 1;
                step = index >= 0 && index < stepOrigins.size() ? stepOrigins.get(index) : null;
            }
            throw new ExportException(step, "a reference of the exported scenario does not resolve: " + e.getMessage(),
                    e);
        }
    }

    /** Per slot with a credential, the fields the plan's calls name that its value does not have. */
    private static Map<String, Set<String>> credentialFields(Plan plan, Map<String, Object> values,
                                                             Map<String, CredentialRef> credentials) {
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        List<Plan.Call> calls = new ArrayList<>(plan.cleanup());
        for (Plan.Step step : plan.steps()) {
            calls.addAll(step.calls());
        }
        for (Plan.Call call : calls) {
            for (References.Ref ref : References.collect(call.args())) {
                if (!credentials.containsKey(ref.slot()) || ref.fields().isEmpty()) {
                    continue;
                }
                String field = ref.fields().get(0);
                if (values.get(ref.slot()) instanceof Map<?, ?> value && !value.containsKey(field)) {
                    fields.computeIfAbsent(ref.slot(), k -> new LinkedHashSet<>()).add(field);
                }
            }
        }
        return fields;
    }
}
