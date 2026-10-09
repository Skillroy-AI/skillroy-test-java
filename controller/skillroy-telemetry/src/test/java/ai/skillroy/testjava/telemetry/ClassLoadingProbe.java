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


package ai.skillroy.testjava.telemetry;

import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObserverProvider;
import ai.skillroy.testjava.scenario.observe.RunObservers;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.TelemetrySettings;

import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The child JVM of {@link ClassLoadingProbeTest}: what a host does with telemetry off (the settings read, "none" asked
 * for), then the providers found and asked their exporter, as {@code RunObservers.open} does before it creates one;
 * with the argument {@code create <dir>}, a file observer is made and closed as well (the positive control).
 */
public final class ClassLoadingProbe {

    private ClassLoadingProbe() {
    }

    /** Prints {@code provider <exporter>} per provider and {@code probe-done}. */
    public static void main(String[] args) throws Exception {
        TelemetrySettings off = TelemetrySettings.load(null, Map.of(), "probe", key -> null, key -> null);
        RunObserver none = RunObservers.open(off, null);
        System.out.println("none " + (none == RunObserver.NONE));
        for (RunObserverProvider provider : ServiceLoader.load(RunObserverProvider.class)) {
            System.out.println("provider " + provider.exporter());
        }
        if (args.length == 2 && args[0].equals("create")) {
            TelemetrySettings file = TelemetrySettings.load(null, Map.of(TelemetrySettings.PROP_EXPORTER, "file",
                    TelemetrySettings.PROP_OUT, Path.of(args[1]).toString()), "probe", key -> null, key -> null);
            RunObserver observer = RunObservers.open(file, null);
            observer.runStarted(new RunStart("probe", null, null, null, "probe", null, null));
            observer.close();
        }
        System.out.println("probe-done");
    }
}
