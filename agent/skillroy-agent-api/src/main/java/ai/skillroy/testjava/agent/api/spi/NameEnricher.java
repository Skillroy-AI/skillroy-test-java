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

package ai.skillroy.testjava.agent.api.spi;

/**
 * Names for components whose accessible name is empty (image-only buttons). Called on the EDT. Without it such
 * components could only be located by fragile paths.
 *
 * <p>Called on the EDT; must be fast and must not change the component.
 */
public interface NameEnricher {

    /** A stable name for the component; null = no opinion. */
    String nameFor(java.awt.Component component);
}
