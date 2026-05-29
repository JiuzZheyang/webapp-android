# WebApp - 打开指定网页的安卓应用

## 功能
- 打开 App 后自动加载网页 http://192.168.1.121:12345/
- 支持返回键返回上一级
- 支持 JavaScript

## 构建方法

### 方法一：Android Studio（推荐）
1. 安装 [Android Studio](https://developer.android.com/studio)
2. 打开 Android Studio → File → Open
3. 选择 `webapp_build` 文件夹
4. 等待 Gradle 同步完成
5. 点击 Run ▶️ 或使用 Build → Build APK 生成 APK

### 方法二：命令行构建
确保已安装 Java JDK 17+ 和 Android SDK：

```bash
# 进入项目目录
cd webapp_build

# 构建 Debug APK
./gradlew assembleDebug

# APK 位于
# app/build/outputs/apk/debug/app-debug.apk
```

### 方法三：一键构建脚本（Windows）
双击运行 `build.bat`

### 方法四：使用 Docker
```bash
cd webapp_build
docker-compose up --build
```
APK 输出到 `WebApp.apk`

## 安装 APK
将生成的 `app-debug.apk` 传输到手机，安装即可。

⚠️ 首次安装可能需要开启「安装未知来源应用」权限。
