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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.spi.RedactionKeys;
import ai.skillroy.testjava.protocol.SecretKeys;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Keeps secrets out of what the module publishes on its own, outside any call: the text of {@code web.console}
 * (phase4-contracts.md section 4.3, amendment A34), which the core's redaction cannot reach (event payloads are never
 * redacted, and console output arrives on a browser's thread, outside the call that caused it). Two rules:
 * <ul>
 *   <li><b>By value.</b> The values of the parameters a {@code web.*} call named in {@code _meta.secretParams}
 *       ({@link Invocation#secretParams()}) are remembered from the call's start until {@link #KEEP_MS} after its
 *       budget ends, at most {@link #MAX_VALUES} of them (the oldest forgotten first). In a text, a remembered value
 *       of {@link #MIN_SUBSTRING} or more characters is replaced wherever it occurs, a shorter one wherever it stands
 *       as a token of its own (the core's rule, so that "rejected 1111" hides a four-digit code and "11112" does
 *       not); the whole text equal to a value becomes {@code <redacted>}.</li>
 *   <li><b>By key.</b> In {@code key=value}, {@code key: value} and {@code "key": "value"} pairs whose key is secret
 *       ({@link SecretKeys#looksSecret}, the {@code redact} option's pattern, and every registered
 *       {@link RedactionKeys}), the value is replaced by {@code <redacted>}.</li>
 * </ul>
 * Limits: a value is only known while it is remembered, so a page that logs a secret more than a minute after the
 * call that filled it is masked by key only (and by the controller's mask in {@code run.jsonl}); a secret split
 * across two console messages, or transformed by the page (upper-cased, encoded), is not recognised.
 *
 * <p>Thread-safe.
 */
final class Secrets {

    /** How long a value stays remembered after the budget of the call that named it. */
    static final long KEEP_MS = 60000;
    /** At most this many values are remembered. */
    static final int MAX_VALUES = 256;
    /** The shortest value replaced wherever it occurs inside a longer text (the core's rule). */
    static final int MIN_SUBSTRING = 8;

    /**
     * A key and its value: {@code key=value}, {@code key: value}, {@code "key": "value"}. Group 1 the key (optionally
     * quoted), 2 the separator, 3 the value (quoted, or up to white space, ',', ';', '&amp;' or a quote).
     */
    private static final Pattern PAIR = Pattern.compile("([\"']?[A-Za-z0-9_.\\-]+[\"']?)(\\s*[=:]\\s*)"
            + "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[^\\s,;&\"']+)");

    private final Map<String, Long> values = new LinkedHashMap<String, Long>();
    private final SpiRegistry spi;
    private final AgentLog log;
    private final Pattern extraKeys;
    private final AtomicBoolean providerFailureLogged = new AtomicBoolean();

    /**
     * @param redactOption the agent's {@code redact} option (a regular expression of further secret keys, matched
     *     case-insensitively), or null
     */
    Secrets(SpiRegistry spi, AgentLog log, String redactOption) {
        this.spi = spi;
        this.log = log;
        Pattern extra = null;
        if (redactOption != null && !redactOption.trim().isEmpty()) {
            try {
                extra = Pattern.compile(redactOption.trim(), Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                if (log != null) {
                    log.warn("web: the redact option is not a valid regular expression; console text is masked by "
                            + "the default keys only");
                }
            }
        }
        this.extraKeys = extra;
    }

    /** Remembers the values of the call's secret parameters (no effect when it named none). */
    void remember(Invocation inv) {
        List<String> names = inv.secretParams();
        if (names == null || names.isEmpty()) {
            return;
        }
        List<String> found = new ArrayList<String>();
        for (String name : names) {
            if (name != null && !"_meta".equals(name)) {
                collect(inv.args().get(name), found, 0);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(inv.remainingMs() + KEEP_MS);
        synchronized (values) {
            for (String v : found) {
                values.remove(v);
                values.put(v, Long.valueOf(until));
            }
            while (values.size() > MAX_VALUES) {
                Iterator<String> it = values.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    private static void collect(Object value, List<String> out, int depth) {
        if (depth > 200 || value == null || value instanceof Boolean) {
            return;
        }
        if (value instanceof Map) {
            for (Object v : ((Map<?, ?>) value).values()) {
                collect(v, out, depth + 1);
            }
        } else if (value instanceof List) {
            for (Object v : (List<?>) value) {
                collect(v, out, depth + 1);
            }
        } else {
            String text = value instanceof Number ? numberText((Number) value) : String.valueOf(value);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
    }

    private static String numberText(Number n) {
        if (n instanceof BigDecimal) {
            return ((BigDecimal) n).toPlainString();
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
        }
        return String.valueOf(n);
    }

    /** {@code text} with both rules applied; null stays null. */
    String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = byValue(text);
        return byKey(out);
    }

    private String byValue(String text) {
        List<String> live = live();
        if (live.isEmpty()) {
            return text;
        }
        if (live.contains(text)) {
            return SecretKeys.REDACTED;
        }
        String out = text;
        for (String secret : live) {
            out = secret.length() >= MIN_SUBSTRING ? out.replace(secret, SecretKeys.REDACTED)
                    : replaceTokens(out, secret);
        }
        return out;
    }

    /** The values still remembered, longest first (so that a value containing another is replaced whole). */
    private List<String> live() {
        List<String> out = new ArrayList<String>();
        long now = System.nanoTime();
        synchronized (values) {
            for (Iterator<Map.Entry<String, Long>> it = values.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> e = it.next();
                if (e.getValue().longValue() - now < 0) {
                    it.remove();
                } else {
                    out.add(e.getKey());
                }
            }
        }
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.length() != b.length() ? b.length() - a.length() : a.compareTo(b);
            }
        });
        return out;
    }

    /** Replaces the occurrences of {@code secret} that are not part of a longer run of letters or digits. */
    private static String replaceTokens(String text, String secret) {
        int from = text.indexOf(secret);
        if (from < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int copied = 0;
        while (from >= 0) {
            int end = from + secret.length();
            boolean before = from > 0 && Character.isLetterOrDigit(text.charAt(from - 1));
            boolean after = end < text.length() && Character.isLetterOrDigit(text.charAt(end));
            if (!before && !after) {
                sb.append(text, copied, from).append(SecretKeys.REDACTED);
                copied = end;
                from = text.indexOf(secret, end);
            } else {
                from = text.indexOf(secret, from + 1);
            }
        }
        return sb.append(text, copied, text.length()).toString();
    }

    private String byKey(String text) {
        if (text.indexOf('=') < 0 && text.indexOf(':') < 0) {
            return text;
        }
        Matcher m = PAIR.matcher(text);
        StringBuffer sb = null;
        while (m.find()) {
            String key = unquote(m.group(1));
            if (!isSecretKey(key)) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuffer(text.length());
            }
            String value = m.group(3);
            String hidden = value.startsWith("\"") ? "\"" + SecretKeys.REDACTED + "\""
                    : value.startsWith("'") ? "'" + SecretKeys.REDACTED + "'" : SecretKeys.REDACTED;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + hidden));
        }
        if (sb == null) {
            return text;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String unquote(String key) {
        String k = key;
        if (k.startsWith("\"") || k.startsWith("'")) {
            k = k.substring(1);
        }
        if (k.endsWith("\"") || k.endsWith("'")) {
            k = k.substring(0, k.length() - 1);
        }
        return k;
    }

    /** The key rule of the core's redactor: the default keys, the {@code redact} option, the registered keys. */
    boolean isSecretKey(String key) {
        if (SecretKeys.looksSecret(key) || (extraKeys != null && extraKeys.matcher(key).find())) {
            return true;
        }
        if (spi == null) {
            return false;
        }
        try {
            for (RedactionKeys keys : spi.all(RedactionKeys.class)) {
                Pattern p = keys.secretKeys();
                if (p != null && p.matcher(key).find()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            if (providerFailureLogged.compareAndSet(false, true) && log != null) {
                log.warn("web: a RedactionKeys provider failed; every key in console text counts as secret: " + e);
            }
            return true;
        }
    }
}
