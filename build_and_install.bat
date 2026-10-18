@echo off
setlocal enabledelayedexpansion

echo ============================================================================
echo Read You - Build, Test, and Install Launcher
echo ============================================================================
echo.

cd /d "%~dp0"

if not exist "gradlew.bat" (
    echo [ERROR] gradlew.bat not found in %CD%.
    echo Please ensure this script is located in the root directory of the project.
    pause
    exit /b 1
)

:: Check for JDK 17+ or configure known Java environments
if not defined JAVA_HOME (
    for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*" "%ProgramFiles%\Eclipse Adoptium\jdk-21*" "%ProgramFiles%\Java\jdk-17*" "%ProgramFiles%\Java\jdk-21*") do (
        if exist "%%~fJ\bin\javac.exe" (
            set "JAVA_HOME=%%~fJ"
        )
    )
    if not defined JAVA_HOME (
        if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\javac.exe" (
            set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
        ) else if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\javac.exe" (
            set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
        )
    )
)

if defined JAVA_HOME (
    set "PATH=!JAVA_HOME!\bin;!PATH!"
    echo [INFO] Using Java at "!JAVA_HOME!"
) else (
    where javac >nul 2>nul
    if !ERRORLEVEL! neq 0 (
        echo [ERROR] Could not find a valid JDK 17+ installation with javac/jlink.
        pause
        exit /b 1
    )
)

:: Configure Android SDK environment if not already set
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)
if defined ANDROID_HOME (
    if exist "!ANDROID_HOME!\platform-tools" (
        set "PATH=!ANDROID_HOME!\platform-tools;!PATH!"
    )
)

echo.
echo [1/3] Running Unit Tests (testGithubDebugUnitTest)...
call gradlew.bat testGithubDebugUnitTest
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Unit tests failed! Please review the output above.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [2/3] Building Debug APK (assembleGithubDebug)...
call gradlew.bat assembleGithubDebug
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Debug build failed! Please review the output above.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [3/3] Checking for connected Android device/emulator to install APK...
where adb >nul 2>nul
if %ERRORLEVEL% equ 0 (
    call gradlew.bat installGithubDebug
    if !ERRORLEVEL! neq 0 (
        echo.
        echo [WARNING] Could not install APK onto device ^(is a device connected with USB debugging enabled?^).
        echo The debug APK was built successfully and can be found in: app\build\outputs\apk\github\debug\
        pause
        exit /b 0
    )
    echo.
    echo [SUCCESS] Read You Debug APK built and installed successfully!
) else (
    echo [INFO] adb not found on PATH. Skipping automatic device installation.
    echo [SUCCESS] Read You Debug APK built successfully in: app\build\outputs\apk\github\debug\
)

echo.
pause
exit /b 0
