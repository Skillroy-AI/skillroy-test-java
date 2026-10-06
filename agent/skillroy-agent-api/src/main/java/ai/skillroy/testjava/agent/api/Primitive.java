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

package ai.skillroy.testjava.agent.api;

/**
 * One callable operation of the control channel (a JSON-RPC method). Primitives are the only way the controller acts
 * on or observes the target, so each declares its contract in a {@link PrimitiveSpec}.
 *
 * <p>{@link #invoke} runs on the thread chosen by {@link PrimitiveSpec#thread()}; several invocations of the same
 * primitive may run concurrently, so implementations must be thread-safe.
 */
public interface Primitive {

    /** The primitive's name, parameters, thread policy and flags. Must return the same spec on every call. */
    PrimitiveSpec spec();

    /**
     * @return a JSON-able value (Map/List/String/Number/Boolean/null). Throw RpcException for protocol errors;
     *     any other exception becomes INTERNAL_ERROR with the exception class and message in data.
     */
    Object invoke(Invocation invocation) throws Exception;
}
