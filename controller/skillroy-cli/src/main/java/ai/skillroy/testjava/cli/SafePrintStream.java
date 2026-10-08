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

import java.io.PrintStream;

/**
 * The CLI's standard output and error: a {@link PrintStream} that passes all character output through
 * {@link Text#printable} before writing it (security finding S26). The CLI prints text the target controls (component
 * text, error messages, event topics, discovery-file values); an ESC sequence in it could retitle the terminal, write
 * the clipboard or repaint a FAIL line as PASS. Neutralising at the print boundary covers every command, including
 * stack traces and commands added later.
 *
 * <p>Line separators written by {@code println()} pass untouched; only the printed values are filtered. Raw byte
 * writes ({@code write(byte[], int, int)}) pass through: the CLI writes no bytes it did not encode itself.
 *
 * <p>Thread-safe as {@link PrintStream} is: every method locks the stream.
 */
final class SafePrintStream extends PrintStream {

    private final PrintStream target;

    /** Wraps {@code target}, writing in its charset with autoflush on line ends. */
    private SafePrintStream(PrintStream target) {
        super(target, true, target.charset());
        this.target = target;
    }

    /** {@code stream} itself when it already filters, else a filtering wrapper around it. */
    static PrintStream wrap(PrintStream stream) {
        return stream instanceof SafePrintStream ? stream : new SafePrintStream(stream);
    }

    /**
     * The stream a wrapper writes to, or {@code stream} itself when it is not a wrapper: what {@code skillroy mcp}
     * speaks the MCP protocol on (phase5-contracts.md 9.2). A protocol's bytes are not text for a terminal, and the
     * filter would rewrite a control character a JSON-RPC message may carry; the server masks its answers itself.
     */
    static PrintStream unwrap(PrintStream stream) {
        return stream instanceof SafePrintStream safe ? safe.target : stream;
    }

    @Override
    public void print(String s) {
        super.print(Text.printable(String.valueOf(s)));
    }

    @Override
    public void print(Object obj) {
        super.print(Text.printable(String.valueOf(obj)));
    }

    @Override
    public void print(char[] s) {
        super.print(Text.printable(new String(s)));
    }

    @Override
    public void print(char c) {
        super.print(Text.printable(String.valueOf(c)));
    }

    @Override
    public void println(String x) {
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(Object x) {
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(char[] x) {
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(char x) {
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public PrintStream append(CharSequence csq) {
        print(String.valueOf(csq));
        return this;
    }

    @Override
    public PrintStream append(CharSequence csq, int start, int end) {
        CharSequence cs = csq == null ? "null" : csq;
        print(cs.subSequence(start, end).toString());
        return this;
    }

    @Override
    public PrintStream append(char c) {
        print(c);
        return this;
    }
}
