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


package ai.skillroy.testjava.agent.hooks;

import java.lang.ref.WeakReference;

/**
 * One hooked method of one class (a hook with several descriptors, or a class defined by several loaders, has several
 * sites). Its {@link #key} is the string constant the advice passes to {@code Spy}: the hook id, then the class, the
 * method and its descriptor, so that the handler finds the hook and what the event names without any other lookup.
 *
 * <p>Immutable apart from the armed stub value; safe to share between threads.
 */
final class Site {

    /** Separates the parts of a {@link #key}; never in a hook id, a binary name or a descriptor. */
    static final char SEPARATOR = ' ';

    final String key;
    final HookEntry hook;
    final String className;
    final String method;
    final String descriptor;
    /** The parameter count of the descriptor. */
    final int parameters;
    /** True for a method returning void. */
    final boolean isVoid;
    /** The descriptor's return type ({@code I}, {@code Ljava/math/BigDecimal;}, ...). */
    final String returnDescriptor;
    /** The return type's class, held weakly (it may be an application class; never pins its loader). */
    final WeakReference<Class<?>> returnType;
    /** The value a STUB hook returns while armed, already of the return type; null when not armed. */
    volatile HandlerCore.Armed armed;

    Site(HookEntry hook, String className, String method, String descriptor, Class<?> returnType) {
        this.hook = hook;
        this.className = className;
        this.method = method;
        this.descriptor = descriptor;
        this.key = key(hook.id, className, method, descriptor);
        this.parameters = Descriptors.parameterCount(descriptor);
        this.returnDescriptor = descriptor.substring(descriptor.indexOf(')') + 1);
        this.isVoid = "V".equals(returnDescriptor);
        this.returnType = new WeakReference<Class<?>>(returnType);
    }

    static String key(String id, String className, String method, String descriptor) {
        return id + SEPARATOR + className + SEPARATOR + method + descriptor;
    }

    /** The return type's readable name ({@code int}, {@code java.math.BigDecimal}, {@code void}). */
    String returnTypeName() {
        return Descriptors.typeName(returnDescriptor);
    }
}
