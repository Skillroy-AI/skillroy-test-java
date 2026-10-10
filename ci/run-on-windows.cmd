@echo off
rem
rem Copyright 2026 Mike Mills.
rem
rem Licensed under the Apache License, Version 2.0 (the "License");
rem you may not use this file except in compliance with the License.
rem You may obtain a copy of the License at
rem
rem     http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing, software
rem distributed under the License is distributed on an "AS IS" BASIS,
rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and
rem limitations under the License.
rem
rem The build and its acceptance on a Windows host (phase6-contracts.md 9.5 and 13.3): mvn verify from the repository
rem root, once with the Java 8 targets and once with the Java 25 targets, the display tests included. Run it in an
rem interactive logon session of the user the targets run as (the tests open real windows; a service session or a
rem remote session without a desktop cannot run them). Written in Phase 6 and not yet run: no Windows host existed
rem (docs/TODO.md, Walls); 13.3 lists what the host must show beside this run.
rem
rem   ci\run-on-windows.cmd
rem
rem Environment:
rem   JAVA_HOME           a JDK 25 or newer: the build, the controller and the Java 25 targets
rem   SKILLROY_JDK8_HOME  a JDK 8: the Java 8 targets
setlocal
rem cmd.exe looks for a command named without a path in the current directory before the PATH unless this variable
rem exists: with it, call mvn below never finds an mvn.cmd in the checkout it changes into (security fix P2-L7-F2).
set "NoDefaultCurrentDirectoryInExePath=1"

if not defined JAVA_HOME goto :missing
if not defined SKILLROY_JDK8_HOME goto :missing
if not exist "%JAVA_HOME%\bin\java.exe" goto :missing
if not exist "%SKILLROY_JDK8_HOME%\bin\java.exe" goto :missing

cd /d "%~dp0.."
echo == the Java 8 targets
call mvn verify -Dskillroy.headless=false "-Dskillroy.it.targetJava=%SKILLROY_JDK8_HOME%\bin\java.exe"
if errorlevel 1 exit /b 1
echo == the Java 25 targets
call mvn verify -Dskillroy.headless=false
exit /b %ERRORLEVEL%

:missing
echo run-on-windows: set JAVA_HOME to a JDK 25 and SKILLROY_JDK8_HOME to a JDK 8, then run, from the repository root
echo in an interactive logon session, the two commands this script runs:
echo   mvn verify -Dskillroy.headless=false -Dskillroy.it.targetJava=%%SKILLROY_JDK8_HOME%%\bin\java.exe
echo   mvn verify -Dskillroy.headless=false
echo found: JAVA_HOME=%JAVA_HOME%
echo found: SKILLROY_JDK8_HOME=%SKILLROY_JDK8_HOME%
exit /b 2
