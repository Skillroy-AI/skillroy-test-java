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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code skillroy schema}: the agent's primitive catalogue, so that {@code call} can be used without the docs. */
final class SchemaCommand implements Command {

    private static final String NAME = "--name";

    private static final CommandSpec SPEC = new CommandSpec("schema", "list the primitives the agent offers", "", """
            Lists the agent's primitives (GET /schema): name, whether they change the application (mutating) or
            need --confirm (destructive), the thread they run on, their default timeout, the capability they need,
            their description and their parameters ('name?' marks an optional one).""",
            Connector.withConnection(
                    OptionSpec.value(NAME, "glob", "only primitives whose name matches: comma-separated globs,\n"
                            + "'*' matches any run of characters including dots (swing.*)"),
                    OptionSpec.flag("--json", "print {\"primitives\":[descriptor...]} as JSON")),
            0, 0);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        String glob = args.value(NAME);
        TopicFilter filter = glob == null ? null : TopicFilter.parse(glob);
        try (Target target = Connector.connect(args, cli)) {
            List<PrimitiveDescriptor> matching = new ArrayList<>();
            for (PrimitiveDescriptor descriptor : target.client().schema()) {
                if (filter == null || filter.matches(descriptor.name())) {
                    matching.add(descriptor);
                }
            }
            if (args.flag("--json")) {
                List<Object> list = new ArrayList<>();
                matching.forEach(d -> list.add(d.toJson()));
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("primitives", list);
                cli.out().print(target.scrub(Json.writePretty(json)));
            } else {
                for (PrimitiveDescriptor descriptor : matching) {
                    cli.out().print(target.scrub(describe(descriptor)));
                }
            }
            if (matching.isEmpty() && glob != null) {
                cli.err().println("skillroy schema: no primitive matches " + NAME + " " + glob);
            }
            return Exit.OK;
        }
    }

    /** One primitive as two to four lines of text. */
    static String describe(PrimitiveDescriptor d) {
        List<String> facts = new ArrayList<>();
        if (d.destructive()) {
            facts.add("destructive: needs --confirm");
        } else if (d.mutating()) {
            facts.add("mutating");
        }
        if (d.thread() != null) {
            facts.add("thread " + d.thread());
        }
        if (d.timeoutMs() > 0) {
            facts.add("timeout " + d.timeoutMs() + " ms");
        }
        if (d.capabilityId() != null) {
            facts.add("needs " + d.capabilityId());
        }
        StringBuilder text = new StringBuilder(d.name());
        if (!facts.isEmpty()) {
            text.append("  (").append(String.join("; ", facts)).append(')');
        }
        text.append('\n');
        if (d.description() != null && !d.description().isBlank()) {
            text.append("    ").append(Text.oneLine(d.description().strip(), 200)).append('\n');
        }
        String params = params(d.paramsSchema());
        if (!params.isEmpty()) {
            text.append("    params: ").append(params).append('\n');
        }
        return text.toString();
    }

    /** {@code name: type, other?: type} from a JSON Schema object; empty when it lists no properties. */
    static String params(Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties) || properties.isEmpty()) {
            return "";
        }
        List<?> required = schema.get("required") instanceof List<?> list ? list : List.of();
        List<String> parts = new ArrayList<>();
        properties.forEach((name, property) -> {
            StringBuilder part = new StringBuilder(String.valueOf(name));
            if (!required.contains(name)) {
                part.append('?');
            }
            if (property instanceof Map<?, ?> p && p.get("type") != null) {
                Object type = p.get("type");
                part.append(": ").append(type instanceof List<?> types ? String.join("|",
                        types.stream().map(String::valueOf).toList()) : String.valueOf(type));
            }
            parts.add(part.toString());
        });
        return String.join(", ", parts);
    }
}
