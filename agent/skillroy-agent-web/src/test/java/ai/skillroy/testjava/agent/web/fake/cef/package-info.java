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

/**
 * The fake JCEF shapes (phase4-contracts.md sections 10.2 and 12.1 item 3): an application singleton, a client and
 * browsers with the members the {@code jcef} adapter's descriptor names, and the part of the DevTools protocol the
 * adapter speaks, with BS8's behaviour (answers on the EDT, a first client that reports "Client is closed", a 1 x 1
 * viewport until overridden, a canvas that throws when printed). Driven by {@code fake-jcef.json} and its variants.
 * The real JCEF run of the kit is the web host sample's. Java 8.
 */
package ai.skillroy.testjava.agent.web.fake.cef;
