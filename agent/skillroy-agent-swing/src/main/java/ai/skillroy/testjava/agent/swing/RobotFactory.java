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

package ai.skillroy.testjava.agent.swing;

import java.awt.GraphicsDevice;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * The seam behind which the Robot implementation is constructed (phase7-contracts.md section 3.4, D290): the
 * {@link #DEFAULT} factory loads {@link RobotInput} by name, so that the class (and {@code java.awt.Robot} with it) is
 * loaded only when the agent option {@code robot} is set and the probe after {@code checkAwt} asks for it; tests hand
 * in a fake. Called on an agent worker, never the EDT.
 */
interface RobotFactory {

    /**
     * A port for a screen device.
     *
     * @throws Exception what {@code new Robot(device)} throws (an {@code AWTException} without XTEST, an
     *     {@code UnsupportedOperationException} or a {@code SecurityException})
     */
    RobotPort create(GraphicsDevice device) throws Exception;

    /** {@link RobotInput}, loaded lazily by name. */
    RobotFactory DEFAULT = new RobotFactory() {
        @Override
        public RobotPort create(GraphicsDevice device) throws Exception {
            Class<?> k = Class.forName("ai.skillroy.testjava.agent.swing.RobotInput", true,
                    RobotFactory.class.getClassLoader());
            Constructor<?> ctor = k.getDeclaredConstructor(GraphicsDevice.class);
            ctor.setAccessible(true);
            try {
                return (RobotPort) ctor.newInstance(device);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw e;
            }
        }
    };
}
