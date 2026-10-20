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

package ai.skillroy.testjava.agent.api.instrument;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * The code-source name of a loaded class (phase8-contracts.md section 6.2, gap 113, D556): which jar a class came from,
 * by the jar's file name and any entries inside it, never a directory (D154's rule: a path may hold a user name). What
 * a hook target's {@code codeSource} qualifier is matched against, and what {@code hook.probe}, {@code hook.install}'s
 * {@code touched}, {@code hook.health.classes} and {@code skillroy hooks status} report.
 *
 * <p><b>What {@link #of} calls, and nothing else:</b> {@code Class.getProtectionDomain()} (a
 * {@link SecurityException} is caught: {@link #UNREADABLE}), {@code ProtectionDomain.getCodeSource()},
 * {@code CodeSource.getLocation()}, and on the {@link URL} the field getters {@code getProtocol()}, {@code getHost()}
 * and {@code getPath()} only. Never {@code toString}, {@code toExternalForm}, {@code toURI}, {@code openConnection},
 * {@code getContent}, {@code equals} or {@code hashCode}: the first three run the URL's stream handler, which for a
 * custom protocol (an application server's virtual file system, an OSGi bundle) is application code, and
 * {@code equals}/{@code hashCode} may resolve the host over the network; so a {@code URL} is never a map key or a set
 * member here. A null at any step is {@link #NONE} (bootstrap classes, a class defined without a protection domain,
 * generated proxies).
 *
 * <p><b>The name</b> ({@link #name}): (1) every leading scheme prefix ({@code [A-Za-z][A-Za-z0-9+.-]*:}) is stripped
 * from the path (a {@code jar:} URL's path begins with the inner URL); (2) the path is split at every {@code !/} and
 * {@code /!}, in order; (3) part 0, the outer location: percent-decoded ({@code %XX} as UTF-8, {@code +} kept, an
 * undecodable sequence kept as written), then trailing {@code /} and {@code \} stripped, then the text after the last
 * {@code /} or {@code \} (Phase 8 security fix, S178: a Windows native path in a {@code jar:file:C:\…!/} or a
 * {@code file} location made with the multi-argument constructor, and an encoded {@code %2F} or {@code %5C}, never
 * leave a directory in the name; a POSIX file name holding a backslash is named by its text after it); later
 * parts, entries inside an archive: leading and trailing {@code /} stripped, percent-decoded, kept whole; after
 * decoding, control characters (U+0000-U+001F, U+007F) become spaces; (4) empty parts are dropped, and with none left
 * the name is {@code <protocol>:<host>}; (5) the parts are joined with {@code !/} and cut at {@link #MAX_LENGTH}
 * characters.
 *
 * <p>Read on agent threads only (a probe or an install, on the worker), never on an application thread and never at
 * transform time. Java 8, JDK only; stateless.
 */
public final class CodeSources {

    /** No protection domain, code source or location. */
    public static final String NONE = "(none)";
    /** {@code getProtectionDomain} threw (a {@link SecurityException}). */
    public static final String UNREADABLE = "(unreadable)";
    /** The longest name; a longer one is cut. */
    public static final int MAX_LENGTH = 200;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private CodeSources() {
    }

    /** The code-source name of a loaded class; never throws, never null. Agent threads only. */
    public static String of(Class<?> type) {
        if (type == null) {
            return NONE;
        }
        ProtectionDomain domain;
        try {
            domain = type.getProtectionDomain();
        } catch (SecurityException e) {
            return UNREADABLE;
        }
        if (domain == null) {
            return NONE;
        }
        CodeSource source = domain.getCodeSource();
        if (source == null) {
            return NONE;
        }
        URL location = source.getLocation();
        if (location == null) {
            return NONE;
        }
        return name(location.getProtocol(), location.getHost(), location.getPath());
    }

    /** The naming rule on a location's parts, for tests and the guide's examples; never null. */
    public static String name(String protocol, String host, String path) {
        String rest = stripSchemes(path == null ? "" : path);
        List<String> parts = new ArrayList<String>();
        int start = 0;
        int index = 0;
        for (int i = 0; i + 1 < rest.length(); i++) {
            if ((rest.charAt(i) == '!' && rest.charAt(i + 1) == '/')
                    || (rest.charAt(i) == '/' && rest.charAt(i + 1) == '!')) {
                parts.add(part(rest.substring(start, i), index++));
                start = i + 2;
                i++;
            }
        }
        parts.add(part(rest.substring(start), index));
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("!/");
            }
            out.append(p);
        }
        String name = out.length() == 0 ? (protocol == null ? "" : protocol) + ":" + (host == null ? "" : host)
                : out.toString();
        name = controls(name);
        return name.length() > MAX_LENGTH ? name.substring(0, MAX_LENGTH) : name;
    }

    /**
     * Part 0: percent-decoded first, then trailing {@code /} and {@code \} stripped and the text after the last of
     * either kept (Phase 8 security fix, S178: a Windows native path keeps its backslashes in a location made from it,
     * and a {@code %2F} or {@code %5C} is a separator only once decoded, so cutting before decoding kept the
     * directories); later parts: leading and trailing {@code /} stripped, decoded, kept whole.
     */
    private static String part(String raw, int index) {
        if (index == 0) {
            String s = decode(raw);
            int end = s.length();
            while (end > 0 && separator(s.charAt(end - 1))) {
                end--;
            }
            s = s.substring(0, end);
            int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
            return controls(cut < 0 ? s : s.substring(cut + 1));
        }
        String s = raw;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return controls(decode(s));
    }

    private static boolean separator(char c) {
        return c == '/' || c == '\\';
    }

    /** Every leading {@code scheme:} prefix stripped. */
    private static String stripSchemes(String path) {
        String s = path;
        while (true) {
            int colon = schemeEnd(s);
            if (colon < 0) {
                return s;
            }
            s = s.substring(colon + 1);
        }
    }

    /** The index of the colon ending a leading {@code [A-Za-z][A-Za-z0-9+.-]*:}, or -1. */
    private static int schemeEnd(String s) {
        if (s.isEmpty() || !letter(s.charAt(0))) {
            return -1;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':') {
                return i;
            }
            if (!(letter(c) || (c >= '0' && c <= '9') || c == '+' || c == '.' || c == '-')) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean letter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * {@code %XX} sequences decoded as UTF-8 bytes; {@code +} kept; a malformed escape ({@code %} not followed by two
     * hexadecimal digits) kept as written, and so is a run of escapes that is not valid UTF-8.
     */
    static String decode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int runStart = -1;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                if (runStart < 0) {
                    runStart = i;
                }
                bytes.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 3;
                continue;
            }
            flush(bytes, out, s, runStart, i);
            runStart = -1;
            out.append(c);
            i++;
        }
        flush(bytes, out, s, runStart, s.length());
        return out.toString();
    }

    /** The run of escapes {@code s[from, to)} decoded strictly as UTF-8, or kept as written when it is not UTF-8. */
    private static void flush(ByteArrayOutputStream bytes, StringBuilder out, String s, int from, int to) {
        if (bytes.size() == 0) {
            return;
        }
        try {
            out.append(UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
        } catch (CharacterCodingException e) {
            out.append(s, from, to);
        }
        bytes.reset();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** Control characters (U+0000-U+001F, U+007F) become spaces. */
    private static String controls(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                if (out == null) {
                    out = new StringBuilder(s);
                }
                out.setCharAt(i, ' ');
            }
        }
        return out == null ? s : out.toString();
    }
}
