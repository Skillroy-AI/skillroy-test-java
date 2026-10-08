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

package ai.skillroy.testjava.agent.web.fake;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

import java.util.concurrent.atomic.AtomicInteger;

/** The neutral fake's navigation ({@code on(Class, Observer)}, {@code reload()}, {@code reloadIgnoringCache()}). */
public final class FakeNavigation {

    static {
        ClassInitialisations.record(FakeNavigation.class);
    }

    private final FakeBrowser browser;
    final AtomicInteger reloads = new AtomicInteger();

    FakeNavigation(FakeBrowser browser) {
        this.browser = browser;
    }

    public FakeSubscription on(Class<?> type, FakeObserver<?> observer) {
        return browser.on(type, observer);
    }

    public void reload() {
        reloads.incrementAndGet();
        browser.reloadDocument();
    }

    public void reloadIgnoringCache() {
        reloads.incrementAndGet();
        browser.reloadDocument();
    }

    /** How many reloads were asked (for tests). */
    public int reloads() {
        return reloads.get();
    }
}
