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


package ai.skillroy.testjava.fixture.hooks;

/**
 * A slip's header line, for the install whose only class fails its retransformation and for a hook's ERROR reason
 * (phase6-contracts.md sections 3.2 and 3.6). Only {@code PartialInstallTest} hooks it.
 */
public class SlipFormat {

    private final String label = "Slip: ";

    public String header(String item) {
        return label + item;
    }
}
