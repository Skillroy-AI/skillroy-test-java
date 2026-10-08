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

package ai.skillroy.testjava.agent.api.spi.web;

import java.util.Objects;

/**
 * A remote-debugging endpoint configured when a browser engine started, as {@link WebEngineAdapter#cdpEndpoints}
 * reports it for {@code web.cdp.status}: a read-only fact, which the agent never probes. One per engine.
 *
 * <p>Immutable; safe to share between threads. Equal when every field is equal.
 */
public final class CdpEndpoint {

    private final String host;
    private final int port;
    private final String engineLabel;

    /**
     * @param host the address the endpoint listens on (usually {@code 127.0.0.1}); neither null nor blank
     * @param port 1 to 65535; a technology's "port 0" means no endpoint and is not reported
     * @param engineLabel which engine: {@code "jcef"}, or a JxBrowser engine's user-data directory name; null when the
     *     adapter cannot name it
     * @throws NullPointerException if host is null
     * @throws IllegalArgumentException if host is blank or port is out of range
     */
    public CdpEndpoint(String host, int port, String engineLabel) {
        Objects.requireNonNull(host, "host must not be null");
        if (host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535: " + port);
        }
        this.host = host;
        this.port = port;
        this.engineLabel = engineLabel;
    }

    /** The address the endpoint listens on; never null or blank. */
    public String host() {
        return host;
    }

    /** 1 to 65535. */
    public int port() {
        return port;
    }

    /** {@code "jcef"}, or a JxBrowser engine's user-data directory name; null when the adapter cannot name it. */
    public String engineLabel() {
        return engineLabel;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CdpEndpoint)) {
            return false;
        }
        CdpEndpoint other = (CdpEndpoint) o;
        return host.equals(other.host) && port == other.port && Objects.equals(engineLabel, other.engineLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, Integer.valueOf(port), engineLabel);
    }

    @Override
    public String toString() {
        return "CdpEndpoint{host=" + host + ", port=" + port + ", engineLabel=" + engineLabel + "}";
    }
}
