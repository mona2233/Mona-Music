@echo off
setlocal

rem Android ????????? D:\ProgramData\testprogram\BuildTools\Android\toolchain
for %%I in ("%~dp0..\..\..\BuildTools\Android\toolchain") do set "TOOLS_ROOT=%%~fI"
set "ANDROID_SDK_ROOT=%TOOLS_ROOT%\android-sdk"

if not exist "%ANDROID_SDK_ROOT%" (
  echo [ERROR] ??? Android SDK: %ANDROID_SDK_ROOT%
  exit /b 1
)

set "JAVA_HOME="
for /d %%D in ("%TOOLS_ROOT%\jdk21\*") do (
  set "JAVA_HOME=%%~fD"
  goto :java_found
)

:java_found
if not defined JAVA_HOME (
  echo [ERROR] ??? JDK??????: %TOOLS_ROOT%\jdk21
  exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%ANDROID_SDK_ROOT%\platform-tools;%PATH%"

if not exist signing.properties (
  echo [ERROR] ??? signing.properties????? signing.properties.template ??
  exit /b 1
)

echo [INFO] ???? Release APK...
call gradlew.bat clean :app:assembleRelease
if %errorlevel% neq 0 (
  echo [ERROR] Release APK ????
  exit /b %errorlevel%
)

echo [INFO] ???? Release AAB...
call gradlew.bat :app:bundleRelease
if %errorlevel% neq 0 (
  echo [ERROR] Release AAB ????
  exit /b %errorlevel%
)

echo [OK] ????????? app\build\outputs\
exit /b 0
