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

package ai.skillroy.testjava.agent.core;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Identity of the running JVM: the pid and the JVM start time, which together name a live process unambiguously (a
 * pid alone may be reused).
 *
 * <p>Why not {@code RuntimeMXBean.getName()} ("pid@host"): on Java 8 and later it calls
 * {@code InetAddress.getLocalHost().getHostName()}, a resolver lookup that can block for seconds or tens of seconds
 * on a machine whose own host name does not resolve, and that initialises the network stack (findings S20, S29). The
 * pid is therefore read, in this order, from {@code ProcessHandle.current().pid()} (Java 9+, reflectively), from the
 * HotSpot management object behind the runtime bean (Java 8, reflectively; no name lookup), from
 * {@code /proc/self} (Linux), and only as a last resort from {@code getName()}. Callers run all of this on the
 * agent's bounded start thread, never on the host's calling thread.
 *
 * <p>Stateless; safe from any thread.
 */
final class JvmIds {

    /** The thread that last asked for the pid (for the test that it is never the host's calling thread). */
    static volatile Thread lastPidCaller;

    private JvmIds() {
    }

    /**
     * This JVM's pid, or -1 if no source knows it. Never throws. May do a host-name lookup only in its last resort,
     * so call it on an agent thread.
     */
    static long pid() {
        lastPidCaller = Thread.currentThread();
        long pid = pidWithoutNameLookup();
        return pid > 0 ? pid : fromRuntimeName();
    }

    /** The pid from every source that cannot do a name lookup, or -1. */
    static long pidWithoutNameLookup() {
        long pid = fromProcessHandle();
        if (pid <= 0) {
            pid = fromVmManagement();
        }
        if (pid <= 0) {
            pid = fromProcSelf();
        }
        return pid;
    }

    /** Java 9+: {@code ProcessHandle.current().pid()}, called reflectively; -1 elsewhere. */
    static long fromProcessHandle() {
        try {
            Class<?> handle = Class.forName("java.lang.ProcessHandle");
            Object current = handle.getMethod("current").invoke(null);
            Object pid = handle.getMethod("pid").invoke(current);
            return pid instanceof Number ? ((Number) pid).longValue() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * Java 8 HotSpot: the private {@code VMManagementImpl.getProcessId()} behind the runtime bean's {@code jvm} field,
     * which does no name lookup; -1 where the members are missing or not accessible (any later JDK).
     */
    static long fromVmManagement() {
        try {
            Object bean = ManagementFactory.getRuntimeMXBean();
            Field jvm = bean.getClass().getDeclaredField("jvm");
            jvm.setAccessible(true);
            Object management = jvm.get(bean);
            Method getProcessId = management.getClass().getDeclaredMethod("getProcessId");
            getProcessId.setAccessible(true);
            Object pid = getProcessId.invoke(management);
            return pid instanceof Number ? ((Number) pid).longValue() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** Linux: the name {@code /proc/self} links to; -1 elsewhere. */
    static long fromProcSelf() {
        try {
            File self = new File("/proc/self");
            if (!self.exists()) {
                return -1L;
            }
            return Long.parseLong(self.getCanonicalFile().getName());
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** The last resort: the pid part of {@code RuntimeMXBean.getName()}, which may look up the host name; or -1. */
    static long fromRuntimeName() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return Long.parseLong(at > 0 ? name.substring(0, at) : name);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** JVM start time in epoch milliseconds ({@code getStartTime} does no name lookup). */
    static long startTimeMs() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    /** The main class or jar from {@code sun.java.command}, without the program arguments (which may be secret). */
    static String mainCommand() {
        String command = System.getProperty("sun.java.command", "").trim();
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }
}
