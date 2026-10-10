@echo off
rem ---------------------------------------------------------------------------
rem Gradle entry forwarder at the build-copy root.
rem
rem The Gradle project lives in manager\ (settings.gradle.kts and the real
rem gradlew.bat are there). The repo root is the Rust/kernel project root and
rem has no gradlew. Some automation calls:
rem     cd /d C:\sukisu-build && gradlew.bat ...
rem so this shim forwards into manager\. The forwarded call must use an
rem explicit path, otherwise after pushd it would resolve back to this file
rem and recurse forever.
rem ASCII only on purpose: cmd.exe decodes .bat files with the ANSI code page,
rem so any non-ASCII byte here turns into mojibake on the console.
rem ---------------------------------------------------------------------------
setlocal
pushd "%~dp0manager"
call "%~dp0manager\gradlew.bat" %*
set _GW_EXIT=%ERRORLEVEL%
popd
endlocal & exit /b %_GW_EXIT%
