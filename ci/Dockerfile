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
# Linux image for GUI and cross-JDK tests: JDK 25 (controller + modern target) with Xvfb and fonts, plus the
# run-time libraries of an embedded Chromium (JCEF; spike BS8 and Phase 4). The second package line is exactly what
# `ldd` reports missing for libcef.so, libjcef.so and jcef_helper of jcefmaven 152.0.6 on this base image; OpenGL for
# off-screen rendering (libgl1, libgl1-mesa-dri, libxxf86vm1) is already present as a dependency of xvfb.
# A JDK 8 for legacy-target tests is mounted at /opt/jdk8 at run time (see ci/run-in-linux.sh).
FROM eclipse-temurin:25-jdk
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      xvfb xauth libxtst6 libxi6 libxrender1 libxext6 libfreetype6 fontconfig fonts-dejavu-core procps \
      libnss3 libnspr4 libatk1.0-0t64 libatk-bridge2.0-0t64 libatspi2.0-0t64 libcups2t64 libdbus-1-3 \
      libglib2.0-0t64 libcairo2 libpango-1.0-0 libasound2t64 libxcomposite1 libxdamage1 libxfixes3 libxkbcommon0 \
 && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8
