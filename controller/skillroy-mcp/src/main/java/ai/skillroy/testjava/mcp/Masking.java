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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.sql.Rows;

import java.util.function.Supplier;

/**
 * Everything the server answers or logs passes here (phase5-contracts.md 9.2, 9.3): an answer reaches the client's
 * model, so the session's {@link SecretMask} (the credentials with the environment's {@code masking.minLengthInText},
 * and the secret fields a resolution joined) and then the {@link PromptMask} (secret keys, {@code key=value} runs, the
 * allow-listed columns of a row, the bounds) apply, as before a prompt. A tool's text is then cut at
 * {@value #MAX_RESULT} characters with {@code [truncated: N more]}.
 *
 * <p>Static descriptions (the primitives' schemas, the scenario schema) take the secret values only: the key rule would
 * replace the schema of a parameter named {@code password}.
 *
 * <p>Immutable; safe to share between threads.
 */
final class Masking {

    /** The longest tool text (control-channel F19). */
    static final int MAX_RESULT = 25_000;

    private final SecretMask secrets;
    private final PromptMask prompt;
    private final Supplier<SecretMask> joined;

    /**
     * @param secrets the credentials' mask
     * @param prompt the prompt mask over {@code secrets}, the pack's allow-lists and row masks
     * @param joined the secret fields the session has joined since (may answer null)
     */
    Masking(SecretMask secrets, PromptMask prompt, Supplier<SecretMask> joined) {
        this.secrets = secrets == null ? SecretMask.NONE : secrets;
        this.prompt = prompt;
        this.joined = joined;
    }

    /** A value read from the target or the run: secret values, then secret keys; a new value. */
    Object value(Object jsonLike) {
        return prompt.jsonLike(plain(jsonLike));
    }

    /** A static description: the secret values only. */
    Object plain(Object jsonLike) {
        Object v = secrets.apply(JsonForm.of(jsonLike));
        SecretMask more = joinedMask();
        return more == null ? v : more.apply(v);
    }

    /** A text: secret values, then the {@code key=value} rule (and the prompt mask's 100000-character bound). */
    String text(String text) {
        if (text == null) {
            return null;
        }
        String t = secrets.apply(text);
        SecretMask more = joinedMask();
        return prompt.text(more == null ? t : more.apply(t));
    }

    /** A row set of a source: its allow-listed columns only, a personal one's values {@code <personal>}. */
    Rows rows(String source, Rows rows) {
        return prompt.rows(source, rows);
    }

    /** Pretty JSON of a value that is already masked, cut at {@value #MAX_RESULT} characters. */
    static String json(Object masked) {
        String text;
        try {
            text = Json.writePretty(masked);
        } catch (JsonException e) {
            text = String.valueOf(masked);
        }
        return cut(text.endsWith("\n") ? text.substring(0, text.length() - 1) : text);
    }

    /** The text cut at {@value #MAX_RESULT} characters with {@code [truncated: N more]}; never in a surrogate pair. */
    static String cut(String text) {
        if (text == null || text.length() <= MAX_RESULT) {
            return text;
        }
        int end = MAX_RESULT;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "\n[truncated: " + (text.length() - end) + " more]";
    }

    private SecretMask joinedMask() {
        try {
            return joined == null ? null : joined.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
