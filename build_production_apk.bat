@echo off
setlocal enabledelayedexpansion

title Read You - Production Release APK Builder
echo ============================================================================
echo Read You - Production Release APK Builder
echo ============================================================================
echo.

cd /d "%~dp0"

:: 1. Verify project root
if not exist "gradlew.bat" (
    echo [ERROR] gradlew.bat not found in %CD%.
    echo Please ensure this script is located in the root directory of the Read You project.
    echo.
    pause
    exit /b 1
)

:: 2. Locate JDK 17+
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
    echo [INFO] Using JDK: "!JAVA_HOME!"
) else (
    where javac >nul 2>nul
    if !ERRORLEVEL! neq 0 (
        echo [ERROR] Could not find a valid JDK 17+ installation with javac/jlink.
        echo Please ensure JDK 17+ is installed or set JAVA_HOME.
        echo.
        pause
        exit /b 1
    )
)

:: 3. Locate Android SDK & platform-tools
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)

if defined ANDROID_HOME (
    echo [INFO] Using Android SDK: "!ANDROID_HOME!"
    if exist "!ANDROID_HOME!\platform-tools\adb.exe" (
        set "PATH=!ANDROID_HOME!\platform-tools;!PATH!"
    )
)

:: 4. Ensure local.properties exists
if not exist "local.properties" (
    if defined ANDROID_HOME (
        set "ESCAPED_SDK=!ANDROID_HOME:\=\\!"
        echo sdk.dir=!ESCAPED_SDK!> local.properties
        echo [INFO] Generated local.properties pointing to !ANDROID_HOME!
    )
)

echo.
echo ============================================================================
echo [1/3] Running Release Unit Tests...
echo ============================================================================
call gradlew.bat testGithubReleaseUnitTest
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Release unit tests failed! Please review the error log above.
    echo.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ============================================================================
echo [2/3] Building Production Minified & Signed Release APK (assembleGithubRelease)...
echo ============================================================================
call gradlew.bat assembleGithubRelease
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Production release build failed! Please review the error log above.
    echo.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ============================================================================
echo [3/3] Locating Output Release Artifact...
echo ============================================================================

set "APK_DIR=app\build\outputs\apk\github\release"
set "PROD_APK="

if exist "%APK_DIR%" (
    for %%F in ("%APK_DIR%\*.apk") do (
        set "PROD_APK=%%~fF"
        set "APK_NAME=%%~nxF"
        set "APK_SIZE=%%~zF"
    )
)

if defined PROD_APK (
    set /a APK_SIZE_MB=!APK_SIZE! / 1048576
    echo.
    echo [SUCCESS] Production Release APK successfully generated!
    echo ----------------------------------------------------------------------------
    echo File Name : !APK_NAME!
    echo File Size : ~!APK_SIZE_MB! MB (!APK_SIZE! bytes)
    echo Location  : !PROD_APK!
    echo ----------------------------------------------------------------------------
    echo.

    :: Check if an Android device/emulator is connected
    where adb >nul 2>nul
    if !ERRORLEVEL! equ 0 (
        echo Checking for connected Android devices via ADB...
        adb get-state >nul 2>nul
        if !ERRORLEVEL! equ 0 (
            echo.
            set /p INSTALL_CHOICE="Do you want to install this production APK onto your connected device now? (Y/N) [default: N]: "
            if /i "!INSTALL_CHOICE!"=="Y" (
                echo Installing !APK_NAME! onto device...
                adb install -r "!PROD_APK!"
                if !ERRORLEVEL! equ 0 (
                    echo [SUCCESS] APK installed and replaced successfully on your device!
                ) else (
                    echo [WARNING] ADB install reported an error. You can install it manually from:
                    echo !PROD_APK!
                )
            )
        ) else (
            echo [INFO] No active Android device/emulator connected.
        )
    )

    echo.
    set /p OPEN_EXPLORER="Open output folder in Windows File Explorer? (Y/N) [default: Y]: "
    if "!OPEN_EXPLORER!"=="" set "OPEN_EXPLORER=Y"
    if /i "!OPEN_EXPLORER!"=="Y" (
        explorer.exe /select,"!PROD_APK!"
    )
) else (
    echo [ERROR] Could not find generated .apk file in %APK_DIR%.
)

echo.
echo ============================================================================
echo Build process complete.
echo ============================================================================
echo.
pause
exit /b 0
