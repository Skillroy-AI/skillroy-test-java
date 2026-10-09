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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.lang.instrument.ClassDefinition;
import java.util.Arrays;
import java.util.List;

/**
 * BS3 item 3: what the JVM hands a transformer at the first transform, and when the post-detach comparison holds or
 * fails. Every scenario uses a fresh toy loader, so its class has never been transformed, and hooks
 * {@code DepositRule.rateName()}, whose string constant {@code "rate:standard"} shows any other agent's change.
 */
final class ByteChecks {

    private static final String RATE_DESC = "()Ljava/lang/String;";

    private final Ctx c;

    ByteChecks(Ctx c) {
        this.c = c;
    }

    void run() throws Throwable {
        c.r.section("item 3: byte check scenarios");
        plain();
        foreignAfterInstall();
        foreignBeforeInstall();
        redefineWithFileBytes();
        redefineChanged();
        foreignAtLoadRetransformable();
        foreignAtLoadNotRetransformable();
    }

    private Object fresh(String name) throws Exception {
        return c.host.newToyLoader(name).loadClass(HostView.DEPOSIT_RULE).newInstance();
    }

    private static String rate(Object rule) {
        try {
            return String.valueOf(HostView.invoke(rule, "rateName", new Class<?>[0]));
        } catch (Throwable t) {
            return "threw " + t;
        }
    }

    private HookEngine.Installed hook(String id, Class<?> k) {
        return c.engine.install(new HookEngine.Spec(id, k, "rateName", RATE_DESC, HookMode.OBSERVE));
    }

    private void record(String key, HookEngine.Installed in, HookEngine.Removal rm, String before, String during,
                        String after) {
        Report r = c.r;
        r.put(key + ".install", in.status + " " + in.errors);
        r.put(key + ".firstTransform", rm.first == null ? "none" : ClassFacts.summary(rm.first));
        r.put(key + ".bytesIdentical", rm.bytesIdentical);
        r.put(key + ".canonicalIdentical", rm.canonicalIdentical);
        r.put(key + ".diff", rm.diff);
        r.put(key + ".foreignEvents", rm.foreignEvents);
        r.put(key + ".behaviour", "before=" + before + " hooked=" + during + " after=" + after);
    }

    /** BC0: no other agent. The JVM's input versus the class file; first transform versus after restore. */
    private void plain() throws Throwable {
        Object rule = fresh("bc0");
        Class<?> k = rule.getClass();
        byte[] file = HostView.classFile(k);
        String before = rate(rule);
        HookEngine.Installed in = hook("bc0.rate", k);
        String during = rate(rule);
        HookEngine.Removal rm = c.engine.remove("bc0.rate", false);
        byte[] again = c.engine.currentBytes(k);
        String after = rate(rule);
        record("item3.bc0", in, rm, before, during, after);
        List<String> fileVsFirst = ClassFacts.diff("file", file, "first", rm.first);
        c.r.put("item3.bc0.fileVsFirst", fileVsFirst);
        c.r.put("item3.bc0.fileVsFirstCanonical", Canon.hash(file).equals(Canon.hash(rm.first)));
        c.r.put("item3.bc0.onceMoreIdentical", again != null && Arrays.equals(again, rm.first));
        c.r.dump("DepositRule.file", file);
        c.r.dump("DepositRule.first", rm.first);
        c.check("item3.bc0.restoredByteIdentical", rm.bytesIdentical && again != null
                && Arrays.equals(again, rm.first), rm.diff);
    }

    /** BC1: another agent's retransformable transformer registered after our install, still present at restore. */
    private void foreignAfterInstall() throws Throwable {
        Object rule = fresh("bc1");
        Class<?> k = rule.getClass();
        String before = rate(rule);
        HookEngine.Installed in = hook("bc1.rate", k);
        ForeignPatch foreign = new ForeignPatch(k.getName(), k.getClassLoader(), "rate:standard", "rate:STANDARD");
        c.inst.addTransformer(foreign, true);
        String during = rate(rule);
        HookEngine.Removal rm = c.engine.remove("bc1.rate", false);
        String after = rate(rule);
        c.inst.removeTransformer(foreign);
        c.inst.retransformClasses(k);
        record("item3.bc1", in, rm, before, during, after);
        c.r.put("item3.bc1.foreignApplied", foreign.applied.get());
        c.r.put("item3.bc1.afterForeignGone", rate(rule));
        c.check("item3.bc1.detectsForeignTransformer", !rm.bytesIdentical && "rate:STANDARD".equals(after),
                "after restore: " + after);
    }

    /** BC2: another agent's retransformable transformer registered before our install, never applied until then. */
    private void foreignBeforeInstall() throws Throwable {
        Object rule = fresh("bc2");
        Class<?> k = rule.getClass();
        ForeignPatch foreign = new ForeignPatch(k.getName(), k.getClassLoader(), "rate:standard", "rate:STANDARD");
        c.inst.addTransformer(foreign, true);
        String before = rate(rule);
        HookEngine.Installed in = hook("bc2.rate", k);
        String during = rate(rule);
        HookEngine.Removal rm = c.engine.remove("bc2.rate", false);
        String after = rate(rule);
        c.inst.removeTransformer(foreign);
        c.inst.retransformClasses(k);
        record("item3.bc2", in, rm, before, during, after);
        c.r.put("item3.bc2.foreignApplied", foreign.applied.get());
        c.check("item3.bc2.ourRetransformActivatesPendingForeign", rm.bytesIdentical
                && "rate:standard".equals(before) && "rate:STANDARD".equals(after),
                "before " + before + ", after " + after + ", bytes identical " + rm.bytesIdentical);
    }

    /** BC3: someone redefines the class with its own class-file bytes while the hook is installed. */
    private void redefineWithFileBytes() throws Throwable {
        Object rule = fresh("bc3");
        Class<?> k = rule.getClass();
        byte[] file = HostView.classFile(k);
        String before = rate(rule);
        HookEngine.Installed in = hook("bc3.rate", k);
        c.inst.redefineClasses(new ClassDefinition(k, file));
        String during = rate(rule);
        HookEngine.Removal rm = c.engine.remove("bc3.rate", false);
        String after = rate(rule);
        record("item3.bc3", in, rm, before, during, after);
        c.r.put("item3.bc3.afterEqualsFile", rm.after != null && Arrays.equals(rm.after, file));
        c.r.put("item3.bc3.afterEqualsFirst", rm.after != null && Arrays.equals(rm.after, rm.first));
        c.check("item3.bc3.foreignRedefineDetected", rm.foreignEvents >= 1 && rm.canonicalIdentical
                && before.equals(after), "foreign events " + rm.foreignEvents + ", bytes identical "
                + rm.bytesIdentical + ", canonical " + rm.canonicalIdentical);
    }

    /** BC4: someone redefines the class with changed bytes while the hook is installed. */
    private void redefineChanged() throws Throwable {
        Object rule = fresh("bc4");
        Class<?> k = rule.getClass();
        byte[] file = HostView.classFile(k);
        byte[] changed = ForeignPatch.patch(file, "rate:standard".getBytes("UTF-8"),
                "rate:redefine".getBytes("UTF-8"));
        String before = rate(rule);
        HookEngine.Installed in = hook("bc4.rate", k);
        c.inst.redefineClasses(new ClassDefinition(k, changed));
        String during = rate(rule);
        HookEngine.Removal rm = c.engine.remove("bc4.rate", false);
        String after = rate(rule);
        record("item3.bc4", in, rm, before, during, after);
        c.r.put("item3.bc4.redefineSurvivesRestore", "rate:redefine".equals(after));
        c.check("item3.bc4.foreignRedefineDetected", rm.foreignEvents >= 1 && "rate:redefine".equals(during),
                "foreign events " + rm.foreignEvents + ", after the restore " + after + ", bytes identical "
                + rm.bytesIdentical);
    }

    /** BC5: a retransformable transformer changed the class at load and was removed before we arrived. */
    private void foreignAtLoadRetransformable() throws Throwable {
        ClassLoader l = c.host.newToyLoader("bc5");
        ForeignPatch foreign = new ForeignPatch(HostView.DEPOSIT_RULE, l, "rate:standard", "rate:STANDARD");
        c.inst.addTransformer(foreign, true);
        Object rule = l.loadClass(HostView.DEPOSIT_RULE).newInstance();
        c.inst.removeTransformer(foreign);
        Class<?> k = rule.getClass();
        String before = rate(rule);
        HookEngine.Installed in = hook("bc5.rate", k);
        String during = rate(rule);
        HookEngine.Removal rm = c.engine.remove("bc5.rate", false);
        String after = rate(rule);
        record("item3.bc5", in, rm, before, during, after);
        c.check("item3.bc5.ourRetransformUndoesEarlierForeignChange", rm.bytesIdentical
                && "rate:STANDARD".equals(before) && "rate:standard".equals(after),
                "before " + before + ", after " + after);
    }

    /** BC6: a non-retransformable transformer changed the class at load (and stays registered). */
    private void foreignAtLoadNotRetransformable() throws Throwable {
        ClassLoader l = c.host.newToyLoader("bc6");
        ForeignPatch foreign = new ForeignPatch(HostView.DEPOSIT_RULE, l, "rate:standard", "rate:STANDARD");
        c.inst.addTransformer(foreign, false);
        Object rule = l.loadClass(HostView.DEPOSIT_RULE).newInstance();
        Class<?> k = rule.getClass();
        String before = rate(rule);
        HookEngine.Installed in = hook("bc6.rate", k);
        String during = rate(rule);
        HookEngine.Removal rm = c.engine.remove("bc6.rate", false);
        String after = rate(rule);
        c.inst.removeTransformer(foreign);
        record("item3.bc6", in, rm, before, during, after);
        c.r.put("item3.bc6.foreignApplied", foreign.applied.get());
        c.check("item3.bc6.keepsLoadTimeChange", rm.bytesIdentical && "rate:STANDARD".equals(before)
                && "rate:STANDARD".equals(after), "before " + before + ", after " + after);
    }
}
