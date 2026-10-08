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


package ai.skillroy.testjava.samples.webhost;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The exports JOGL needs for off-screen rendering on Java 16 or newer (jcefmaven's documentation; spike BS8 ran its
 * off-screen configurations on Java 25 with exactly these): {@link WebHostApp#OFF_SCREEN_JVM_ARGS}. A Java 8 JVM has
 * no modules and refuses the flags; on Java 9 to 15 they are accepted and not needed. Here only for a diagnostic:
 * the host warns when it renders off screen on Java 16+ without them.
 *
 * <p>Uses the module API reflectively (this module is compiled for Java 8) and loads no AWT class.
 */
final class JvmExports {

    /** {@code <module>/<package>} pairs the flags export to the unnamed module. */
    static final List<String> OFF_SCREEN_EXPORTS = Collections.unmodifiableList(Arrays.asList(
            "java.base/java.lang", "java.desktop/sun.awt", "java.desktop/sun.java2d"));

    private JvmExports() {
    }

    /** @return the Java feature version of this JVM: 8 for "1.8", 25 for "25", ... (0 when unreadable) */
    static int javaFeatureVersion() {
        String spec = System.getProperty("java.specification.version", "");
        try {
            return spec.startsWith("1.") ? Integer.parseInt(spec.substring(2)) : Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * @return the pairs of {@link #OFF_SCREEN_EXPORTS} this JVM does not export to the unnamed module (where the host's
     *     and JOGL's classes live); empty on Java 8, and empty when the module API cannot be read
     */
    static List<String> missingOffScreenExports() {
        Method getModule;
        try {
            getModule = Class.class.getMethod("getModule");
        } catch (NoSuchMethodException java8) {
            return Collections.emptyList();
        }
        try {
            Object unnamed = getModule.invoke(JvmExports.class);
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Class<?> layerClass = Class.forName("java.lang.ModuleLayer");
            Object boot = layerClass.getMethod("boot").invoke(null);
            Method findModule = layerClass.getMethod("findModule", String.class);
            Method isExported = moduleClass.getMethod("isExported", String.class, moduleClass);
            List<String> missing = new ArrayList<String>();
            for (String pair : OFF_SCREEN_EXPORTS) {
                int slash = pair.indexOf('/');
                Optional<?> module = (Optional<?>) findModule.invoke(boot, pair.substring(0, slash));
                if (!module.isPresent()
                        || !Boolean.TRUE.equals(isExported.invoke(module.get(), pair.substring(slash + 1), unnamed))) {
                    missing.add(pair);
                }
            }
            return Collections.unmodifiableList(missing);
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }
}
