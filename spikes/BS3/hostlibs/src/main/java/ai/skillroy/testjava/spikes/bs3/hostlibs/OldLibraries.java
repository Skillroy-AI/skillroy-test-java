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

package ai.skillroy.testjava.spikes.bs3.hostlibs;

import java.util.concurrent.atomic.AtomicInteger;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.objectweb.asm.ClassReader;

/**
 * Host code that uses the host's own, older copies of Byte Buddy (1.12.19), ASM (5.0.3) and JNA (4.5.2), from the
 * system class path, before and after the probe (BS3 item 2). Each use is independent; a failure is reported, not
 * thrown.
 */
public final class OldLibraries {

    private static final AtomicInteger COUNT = new AtomicInteger();

    private OldLibraries() {
    }

    public static String exercise() {
        StringBuilder b = new StringBuilder();
        try {
            Class<?> made = new ByteBuddy()
                    .subclass(Object.class)
                    .name("ai.skillroy.testjava.spikes.bs3.hostlibs.MadeByHost" + COUNT.incrementAndGet())
                    .method(ElementMatchers.named("toString"))
                    .intercept(FixedValue.value("made by the host's own Byte Buddy"))
                    .make()
                    .load(OldLibraries.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                    .getLoaded();
            b.append("byte-buddy ").append(ByteBuddy.class.getPackage().getImplementationVersion()).append(": ")
                    .append(made.newInstance());
        } catch (Throwable t) {
            b.append("byte-buddy failed: ").append(t);
        }
        try {
            ClassReader reader = new ClassReader(OldLibraries.class.getName());
            b.append("; asm read ").append(reader.getClassName());
        } catch (Throwable t) {
            b.append("; asm failed: ").append(t);
        }
        try {
            b.append("; jna linux=").append(Platform.isLinux()).append(" 64bit=").append(Platform.is64Bit())
                    .append(" pointer=").append(Native.POINTER_SIZE);
        } catch (Throwable t) {
            b.append("; jna failed: ").append(t);
        }
        return b.toString();
    }
}
