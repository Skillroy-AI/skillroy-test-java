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

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.concurrent.Callable;

/**
 * The host as the probe sees it: reflection over the host's {@code HostTargets} through the system loader. The probe
 * never links against host types; it calls the host's methods the way a hook engine would reach them, by name.
 */
final class HostView {

    static final String TARGETS = "ai.skillroy.testjava.spikes.bs3.host.HostTargets";
    static final String ACCESS_CONTROL = "ai.skillroy.testjava.samples.lending.AccessControl";
    static final String ACCESS_DECISION = "ai.skillroy.testjava.samples.lending.AccessDecision";
    static final String DEPOSIT_RULE = "ai.skillroy.testjava.spikes.bs3.toy.DepositRule";

    final Class<?> targets;

    HostView() throws ClassNotFoundException {
        targets = Class.forName(TARGETS, true, ClassLoader.getSystemClassLoader());
    }

    Object field(String name) throws Exception {
        return targets.getField(name).get(null);
    }

    Object call(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = targets.getMethod(method, types);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) {
                throw (Exception) c;
            }
            if (c instanceof Error) {
                throw (Error) c;
            }
            throw e;
        }
    }

    /** Runs the task on the host's own "lending-desk" thread and returns its result (bounded). */
    Object onDesk(Callable<?> task) throws Exception {
        return call("onDesk", new Class<?>[] {Callable.class, long.class}, task, 10000L);
    }

    /** The desk's access decision, made on the desk thread: {@code AccessControl.check(code, permission, amount)}. */
    Object check(final String code, final String permission, final String amount) throws Exception {
        return onDesk(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return HostView.this.call("check", new Class<?>[] {String.class, String.class, String.class}, code,
                        permission, amount);
            }
        });
    }

    Class<?> accessControlClass() throws Exception {
        return field("ACCESS").getClass();
    }

    Object newDecision(boolean allowed, BigDecimal limit, String reason) throws Exception {
        Class<?> c = Class.forName(ACCESS_DECISION, false, accessControlClass().getClassLoader());
        return c.getConstructor(boolean.class, BigDecimal.class, String.class).newInstance(allowed, limit, reason);
    }

    /** Reads a decision through its accessors (host code, called on the probe thread, outside any hook). */
    static String decision(Object d) {
        if (d == null) {
            return "null";
        }
        try {
            Class<?> c = d.getClass();
            return "allowed=" + c.getMethod("allowed").invoke(d) + " limit=" + c.getMethod("limit").invoke(d)
                    + " reason=" + c.getMethod("reason").invoke(d);
        } catch (Exception e) {
            return "unreadable " + e;
        }
    }

    /** Calls a no-argument or simple public method of a toy object reflectively, returning the cause on failure. */
    static Object invoke(Object target, String method, Class<?>[] types, Object... args) throws Throwable {
        try {
            return target.getClass().getMethod(method, types).invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    ClassLoader newToyLoader(String name) throws Exception {
        return (ClassLoader) call("newToyLoader", new Class<?>[] {String.class}, name);
    }

    ClassLoader newStrictLoader(String name) throws Exception {
        return (ClassLoader) call("newStrictLoader", new Class<?>[] {String.class}, name);
    }

    File toyJar() throws Exception {
        return (File) field("TOY_JAR");
    }

    static byte[] classFile(Class<?> c) throws Exception {
        String res = c.getName().replace('.', '/') + ".class";
        ClassLoader l = c.getClassLoader();
        InputStream in = l == null ? ClassLoader.getSystemResourceAsStream(res) : l.getResourceAsStream(res);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
