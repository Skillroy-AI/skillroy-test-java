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

package ai.skillroy.testjava.agent.web.reflect;

/**
 * A reflective call through a {@link ResolvedApi} failed: the vendor's method threw, or the call could not be made.
 * The message names the role and the vendor exception's class and message; the vendor exception is kept as the
 * cause for the adapter's own diagnostics only. An adapter translates this into a JDK or SPI exception before
 * anything leaves it (SPI rule 6: no vendor type crosses the adapter's boundary).
 */
public final class ReflectiveException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String role;
    private final String vendorClass;

    ReflectiveException(String role, Throwable cause) {
        super(describe(role, cause), cause);
        this.role = role;
        this.vendorClass = cause == null ? null : cause.getClass().getName();
    }

    private static String describe(String role, Throwable cause) {
        if (cause == null) {
            return role + " failed";
        }
        String message = cause.getMessage();
        return role + " failed: " + cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** The role whose call failed. */
    public String role() {
        return role;
    }

    /** The class name of what the vendor threw, or null. */
    public String vendorClass() {
        return vendorClass;
    }

    /** The vendor exception's own message, or null. */
    public String vendorMessage() {
        return getCause() == null ? null : getCause().getMessage();
    }
}
