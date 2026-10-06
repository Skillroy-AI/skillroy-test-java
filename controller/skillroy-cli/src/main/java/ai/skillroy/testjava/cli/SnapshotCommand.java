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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.UiSnapshotText;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code skillroy snapshot}: the target's UI tree as compact text (the same rendering failure reports use), or the
 * raw {@code swing.snapshot} value as JSON.
 */
final class SnapshotCommand implements Command {

    private static final String ROOT = "--root";
    private static final String MAX_DEPTH = "--max-depth";
    private static final String INCLUDE_HIDDEN = "--include-hidden";
    private static final String MAX_CHARS = "--max-chars";
    private static final long DEFAULT_MAX_CHARS = 1_000_000L;

    private static final CommandSpec SPEC = new CommandSpec("snapshot", "print the UI tree of the target", "", """
            Reads the UI tree (swing.snapshot) and the application state (app.state) and prints them as indented
            text: one line per window and component with its role, name, text, ref and flags; unnamed containers are
            folded away. With --json the swing.snapshot value is printed as it is.""",
            Connector.withConnection(
                    OptionSpec.value(ROOT, "ref", "start at this component or window ref instead of all windows"),
                    OptionSpec.value(MAX_DEPTH, "n", "the deepest level to read (the agent's default is 40)"),
                    OptionSpec.flag(INCLUDE_HIDDEN, "include components that are not showing"),
                    OptionSpec.value(MAX_CHARS, "n", "cut the text after about n characters (default 1000000)"),
                    OptionSpec.flag("--json", "print the swing.snapshot value {seq, truncated, windows} as JSON")),
            0, 0);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if (args.value(ROOT) != null) {
            params.put("root", args.value(ROOT));
        }
        Long maxDepth = args.longValue(MAX_DEPTH, 1, 1000);
        if (maxDepth != null) {
            params.put("maxDepth", maxDepth);
        }
        if (args.flag(INCLUDE_HIDDEN)) {
            params.put("includeHidden", Boolean.TRUE);
        }
        Long maxChars = args.longValue(MAX_CHARS, 100, Integer.MAX_VALUE);
        try (Target target = Connector.connect(args, cli)) {
            Object snapshot;
            try {
                snapshot = target.client().call("swing.snapshot", params).value();
            } catch (AgentRpcException e) {
                cli.err().println("skillroy snapshot: " + target.scrub(Text.rpcError(e)));
                return Exit.FAILED;
            }
            if (args.flag("--json")) {
                cli.out().print(target.scrub(Json.writePretty(snapshot)));
                return Exit.OK;
            }
            Object appState = null;
            try {
                appState = target.client().call("app.state", Map.of()).value();
            } catch (AgentRpcException e) {
                cli.err().println("skillroy snapshot: (no application state: " + Text.oneLine(e.getMessage(), 200)
                        + ")");
            }
            String text = UiSnapshotText.render(snapshot, appState,
                    (int) (maxChars == null ? DEFAULT_MAX_CHARS : maxChars));
            cli.out().print(target.scrub(text.endsWith("\n") || text.isEmpty() ? text : text + "\n"));
            return Exit.OK;
        }
    }
}
