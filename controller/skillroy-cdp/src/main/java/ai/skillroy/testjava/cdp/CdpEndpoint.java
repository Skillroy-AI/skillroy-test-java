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

package ai.skillroy.testjava.cdp;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * A Chrome DevTools Protocol endpoint: the host and port of a browser's remote-debugging HTTP server
 * (phase4-contracts.md section 5.1). The base only connects to a port the application configured at launch; it never
 * opens one (design section 3.8).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param host an IP literal or a host name; null or blank means {@value #LOCALHOST}, the default unless a suite says
 *     otherwise
 * @param port 1 to 65535
 */
public record CdpEndpoint(String host, int port) {

    /** The default host. */
    public static final String LOCALHOST = "127.0.0.1";

    /**
     * @throws IllegalArgumentException if the port is out of range or the host cannot be the host of an HTTP URI
     */
    public CdpEndpoint {
        host = host == null || host.isBlank() ? LOCALHOST : host.strip();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
        }
        URI uri;
        try {
            uri = new URI("http://" + authority(host, port));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a host: " + host, e);
        }
        if (uri.getHost() == null || uri.getPort() != port || uri.getRawUserInfo() != null
                || uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("not a host: " + host);
        }
    }

    /** An endpoint on {@value #LOCALHOST}. */
    public static CdpEndpoint local(int port) {
        return new CdpEndpoint(LOCALHOST, port);
    }

    /** {@code http://<host>:<port>}: the base of {@code /json/version} and {@code /json/list}. */
    public URI httpUri() {
        return URI.create("http://" + authority());
    }

    /** {@code <host>:<port>}, an IPv6 literal in brackets. */
    public String authority() {
        return authority(host, port);
    }

    /** The same as {@link #authority()}; used in messages and in the {@code web.path} record. */
    @Override
    public String toString() {
        return authority();
    }

    private static String authority(String host, int port) {
        boolean ipv6 = host.indexOf(':') >= 0 && !host.startsWith("[");
        return (ipv6 ? "[" + host + "]" : host) + ":" + port;
    }
}
