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


package ai.skillroy.testjava.scenario.observe;

/**
 * A wait of the open call: the {@code sync} record's facts.
 *
 * @param mode {@code ACK}, {@code READY}, {@code SETTLE} or {@code EVENT}
 * @param ok whether the wait ended as it should
 * @param eventSeq the sequence number of the event that ended it, or null
 * @param topic the topic of that event, or null
 * @param ms how long it waited
 * @param source who published that event ({@code agent} or a web path's id), or null
 */
public record AwaitRecord(String mode, boolean ok, Long eventSeq, String topic, long ms, String source) {
}
