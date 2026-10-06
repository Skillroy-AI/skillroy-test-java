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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One parsed HTTP/1.x request head of the control server (hardening item H1): request line and header fields, read
 * from the bytes before the blank line. The grammar is strict because the only legitimate clients are the
 * framework's own: lines end in CRLF; the request line is {@code METHOD SP target SP HTTP/1.0|HTTP/1.1} with a
 * method of 1 to 16 upper-case letters and an origin-form target of visible ASCII; a field is
 * {@code name ":" OWS value OWS} with a token name and no control characters in the value. Line folding, a bare CR
 * or LF, an empty field name and whitespace before the colon are refused (400); more than
 * {@link ControlServer#MAX_HEADER_FIELDS} fields is 431. The bytes are decoded as ISO-8859-1, never widened beyond
 * the 16 KB the connection buffer holds.
 *
 * <p>Immutable once parsed; created and read on one HTTP thread.
 */
final class HttpHead {

    /** The request head is not acceptable; {@link #status} is the reply (400 or 431). */
    static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        Malformed(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    private static final int MAX_METHOD = 16;

    final String method;
    /** The request target exactly as on the request line (what the request MAC covers). */
    final String target;
    /** The target up to {@code '?'}, not percent-decoded. */
    final String path;
    /** The target after {@code '?'}, or null. */
    final String rawQuery;
    /** True for HTTP/1.1, false for HTTP/1.0. */
    final boolean http11;
    private final Map<String, List<String>> fields;

    private HttpHead(String method, String target, boolean http11, Map<String, List<String>> fields) {
        this.method = method;
        this.target = target;
        int q = target.indexOf('?');
        this.path = q < 0 ? target : target.substring(0, q);
        this.rawQuery = q < 0 ? null : target.substring(q + 1);
        this.http11 = http11;
        this.fields = fields;
    }

    /** Every value of the field (case-insensitive name), in order; empty when absent. */
    List<String> all(String name) {
        List<String> values = fields.get(name.toLowerCase(Locale.ROOT));
        return values == null ? Collections.<String>emptyList() : values;
    }

    /** True if the field is present at all. */
    boolean has(String name) {
        return fields.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** The value when the field appears exactly once, else null. */
    String only(String name) {
        List<String> values = all(name);
        return values.size() == 1 ? values.get(0) : null;
    }

    /** True if the client asked to keep the connection open (HTTP/1.1 without {@code Connection: close}). */
    boolean keepAlive() {
        if (!http11) {
            return false;
        }
        for (String value : all("Connection")) {
            for (String token : value.split(",")) {
                if (token.trim().equalsIgnoreCase("close")) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Parses {@code length} bytes of {@code buf} from offset 0: the head without the terminating blank line (the
     * last CRLF of the last field is not included either).
     */
    static HttpHead parse(byte[] buf, int length) throws Malformed {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) (buf[i] & 0xff);
        }
        String text = new String(chars);
        List<String> lines = splitLines(text);
        if (lines.isEmpty()) {
            throw new Malformed(400, "empty request");
        }
        String requestLine = lines.get(0);
        int sp1 = requestLine.indexOf(' ');
        int sp2 = sp1 < 0 ? -1 : requestLine.indexOf(' ', sp1 + 1);
        if (sp1 <= 0 || sp2 < 0 || requestLine.indexOf(' ', sp2 + 1) >= 0) {
            throw new Malformed(400, "malformed request line");
        }
        String method = requestLine.substring(0, sp1);
        String target = requestLine.substring(sp1 + 1, sp2);
        String version = requestLine.substring(sp2 + 1);
        if (!isMethod(method)) {
            throw new Malformed(400, "malformed method");
        }
        if (!isOriginForm(target)) {
            throw new Malformed(400, "the request target must be a path");
        }
        boolean http11;
        if ("HTTP/1.1".equals(version)) {
            http11 = true;
        } else if ("HTTP/1.0".equals(version)) {
            http11 = false;
        } else {
            throw new Malformed(400, "unsupported HTTP version");
        }
        if (lines.size() - 1 > ControlServer.MAX_HEADER_FIELDS) {
            throw new Malformed(431, "more than " + ControlServer.MAX_HEADER_FIELDS + " header fields");
        }
        Map<String, List<String>> fields = new LinkedHashMap<String, List<String>>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new Malformed(400, "malformed header field");
            }
            String name = line.substring(0, colon);
            if (!isToken(name)) {
                throw new Malformed(400, "malformed header field name");
            }
            String value = trimOws(line.substring(colon + 1));
            for (int k = 0; k < value.length(); k++) {
                char c = value.charAt(k);
                if ((c < 0x20 && c != '\t') || c == 0x7f) {
                    throw new Malformed(400, "control character in a header field");
                }
            }
            String key = name.toLowerCase(Locale.ROOT);
            List<String> values = fields.get(key);
            if (values == null) {
                values = new ArrayList<String>(1);
                fields.put(key, values);
            }
            values.add(value);
        }
        return new HttpHead(method, target, http11, fields);
    }

    /** Splits on CRLF; a bare CR or LF anywhere is malformed. */
    private static List<String> splitLines(String text) throws Malformed {
        List<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 >= text.length() || text.charAt(i + 1) != '\n') {
                    throw new Malformed(400, "bare CR in the request head");
                }
                lines.add(text.substring(start, i));
                i++;
                start = i + 1;
            } else if (c == '\n') {
                throw new Malformed(400, "bare LF in the request head");
            }
        }
        lines.add(text.substring(start));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new Malformed(400, "folded or empty header line");
            }
        }
        return lines;
    }

    private static boolean isMethod(String method) {
        if (method.isEmpty() || method.length() > MAX_METHOD) {
            return false;
        }
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }

    private static boolean isOriginForm(String target) {
        if (target.isEmpty() || target.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** RFC 9110 token characters. */
    private static boolean isToken(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return !name.isEmpty();
    }

    private static String trimOws(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }
}
