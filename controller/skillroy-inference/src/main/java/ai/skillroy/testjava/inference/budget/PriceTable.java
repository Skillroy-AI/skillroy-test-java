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

package ai.skillroy.testjava.inference.budget;

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.data.yaml.YamlException;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a model call costs (phase5-contracts.md 7.2): US dollars per million tokens by model id, with the cache-write
 * factors by time to live and the batch factor. The provider reports tokens, not money; a gateway computes
 * {@code Usage.costUsd} with {@link #cost(Usage)}, from the usage's own cache-write split (BS13 F-4), at the batch
 * rate for a batch call (stacking with cache reads). A dated snapshot id the table does not hold (a known id followed
 * by {@code -YYYYMMDD}) costs as the known id; an unknown model costs at the price of the Opus class's default model
 * ({@value #UNKNOWN_MODEL_PRICE}) and {@link #note} says so.
 *
 * <p>The built-in table ({@link #builtIn()}) holds this version's published rates, to be replaced by a file when they
 * move ({@code inference.yaml} {@code prices}, read by {@link #load}):
 *
 * <pre>
 * version: 1
 * models:
 *   claude-sonnet-5-5: {inputPerMTok: "2.00", outputPerMTok: "10.00", cacheReadPerMTok: "0.20",
 *                       cacheWrite5mFactor: "1.25", cacheWrite1hFactor: "2", batchFactor: "0.5"}
 * </pre>
 *
 * A model the file names replaces or extends the built-in row (keys it leaves out keep the row's values; a new model
 * needs {@code inputPerMTok} and {@code outputPerMTok}, a cache read defaults to a tenth of the input price and the
 * factors to 1.25, 2 and 0.5).
 *
 * <p>Immutable; safe to share between threads.
 */
public final class PriceTable {

    /** The model whose price an unknown model is charged at: the Opus class's default. */
    public static final String UNKNOWN_MODEL_PRICE = "claude-opus-5-5";

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final int SCALE = 6;
    private static final int MAX_FILE_BYTES = 1 << 20;
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,199}");
    /** A dated snapshot id: an id, {@code -} and the eight digits of a date. */
    private static final Pattern DATED_ID = Pattern.compile("(.+)-[0-9]{8}");
    private static final List<String> TOP_KEYS = List.of("version", "models");
    private static final List<String> PRICE_KEYS = List.of("inputPerMTok", "outputPerMTok", "cacheReadPerMTok",
            "cacheWrite5mFactor", "cacheWrite1hFactor", "batchFactor");

    /**
     * One model's prices.
     *
     * @param model the model id
     * @param inputPerMTok US dollars per million input tokens
     * @param outputPerMTok per million output tokens
     * @param cacheReadPerMTok per million tokens read from the cache
     * @param cacheWrite5mFactor the input price's factor for a five-minute cache write
     * @param cacheWrite1hFactor the input price's factor for a one-hour cache write
     * @param batchFactor the factor of a batch call's whole cost
     */
    public record Price(String model, BigDecimal inputPerMTok, BigDecimal outputPerMTok, BigDecimal cacheReadPerMTok,
                        BigDecimal cacheWrite5mFactor, BigDecimal cacheWrite1hFactor, BigDecimal batchFactor) {

        /** @throws IllegalArgumentException for a missing or negative amount */
        public Price {
            Objects.requireNonNull(model, "model");
            for (BigDecimal amount : List.of(inputPerMTok, outputPerMTok, cacheReadPerMTok, cacheWrite5mFactor,
                    cacheWrite1hFactor, batchFactor)) {
                if (amount == null || amount.signum() < 0) {
                    throw new IllegalArgumentException("price of " + model + ": every amount is given and not"
                            + " negative");
                }
            }
        }

        static Price of(String model, String in, String out, String cacheRead) {
            return new Price(model, new BigDecimal(in), new BigDecimal(out), new BigDecimal(cacheRead),
                    new BigDecimal("1.25"), new BigDecimal("2"), new BigDecimal("0.5"));
        }
    }

    private static final PriceTable BUILT_IN;

    static {
        Map<String, Price> table = new LinkedHashMap<>();
        // phase5-contracts.md 7.2 (ai-inference F10), USD per million tokens.
        table.put("claude-opus-5-5", Price.of("claude-opus-5-5", "4.00", "20.00", "0.20"));
        table.put("claude-sonnet-5-5", Price.of("claude-sonnet-5-5", "2.00", "10.00", "0.20"));
        table.put("claude-haiku-4-5", Price.of("claude-haiku-4-5", "1.00", "5.00", "0.10"));
        // The fallback targets: Sonnet 5.5's documented one (BS13 F-7) and the two the models endpoint names for
        // Opus 5.5 (BS13 live, 2026-10-08).
        table.put("claude-sonnet-5", Price.of("claude-sonnet-5", "2.00", "10.00", "0.20"));
        table.put("claude-opus-4-8", Price.of("claude-opus-4-8", "5.00", "25.00", "0.50"));
        table.put("claude-opus-5", Price.of("claude-opus-5", "5.00", "25.00", "0.50"));
        BUILT_IN = new PriceTable(table);
    }

    private final Map<String, Price> prices;

    private PriceTable(Map<String, Price> prices) {
        this.prices = Collections.unmodifiableMap(new LinkedHashMap<>(prices));
    }

    /**
     * This version's table (phase5-contracts.md 7.2): {@code claude-opus-5-5}, {@code claude-sonnet-5-5} and
     * {@code claude-haiku-4-5}, and the fallback targets {@code claude-sonnet-5} (Sonnet 5.5's),
     * {@code claude-opus-4-8} and {@code claude-opus-5} (Opus 5.5's), each with the factors 1.25 and 2 of the cache
     * writes and 0.5 of a batch.
     */
    public static PriceTable builtIn() {
        return BUILT_IN;
    }

    /**
     * The table of these settings: the built-in one, or the built-in one with the file {@code prices} names.
     *
     * @throws SettingsException as {@link #load}
     */
    public static PriceTable of(InferenceSettings settings) throws SettingsException {
        return settings == null || settings.prices() == null ? BUILT_IN : load(settings.prices());
    }

    /**
     * The built-in table with the overrides and additions of a price file (strict YAML; the format of the class
     * description). Messages name the file and the line, never a value.
     *
     * @throws SettingsException when the file is missing, unreadable, malformed or against the grammar
     */
    public static PriceTable load(Path yaml) throws SettingsException {
        Objects.requireNonNull(yaml, "yaml");
        String name = yaml.toString();
        byte[] bytes;
        try (InputStream in = Files.newInputStream(yaml)) {
            bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            throw new SettingsException("price table " + name + " cannot be read (" + e.getClass().getName() + ")");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new SettingsException(name + ": a price table is at most 1 MiB");
        }
        StrictYaml.Doc doc;
        try {
            doc = StrictYaml.read(bytes);
        } catch (YamlException e) {
            throw new SettingsException(name + (e.line() > 0 ? ":" + e.line() : "") + ": " + (e.quotesInput()
                    ? "not valid YAML (check the syntax near this line)" : e.reason()));
        }
        if (!(doc.root() instanceof Map<?, ?> root)) {
            throw new SettingsException(name + ": a price table is a YAML mapping");
        }
        for (Object key : root.keySet()) {
            if (!TOP_KEYS.contains(String.valueOf(key))) {
                throw new SettingsException(where(name, doc, root, String.valueOf(key)) + "a top-level key is not"
                        + " known; allowed keys: " + String.join(", ", TOP_KEYS));
            }
        }
        if (!(root.get("version") instanceof Long v) || v != 1L) {
            throw new SettingsException(where(name, doc, root, "version") + "'version' must be 1");
        }
        Map<String, Price> table = new LinkedHashMap<>(BUILT_IN.prices);
        Object models = root.get("models");
        if (models != null && !(models instanceof Map<?, ?>)) {
            throw new SettingsException(where(name, doc, root, "models") + "'models' must be a mapping");
        }
        if (models instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String model = String.valueOf(e.getKey());
                if (!MODEL_ID.matcher(model).matches()) {
                    throw new SettingsException(where(name, doc, map, model) + "a key of 'models' must be a model id");
                }
                if (!(e.getValue() instanceof Map<?, ?> fields)) {
                    throw new SettingsException(where(name, doc, map, model) + "a model's prices are a mapping");
                }
                table.put(model, price(name, doc, model, fields, table.get(model)));
            }
        }
        return new PriceTable(table);
    }

    private static Price price(String file, StrictYaml.Doc doc, String model, Map<?, ?> fields, Price base)
            throws SettingsException {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : fields.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!PRICE_KEYS.contains(key)) {
                throw new SettingsException(where(file, doc, fields, key) + "a price key is not known; allowed keys: "
                        + String.join(", ", PRICE_KEYS));
            }
            BigDecimal amount = amount(e.getValue());
            if (amount == null) {
                throw new SettingsException(where(file, doc, fields, key) + key + " must be an amount of at least 0,"
                        + " written as a plain decimal such as \"2.00\"");
            }
            values.put(key, amount);
        }
        if (base == null && (!values.containsKey("inputPerMTok") || !values.containsKey("outputPerMTok"))) {
            throw new SettingsException(where(file, doc, fields, null) + "a model the built-in table does not know"
                    + " needs inputPerMTok and outputPerMTok");
        }
        BigDecimal input = values.getOrDefault("inputPerMTok", base == null ? null : base.inputPerMTok());
        return new Price(model, input,
                values.getOrDefault("outputPerMTok", base == null ? null : base.outputPerMTok()),
                values.getOrDefault("cacheReadPerMTok", base == null ? input.divide(BigDecimal.TEN)
                        : base.cacheReadPerMTok()),
                values.getOrDefault("cacheWrite5mFactor", base == null ? new BigDecimal("1.25")
                        : base.cacheWrite5mFactor()),
                values.getOrDefault("cacheWrite1hFactor", base == null ? new BigDecimal("2")
                        : base.cacheWrite1hFactor()),
                values.getOrDefault("batchFactor", base == null ? new BigDecimal("0.5") : base.batchFactor()));
    }

    private static BigDecimal amount(Object value) {
        try {
            BigDecimal d = value instanceof String s ? new BigDecimal(s.strip())
                    : value instanceof Long l ? BigDecimal.valueOf(l)
                    : value instanceof Double x ? BigDecimal.valueOf(x) : null;
            return d == null || d.signum() < 0 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String where(String file, StrictYaml.Doc doc, Object map, String key) {
        int line = key == null ? doc.lineOf(map) : doc.keyLine(map, key);
        return file + (line > 0 ? ":" + line : "") + ": ";
    }

    /**
     * The price of a model, or null when the table does not know it. A dated id the table does not hold, a known id
     * followed by {@code -} and the eight digits of a snapshot date ({@code claude-haiku-4-5-20251001}, the id the
     * provider names in its answer to a request for {@code claude-haiku-4-5}, BS13 live), has the known id's price.
     */
    public Price price(String model) {
        if (model == null) {
            return null;
        }
        Price own = prices.get(model);
        if (own != null) {
            return own;
        }
        Matcher dated = DATED_ID.matcher(model);
        return dated.matches() ? prices.get(dated.group(1)) : null;
    }

    /** True when the table knows the model. */
    public boolean knows(String model) {
        return price(model) != null;
    }

    /** The model ids the table knows, in table order. */
    public List<String> models() {
        return List.copyOf(prices.keySet());
    }

    /**
     * The cost of a call, 6 decimals, rounded half up: input, output and cache reads at their prices, cache writes at
     * the input price times their time-to-live factor, the whole at the batch factor for a batch call. Priced by the
     * model that answered ({@code usage.model()}, else the requested one); an unknown model at
     * {@value #UNKNOWN_MODEL_PRICE}'s price.
     */
    public BigDecimal cost(Usage usage) {
        Price p = effective(usage.model() != null ? usage.model() : usage.requestedModel());
        BigDecimal total = p.inputPerMTok().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(p.outputPerMTok().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .add(p.cacheReadPerMTok().multiply(BigDecimal.valueOf(usage.cacheReadTokens())))
                .add(p.inputPerMTok().multiply(p.cacheWrite5mFactor()).multiply(
                        BigDecimal.valueOf(usage.cacheWrite5mTokens())))
                .add(p.inputPerMTok().multiply(p.cacheWrite1hFactor()).multiply(
                        BigDecimal.valueOf(usage.cacheWrite1hTokens())));
        if (usage.batch()) {
            total = total.multiply(p.batchFactor());
        }
        return total.divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * An estimate before a call (phase5-contracts.md 7.2, 5.4): {@code inputTokens} at the input price (no cache
     * discount) and {@code outputTokens} at the output price, at the batch factor when {@code batch}.
     */
    public BigDecimal estimate(String model, long inputTokens, long outputTokens, boolean batch) {
        Price p = effective(model);
        BigDecimal total = p.inputPerMTok().multiply(BigDecimal.valueOf(inputTokens))
                .add(p.outputPerMTok().multiply(BigDecimal.valueOf(outputTokens)));
        if (batch) {
            total = total.multiply(p.batchFactor());
        }
        return total.divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }

    /** {@code "priced as claude-opus-5-5 (unknown model x)"} for a model the table does not know, else null. */
    public String note(String model) {
        return knows(model) ? null : "priced as " + UNKNOWN_MODEL_PRICE + " (unknown model " + model + ")";
    }

    private Price effective(String model) {
        Price p = price(model);
        if (p != null) {
            return p;
        }
        Price fallback = prices.get(UNKNOWN_MODEL_PRICE);
        return fallback != null ? fallback : BUILT_IN.prices.get(UNKNOWN_MODEL_PRICE);
    }

    /** The model count only. */
    @Override
    public String toString() {
        return "PriceTable[" + prices.size() + " models]";
    }
}
