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

package ai.skillroy.testjava.agent.web.reflect;

import java.util.function.Consumer;

/** A class standing for a vendor's, for the reflection tests. */
public class Sample {

    /** A vendor interface the adapter implements with a proxy. */
    public interface Listener {
        String heard(String what);
    }

    public static int created;
    private static String secret = "hidden";
    public String name = "sample";
    private final StringBuilder log = new StringBuilder();

    public static Sample create() {
        created++;
        return new Sample();
    }

    public String echo(String text) {
        return "echo " + text;
    }

    public int add(int a, int b) {
        return a + b;
    }

    public void run(String text, Consumer<Object> callback) {
        callback.accept(text.toUpperCase(java.util.Locale.ROOT));
    }

    public <T> void put(Class<T> type, T value) {
        log.append(type.getSimpleName()).append('=').append(value);
    }

    public String fail() {
        throw new IllegalStateException("vendor boom");
    }

    public String tell(Listener l) {
        return l.heard("news");
    }

    protected String guarded() {
        return "guarded";
    }

    String log() {
        return log.toString();
    }

    static String secret() {
        return secret;
    }
}
