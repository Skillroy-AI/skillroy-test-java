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

import ai.skillroy.testjava.agent.api.SpiRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link SpiRegistry}: per SPI type, the registered implementations in registration order. The same
 * implementation may be registered twice; each handle removes its own registration.
 *
 * <p>Thread-safe; lookups return snapshots.
 */
final class CoreSpiRegistry implements SpiRegistry {

    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Registration>> byType =
            new ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Registration>>();

    @Override
    public <T> AutoCloseable register(Class<T> type, T implementation) {
        if (type == null || implementation == null) {
            throw new IllegalArgumentException("type and implementation must not be null");
        }
        if (!type.isInstance(implementation)) {
            throw new IllegalArgumentException(implementation.getClass().getName() + " is not a " + type.getName());
        }
        CopyOnWriteArrayList<Registration> list = byType.get(type);
        if (list == null) {
            CopyOnWriteArrayList<Registration> created = new CopyOnWriteArrayList<Registration>();
            list = byType.putIfAbsent(type, created);
            if (list == null) {
                list = created;
            }
        }
        final Registration registration = new Registration(implementation);
        final CopyOnWriteArrayList<Registration> target = list;
        target.add(registration);
        return new AutoCloseable() {
            @Override
            public void close() {
                target.remove(registration);
            }
        };
    }

    @Override
    public <T> List<T> all(Class<T> type) {
        List<T> out = new ArrayList<T>();
        CopyOnWriteArrayList<Registration> list = type == null ? null : byType.get(type);
        if (list != null) {
            for (Registration registration : list) {
                out.add(type.cast(registration.implementation));
            }
        }
        return out;
    }

    @Override
    public <T> T first(Class<T> type) {
        CopyOnWriteArrayList<Registration> list = type == null ? null : byType.get(type);
        if (list != null) {
            for (Registration registration : list) {
                return type.cast(registration.implementation);
            }
        }
        return null;
    }

    /** Identity-compared holder, so that removing one registration never removes an equal one. */
    private static final class Registration {
        final Object implementation;

        Registration(Object implementation) {
            this.implementation = implementation;
        }
    }
}
