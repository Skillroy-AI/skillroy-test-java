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
rem skillroy: the command line front end of skillroy-test-java. Needs a Java 25 or newer runtime.
rem
rem   skillroy --help                the commands and the exit codes
rem   skillroy <command> --help      the options of one command
rem
rem Environment:
rem   JAVA_HOME           the Java runtime to use; without it, java on the PATH
rem   SKILLROY_JAVA_OPTS  extra JVM options for the CLI itself (for example -Xmx256m), split at spaces
rem
rem The commands that use the JDK attach API (jvms, attach) get --add-modules jdk.attach when the runtime has that
rem module; a runtime image without it still runs every other command. Exit code 69: no usable Java runtime.
rem
rem The Windows twin of bin/skillroy, line for line (phase6-contracts.md 9.1, D137). Not yet verified on a Windows
rem host: the text is checked by BatLauncherTest, the run waits for the host (9.5).
setlocal

rem The installation directory is the parent of this script's directory. cmd.exe cannot follow a symbolic link to a
rem script, so a linked skillroy.bat is unsupported: call the one in the installation's bin directory.
for %%i in ("%~dp0..") do set "home=%%~fi"

if not defined JAVA_HOME goto :java_from_path
set "java=%JAVA_HOME%\bin\java.exe"
if exist "%java%" goto :java_found
>&2 echo skillroy: JAVA_HOME is '%JAVA_HOME%', which has no executable bin/java
exit /b 69

:java_from_path
set "java="
for %%i in (java.exe) do set "java=%%~$PATH:i"
if defined java goto :java_found
>&2 echo skillroy: no java on the PATH; install a Java 25 or newer runtime or set JAVA_HOME
exit /b 69

:java_found
rem The runtime's release file, two levels above java.exe.
for %%i in ("%java%\..\..\release") do set "release=%%~fi"

rem A runtime older than Java 25 cannot load the CLI's classes: say so plainly when its release file tells. The major
rem is the text before the first dot (1.8.0_x gives 1, as in the shell launcher); a non-numeric major is let through.
if not exist "%release%" goto :version_ok
set "version="
for /f "tokens=2 delims==" %%v in ('findstr /b /c:"JAVA_VERSION=" "%release%"') do set "version=%%~v"
if not defined version goto :version_ok
set "major="
for /f "delims=." %%m in ("%version%") do set "major=%%m"
echo %major%| findstr /r /x "[0-9][0-9]*" >nul 2>nul || goto :version_ok
if %major% LSS 25 goto :too_old
goto :version_ok
:too_old
>&2 echo skillroy: %java% is Java %version%; the CLI needs Java 25 or newer (set JAVA_HOME)
exit /b 69
:version_ok

set "modules="
if /i "%~1"=="jvms" goto :attach_check
if /i "%~1"=="attach" goto :attach_check
goto :run
:attach_check
if not exist "%release%" goto :attach_list
findstr /b /c:"MODULES=" "%release%" >nul 2>nul || goto :attach_list
findstr /r /c:"^MODULES=.*[\" ]jdk\.attach[\" ]" "%release%" >nul 2>nul && set "modules=--add-modules jdk.attach"
goto :run
:attach_list
"%java%" --list-modules 2>nul | findstr /b /c:"jdk.attach@" >nul 2>nul && set "modules=--add-modules jdk.attach"

:run
rem skillroy.launcher names this script to the CLI, which writes it into the MCP configuration of 'skillroy author'
rem (the planner starts 'skillroy mcp' through it, as "cmd.exe /c <launcher>"). There is no exec in cmd.exe: the
rem exit code of java is relayed.
"%java%" %SKILLROY_JAVA_OPTS% %modules% "-Dskillroy.launcher=%home%\bin\skillroy.bat" -cp "%home%\lib\*" ai.skillroy.testjava.cli.Main %*
exit /b %ERRORLEVEL%
