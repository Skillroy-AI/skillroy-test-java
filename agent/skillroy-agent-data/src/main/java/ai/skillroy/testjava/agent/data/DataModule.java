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

package ai.skillroy.testjava.agent.data;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.PrimitiveRegistry;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import ai.skillroy.testjava.protocol.sql.ValueConverter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Agent module {@code data}: read access to the target application's own data through the
 * {@link DataSourceProvider}s that domain modules register, so that scenarios can look up and verify facts without
 * database credentials ever leaving the target JVM.
 *
 * <p>Registers two primitives, both on an agent worker thread ({@link ThreadPolicy#WORKER}):
 * <ul>
 *   <li>{@code data.sources} {@code {}} -> {@code {sources:[{id, describe}]}};</li>
 *   <li>{@code data.query} {@code {source, sql, params?, maxRows? (200, at most 200)}} ->
 *       {@code {columns:[{name,type}], rows:[[...]], rowCount, truncated}}, timeout 10000 ms, capability
 *       {@code data.query}. The statement must pass {@link StatementGuard}; the provider's {@code query()} form is
 *       tried first and its {@code open()} form is the fallback; values are converted to JSON (decimals as plain
 *       strings, dates as ISO-8601) and then passed through every registered {@code MaskPolicy}. A failure reports
 *       SQL state, error code and exception class; the driver's own text is withheld unless the provider opted in
 *       and no {@code MaskPolicy} is registered (finding S22, see {@link DataQuery}).</li>
 * </ul>
 * and declares the capability {@code data.query}: OK while at least one {@code DataSourceProvider} is registered,
 * otherwise UNAVAILABLE.
 *
 * <p>The module adds nothing to the host (no listeners, no threads) and touches no application object until a
 * provider is called. Discovered through {@code META-INF/services}; the core calls install and uninstall one at a time.
 */
public final class DataModule implements AgentModule {

    /** Module id. */
    public static final String ID = "data";
    /** Module version reported by {@code agent.info}. */
    public static final String VERSION = "0.1.0";
    /** Name of the primitive that lists data sources. */
    public static final String SOURCES = "data.sources";
    /** Name of the query primitive, and id of its capability. */
    public static final String QUERY = "data.query";

    private volatile PrimitiveRegistry registry;
    /** Replaces the providers' own {@code exposesErrorText()} answer (tests only); null in production. */
    private final Predicate<DataSourceProvider> errorTextOptIn;

    /** For {@code ServiceLoader}. */
    public DataModule() {
        this(null);
    }

    /** For tests: {@code errorTextOptIn} answers in place of the providers' {@code exposesErrorText()}. */
    DataModule(Predicate<DataSourceProvider> errorTextOptIn) {
        this.errorTextOptIn = errorTextOptIn;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    /**
     * Registers {@code data.sources} and {@code data.query} and declares the {@code data.query} capability. If any
     * step fails, whatever was registered before it is unregistered again and the failure is rethrown.
     */
    @Override
    public void install(AgentContext ctx) throws Exception {
        DataQuery queries = new DataQuery(ctx.spi(), ctx.log(), errorTextOptIn);
        PrimitiveRegistry primitives = ctx.primitives();
        List<String> registered = new ArrayList<String>();
        try {
            primitives.register(Primitives.of(sourcesSpec(), invocation -> queries.sources()));
            registered.add(SOURCES);
            primitives.register(Primitives.of(querySpec(),
                    invocation -> queries.query(invocation.args(), invocation::remainingMs)));
            registered.add(QUERY);
            ctx.capabilities().declare(QUERY, probeCtx -> probeCtx.spi().all(DataSourceProvider.class).isEmpty()
                    ? ProbeResult.unavailable("no DataSourceProvider is registered")
                    : ProbeResult.ok());
        } catch (RuntimeException e) {
            for (String name : registered) {
                primitives.unregister(name);
            }
            throw e;
        }
        registry = primitives;
    }

    /** Unregisters the primitives. The module added nothing to the host, so there is nothing else to remove. */
    @Override
    public void uninstall() {
        PrimitiveRegistry primitives = registry;
        registry = null;
        if (primitives != null) {
            primitives.unregister(SOURCES);
            primitives.unregister(QUERY);
        }
    }

    static PrimitiveSpec sourcesSpec() {
        return PrimitiveSpec.builder(SOURCES)
                .description("Lists the registered data sources: {sources:[{id, describe}]}.")
                .resultSchema(object(
                        "sources", array(object("id", type("string"), "describe", type("string|null")))))
                .thread(ThreadPolicy.WORKER)
                .timeoutMs(5000)
                .build();
    }

    static PrimitiveSpec querySpec() {
        return PrimitiveSpec.builder(QUERY)
                .description("Runs one read-only SELECT or WITH statement on a registered data source and returns at"
                        + " most maxRows rows (default and limit " + DataQuery.MAX_ROWS + "). The statement may end"
                        + " with one ';' and must not contain another outside literals, quoted identifiers and"
                        + " comments; data-changing words are rejected. Numbers stay numbers (DECIMAL as a plain"
                        + " string), dates and times become ISO-8601 strings, binary values"
                        + " \"base64:...\" (first " + ValueConverter.MAX_BINARY_BYTES + " bytes), text is capped at "
                        + ValueConverter.MAX_TEXT_CHARS + " characters; every MaskPolicy is applied before rows"
                        + " leave the target.")
                .param("source", "string", true, "data source id, see data.sources")
                .param("sql", "string", true, "one SELECT or WITH statement; '?' marks a positional parameter")
                .param("params", "array", false, "positional parameter values: strings, numbers, booleans, null")
                .param("maxRows", "integer", false, "rows to return, default and limit " + DataQuery.MAX_ROWS)
                .resultSchema(object(
                        "columns", array(object("name", type("string"), "type", type("string|null"))),
                        "rows", array(type("array")),
                        "rowCount", type("integer"),
                        "truncated", type("boolean")))
                .thread(ThreadPolicy.WORKER)
                .timeoutMs(10000)
                .capability(QUERY)
                .build();
    }

    private static Map<String, Object> object(Object... namesAndSchemas) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < namesAndSchemas.length; i += 2) {
            properties.put((String) namesAndSchemas[i], namesAndSchemas[i + 1]);
        }
        Map<String, Object> schema = type("object");
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> schema = type("array");
        schema.put("items", items);
        return schema;
    }

    private static Map<String, Object> type(String type) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", type.contains("|") ? Arrays.asList(type.split("\\|")) : type);
        return schema;
    }
}
