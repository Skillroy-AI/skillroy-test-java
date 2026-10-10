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

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * {@link RobotPort} with {@code java.awt.Robot} (phase7-contracts.md section 3.4, D290): JDK API only, one per screen
 * device, {@code setAutoWaitForIdle(false)} and {@code setAutoDelay(0)}, constructed on an agent worker after
 * {@code checkAwt} confirmed that the application started AWT (A4); never used on the EDT ({@code waitForIdle} throws
 * there). Loaded only through {@link RobotFactory#DEFAULT}, by name. Package-private.
 */
final class RobotInput implements RobotPort {

    private final Robot robot;

    RobotInput(GraphicsDevice device) throws AWTException {
        this.robot = device == null ? new Robot() : new Robot(device);
        robot.setAutoWaitForIdle(false);
        robot.setAutoDelay(0);
    }

    @Override
    public void mouseMove(int x, int y) {
        robot.mouseMove(x, y);
    }

    @Override
    public void mousePress(int buttons) {
        robot.mousePress(buttons);
    }

    @Override
    public void mouseRelease(int buttons) {
        robot.mouseRelease(buttons);
    }

    @Override
    public void mouseWheel(int notches) {
        robot.mouseWheel(notches);
    }

    @Override
    public BufferedImage createScreenCapture(Rectangle screen) {
        return robot.createScreenCapture(screen);
    }

    @Override
    public Point pointer() {
        PointerInfo info = MouseInfo.getPointerInfo();
        return info == null ? null : info.getLocation();
    }
}
