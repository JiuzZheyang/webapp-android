@echo off
echo ========================================
echo   WebApp 安卓应用一键构建脚本
echo ========================================
echo.

echo [1/3] 检查 Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo 错误：未检测到 Java JDK
    echo 请先安装 JDK 17+：https://adoptium.net/
    pause
    exit /b 1
)

echo [2/3] 检查 Android SDK...
if not exist "%ANDROID_HOME%" (
    if not exist "%LOCALAPPDATA%\Android\Sdk" (
        echo 错误：未检测到 Android SDK
        echo 请先安装 Android Studio：https://developer.android.com/studio
        pause
        exit /b 1
    )
    set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
)

echo [3/3] 构建 APK...
call gradlew.bat assembleDebug

if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy "app\build\outputs\apk\debug\app-debug.apk" "WebApp.apk"
    echo.
    echo ========================================
    echo   构建成功！
    echo   APK 文件：WebApp.apk
    echo ========================================
) else (
    echo.
    echo 构建失败，请检查错误信息
)

pause
