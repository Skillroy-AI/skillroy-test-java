#
# Copyright 2026 Mike Mills.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Spike BS9 (and BS10): the test image plus a small window manager and the X tools of the F13 check, all from the base
# distribution's own apt repositories. openbox: a small stacking window manager that implements EWMH
# (_NET_ACTIVE_WINDOW, _NET_WM_USER_TIME, transient-for stacking), focuses new windows and focuses on click by default,
# needs no session or compositor, and pulls in no desktop environment (xfwm4 and metacity bring much of Xfce or GNOME).
# xdotool: XTEST input from outside the JVM (no --window) and the X focus window; x11-utils: xprop, xdpyinfo, xwininfo.
# Build (never modifies the base image):
#   docker build --platform linux/arm64 --build-arg BASE=skillroy-test-java-ci:bs8-arm64 \
#     -t skillroy-spike-bs9:wm-arm64 spikes/BS9/docker
ARG BASE=skillroy-test-java-ci:bs8-arm64
FROM ${BASE}
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openbox xdotool x11-utils \
 && rm -rf /var/lib/apt/lists/*
