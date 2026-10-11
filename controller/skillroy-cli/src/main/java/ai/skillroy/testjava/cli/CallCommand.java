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
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.RpcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code skillroy call <method> [json]}: one primitive call, for exploring a target and for scripts that need a
 * single action without writing a scenario.
 *
 * <p>Phase 7 (phase7-contracts.md 3.4, 9.3, 11 rule 5, decision D332): the raw call sends its arguments through the
 * client with no {@code ExecutorSession} and no {@code CallPolicy}, so the person's half of the Robot opt-in is a flag
 * here too: without {@code --allow-robot} a {@code via: ROBOT} or {@code mode: ROBOT} in the arguments is a usage error
 * before anything is sent; with it the person's typed argument is the second half (the target's agent option
 * {@code robot=true} is the first).
 */
final class CallCommand implements Command {

    private static final String CONFIRM = "--confirm";
    private static final String TIMEOUT = "--timeout";
    private static final String ALLOW_ROBOT = "--allow-robot";

    private static final CommandSpec SPEC = new CommandSpec("call", "call one primitive and print its result",
            "<method> [<json-params>]", """
            Calls one primitive over JSON-RPC and prints its result value as indented JSON. <json-params> is a JSON
            object such as '{"by":{"name":"OK"}}', or @<file> to read it from a file, or - to read it from standard
            input; without it the call has no params. A JSON-RPC error is printed on stderr and gives exit code 1.
            Destructive primitives (agent.disable) are refused by the agent unless --confirm is given.
            Arguments that act through java.awt.Robot (via: ROBOT, mode: ROBOT) are refused before anything is sent
            unless --allow-robot is given; the target's agent option robot=true is the other half of that opt-in.""",
            Connector.withConnection(
                    OptionSpec.flag(CONFIRM, "allow a destructive primitive"),
                    OptionSpec.flag(ALLOW_ROBOT, "allow via: ROBOT and mode: ROBOT in the arguments (the\n"
                            + "person's half of the Robot opt-in)"),
                    OptionSpec.value(TIMEOUT, "duration", "how long the agent may take (10s, 500ms, 2m; the agent\n"
                            + "caps it at 120 s); default: the primitive's own timeout"),
                    OptionSpec.flag("--json", "print the whole result {value, events, seq, durationMs, thread}\n"
                            + "(or {\"error\":...}) as JSON")),
            1, 2);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        String method = args.positionals().get(0).strip();
        if (method.isEmpty()) {
            throw new UsageException("<method> must not be empty");
        }
        Map<String, Object> params = params(args.positionals().size() > 1 ? args.positionals().get(1) : null, cli);
        String robot = robotArgument(params);
        if (robot != null && !args.flag(ALLOW_ROBOT)) {
            throw new UsageException(robot + " acts through java.awt.Robot: a person passes " + ALLOW_ROBOT
                    + " to send it (and the target needs the agent option robot=true); nothing was sent");
        }
        CallOptions options = CallOptions.defaults().withConfirm(args.flag(CONFIRM))
                .withTimeoutMs(args.durationMs(TIMEOUT));
        boolean json = args.flag("--json");
        try (Target target = Connector.connect(args, cli)) {
            RpcResult result;
            try {
                result = target.client().call(method, params, options);
            } catch (AgentRpcException e) {
                if (json) {
                    cli.out().print(target.scrub(Json.writePretty(Text.rpcErrorJson(e))));
                }
                cli.err().println("skillroy call: " + target.scrub(Text.rpcError(e)));
                if (e.error().code() == ErrorCodes.CONFIRM_REQUIRED) {
                    cli.err().println("(" + method + " is destructive: add " + CONFIRM + " to run it)");
                }
                return Exit.FAILED;
            }
            cli.out().print(target.scrub(Json.writePretty(json ? result.toJson() : result.value())));
            return Exit.OK;
        }
    }

    /**
     * {@code via: ROBOT} or {@code mode: ROBOT} when the arguments carry one at the top level (where
     * {@code swing.pointer} and {@code swing.capture} read them), else null.
     */
    static String robotArgument(Map<String, Object> params) {
        if ("ROBOT".equals(params.get("via"))) {
            return "via: ROBOT";
        }
        if ("ROBOT".equals(params.get("mode"))) {
            return "mode: ROBOT";
        }
        return null;
    }

    /**
     * The params argument as a JSON object: inline JSON, {@code @file}, or {@code -} for standard input; an empty
     * map when absent.
     *
     * @throws UsageException if it cannot be read or is not a JSON object
     */
    static Map<String, Object> params(String argument, Cli cli) throws UsageException {
        if (argument == null) {
            return new LinkedHashMap<>();
        }
        String text;
        String source;
        try {
            if (argument.equals("-")) {
                source = "standard input";
                text = new String(cli.in().readAllBytes(), StandardCharsets.UTF_8);
            } else if (argument.startsWith("@")) {
                Path file = ParsedArgs.toPath("<json-params>", argument.substring(1));
                source = file.toString();
                text = Files.readString(file, StandardCharsets.UTF_8);
            } else {
                source = "<json-params>";
                text = argument;
            }
        } catch (IOException e) {
            throw new UsageException("<json-params> " + argument + " cannot be read: " + e);
        }
        Object parsed;
        try {
            parsed = Json.parse(text.strip());
        } catch (JsonException e) {
            throw new UsageException(source + " is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new UsageException(source + " must be a JSON object, found "
                    + (parsed == null ? "null" : Text.oneLine(Json.write(parsed), 80)));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        map.forEach((k, v) -> params.put(String.valueOf(k), v));
        return params;
    }
}
