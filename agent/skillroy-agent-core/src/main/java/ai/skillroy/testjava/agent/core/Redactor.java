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

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.spi.RedactionKeys;
import ai.skillroy.testjava.protocol.SecretKeys;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Hides secret values before they leave the JVM (contract amendment A5). Host applications pass passwords, tokens
 * and licence keys as system properties and JVM arguments; {@code jvm.info}, {@code jvm.properties} and
 * {@code agent.info} would otherwise hand them to anyone holding the agent's token, and from there into run logs.
 *
 * <p>A key is secret when it contains (case-insensitive) a match of {@link #DEFAULT_KEYS}, the rule shared with the
 * controller ({@link SecretKeys}, contract amendment A17), of the regex given in the {@code redact} option (case
 * insensitive), or of the pattern of any {@link RedactionKeys} registered by a module (contract amendment A21; case
 * sensitivity is that pattern's own). The registered patterns are read at call time, so a registration takes effect
 * at once and an uninstalled module's keys stop counting; a provider that throws makes every key secret. The value of
 * a secret key is replaced by {@link #REDACTED}:
 * <ul>
 *   <li>a whole JVM argument {@code -Dkey=value}, {@code -key=value} or {@code --key=value}: everything after the
 *       first {@code '='};</li>
 *   <li>a {@code key=value} pair inside any other text (an agent argument string, a JDBC URL, a non-secret property
 *       value): the value up to the next {@code ';'}, {@code ','}, {@code '&'} or whitespace;</li>
 *   <li>a property or option whose own name is secret: the whole value;</li>
 *   <li>in any value that is not hidden as a whole, the password of URL userinfo (finding S21):
 *       {@code scheme://user:password@host} and the Oracle form {@code jdbc:oracle:<driver>:user/password@}, the
 *       text between the {@code ':'} (or {@code '/'}) and the last {@code '@'} of the authority. A userinfo without
 *       a password ({@code ssh://git@host}) is left alone. Not recognised: a password containing an unencoded
 *       {@code '/'}, {@code '?'}, {@code '#'} or white space, and a token carried as a bare user name
 *       ({@code https://<token>@host}); the {@code redact} option naming the property is the remedy.</li>
 * </ul>
 * Secrets written without {@code '='} (for example {@code --password hunter2} as two program arguments) cannot be
 * recognised; {@code mainCommand} never carries program arguments for that reason.
 *
 * <p>Immutable apart from the registry it reads; safe from any thread.
 */
final class Redactor {

    /** What a secret value is replaced by. Never null, so a scenario can still require a property's presence. */
    static final String REDACTED = SecretKeys.REDACTED;
    /** The keys that are always secret (contract amendments A5 and A17). */
    static final String DEFAULT_KEYS = SecretKeys.DEFAULT_KEYS;
    /** A whole argument {@code -Dkey=value}, {@code -key=value} or {@code --key=value}. */
    private static final Pattern WHOLE_ARGUMENT = Pattern.compile("^(-D|--?)([A-Za-z0-9_.\\-]+)=(.*)$",
            Pattern.DOTALL);
    /**
     * {@code scheme://user:password@}: group 1 ends at the {@code ':'} before the password. Greedy, so the last
     * {@code '@'} before {@code '/'}, {@code '?'}, {@code '#'} or white space ends it. Linear: negated classes only.
     */
    private static final Pattern USERINFO = Pattern.compile("(://[^/?#\\s:@]*:)[^/?#\\s]*@");
    /** {@code jdbc:oracle:thin:user/password@...}. */
    private static final Pattern ORACLE_USERINFO =
            Pattern.compile("(jdbc:oracle:[a-z]+:[^/@\\s]*/)[^@\\s]*@", Pattern.CASE_INSENSITIVE);
    private static final String USERINFO_REPLACEMENT = "$1" + Matcher.quoteReplacement(SecretKeys.REDACTED) + "@";

    private final Pattern extra;
    private final SpiRegistry spi;
    private final AtomicBoolean providerFailureLogged = new AtomicBoolean();
    private final AgentLog log;

    private Redactor(Pattern extra, SpiRegistry spi, AgentLog log) {
        this.extra = extra;
        this.spi = spi;
        this.log = log;
    }

    /**
     * The redactor for the given {@code redact} option value (null or empty: the default keys only).
     *
     * @throws IllegalArgumentException if the option is not a valid regular expression
     */
    static Redactor withExtraKeys(String regex) {
        return withExtraKeys(regex, null, null);
    }

    /**
     * The redactor for the given {@code redact} option value that also consults every {@link RedactionKeys}
     * registered in {@code spi} (null: none) at call time; a failing provider is logged once to {@code log}.
     *
     * @throws IllegalArgumentException if the option is not a valid regular expression
     */
    static Redactor withExtraKeys(String regex, SpiRegistry spi, AgentLog log) {
        if (regex == null || regex.trim().isEmpty()) {
            return new Redactor(null, spi, log);
        }
        try {
            return new Redactor(Pattern.compile(regex.trim(), Pattern.CASE_INSENSITIVE), spi, log);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("option redact is not a valid regular expression: "
                    + e.getDescription());
        }
    }

    /** True if the key names a secret. */
    boolean isSecret(String key) {
        if (key == null) {
            return false;
        }
        return SecretKeys.looksSecret(key) || (extra != null && extra.matcher(key).find()) || registeredSecret(key);
    }

    /** True if the pattern of a registered {@link RedactionKeys} finds a match in the key, or a provider fails. */
    private boolean registeredSecret(String key) {
        if (spi == null) {
            return false;
        }
        for (RedactionKeys keys : spi.all(RedactionKeys.class)) {
            try {
                Pattern pattern = keys.secretKeys();
                if (pattern != null && pattern.matcher(key).find()) {
                    return true;
                }
            } catch (Throwable t) {
                if (log != null && providerFailureLogged.compareAndSet(false, true)) {
                    log.warn("RedactionKeys " + keys.getClass().getName() + " failed; every key counts as secret", t);
                }
                return true;
            }
        }
        return false;
    }

    /** The value of a named property or option: {@link #REDACTED} for a secret name, else its secret pairs hidden. */
    String value(String key, String value) {
        if (value == null) {
            return null;
        }
        return isSecret(key) ? REDACTED : text(value);
    }

    /** One JVM argument (or the main command) with its secret values hidden. */
    String argument(String argument) {
        if (argument == null) {
            return null;
        }
        Matcher whole = WHOLE_ARGUMENT.matcher(argument);
        if (whole.matches()) {
            String key = whole.group(2);
            String prefix = whole.group(1) + key + "=";
            return isSecret(key) ? prefix + REDACTED : prefix + text(whole.group(3));
        }
        return text(argument);
    }

    /** {@link #pairs} plus the password of URL userinfo (finding S21). */
    String text(String text) {
        String out = pairs(text);
        if (out == null || out.indexOf('@') < 0) {
            return out;
        }
        out = USERINFO.matcher(out).replaceAll(USERINFO_REPLACEMENT);
        return ORACLE_USERINFO.matcher(out).replaceAll(USERINFO_REPLACEMENT);
    }

    /** Hides the value of every {@code key=value} pair inside the text whose key is secret. */
    String pairs(String text) {
        if (text == null || text.indexOf('=') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int i = text.indexOf('=');
        while (i >= 0) {
            int keyStart = i;
            while (keyStart > 0 && isKeyChar(text.charAt(keyStart - 1))) {
                keyStart--;
            }
            if (keyStart < i && isSecret(text.substring(keyStart, i))) {
                int end = i + 1;
                while (end < text.length() && !isSeparator(text.charAt(end))) {
                    end++;
                }
                out.append(text, copied, i + 1).append(REDACTED);
                copied = end;
                i = text.indexOf('=', end);
            } else {
                i = text.indexOf('=', i + 1);
            }
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    private static boolean isKeyChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.'
                || c == '-';
    }

    private static boolean isSeparator(char c) {
        return c == ';' || c == ',' || c == '&' || Character.isWhitespace(c);
    }
}
