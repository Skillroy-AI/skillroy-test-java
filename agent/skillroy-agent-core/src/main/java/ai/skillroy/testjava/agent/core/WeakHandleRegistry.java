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

import ai.skillroy.testjava.agent.api.HandleRegistry;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@link HandleRegistry}: handles {@code "h<n>"} for live host objects, by identity and weakly held, so that the
 * controller can name a component across calls without the agent keeping it alive. Entries of collected objects are
 * expunged lazily.
 *
 * <p>Thread-safe (all methods synchronise on the registry; each is O(1) apart from expunging).
 */
final class WeakHandleRegistry implements HandleRegistry {

    private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
    private final Map<IdentityRef, String> byObject = new HashMap<IdentityRef, String>();
    private final Map<String, IdentityRef> byHandle = new HashMap<String, IdentityRef>();
    private long counter;

    @Override
    public synchronized String handleFor(Object object, String kind) {
        if (object == null) {
            throw new IllegalArgumentException("cannot make a handle for null");
        }
        expunge();
        String existing = byObject.get(new IdentityRef(object, null, null, null));
        if (existing != null) {
            return existing;
        }
        String handle = "h" + (++counter);
        IdentityRef ref = new IdentityRef(object, queue, handle, kind);
        byObject.put(ref, handle);
        byHandle.put(handle, ref);
        return handle;
    }

    @Override
    public synchronized Object resolve(String handle) {
        expunge();
        IdentityRef ref = handle == null ? null : byHandle.get(handle);
        Object object = ref == null ? null : ref.get();
        if (object == null) {
            throw new RpcException(ErrorCodes.STALE_HANDLE,
                    "handle " + handle + (ref == null ? " is unknown" : " refers to a collected object"),
                    detail(handle, ref == null ? null : ref.kind));
        }
        return object;
    }

    @Override
    public <T> T resolve(String handle, Class<T> type) {
        Object object = resolve(handle);
        if (type != null && !type.isInstance(object)) {
            Map<String, Object> data = detail(handle, null);
            data.put("expected", type.getName());
            data.put("actual", object.getClass().getName());
            throw new RpcException(ErrorCodes.INVALID_PARAMS,
                    "handle " + handle + " is a " + object.getClass().getSimpleName() + ", not a "
                            + type.getSimpleName(), data);
        }
        return type == null ? null : type.cast(object);
    }

    @Override
    public synchronized void release(String handle) {
        IdentityRef ref = handle == null ? null : byHandle.remove(handle);
        if (ref != null) {
            byObject.remove(ref);
        }
    }

    /** Live handle count (expunges first); for tests and diagnostics. */
    synchronized int size() {
        expunge();
        return byHandle.size();
    }

    private void expunge() {
        Reference<?> ref;
        while ((ref = queue.poll()) != null) {
            IdentityRef dead = (IdentityRef) ref;
            byObject.remove(dead);
            if (dead.handle != null && byHandle.get(dead.handle) == dead) {
                byHandle.remove(dead.handle);
            }
        }
    }

    private static Map<String, Object> detail(String handle, String kind) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("handle", handle);
        if (kind != null) {
            data.put("kind", kind);
        }
        return data;
    }

    /** A weak reference compared by the identity of its referent; a cleared reference equals only itself. */
    private static final class IdentityRef extends WeakReference<Object> {
        private final int hash;
        final String handle;
        final String kind;

        IdentityRef(Object referent, ReferenceQueue<Object> queue, String handle, String kind) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
            this.handle = handle;
            this.kind = kind;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof IdentityRef)) {
                return false;
            }
            Object mine = get();
            return mine != null && mine == ((IdentityRef) o).get();
        }
    }
}
