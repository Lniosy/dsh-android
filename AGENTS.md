# dsh

DeepSeek Harness 的安卓原生壳。桌面图标显示 **dsh**。官方 DSH 仍是 Node 内核；本仓库补的是原生权限层和启动器。

## 边界

- 不重写 Cordis / Web UI。
- 权限：Root 优先，Shizuku 兜底，统一走 `127.0.0.1:3091`。
- `targetSdk` 必须保持 28，否则 node 在应用私有目录无法 exec。

## 目录

| 路径 | 作用 |
|---|---|
| `app/` | Kotlin 原生壳、特权桥、引擎保活、WebView |
| `plugins/dsh-tool-android/` | DSH 工具插件，调本机特权桥 |
| `config/cordis.patch.yml` | 注入插件、关掉 Android 上不可用的沙箱 |
| `scripts/prepare-runtime.sh` | 组装 payload 目录 |
| `runtime/payload/` | 本地准备的 node + dshroot，不入库 |

## 权限桥

```
GET  /v1/status
POST /v1/exec   {"cmd":"...","timeoutMs":30000,"prefer":"ROOT|SHIZUKU"}
```

默认顺序：Root → Shizuku。都没有时工具返回引导，不假装成功。

## 命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

运行时（真机 arm64 / 雷电 x86_64 分开准备）：

```bash
bash scripts/prepare-runtime.sh
adb push runtime/payload /data/data/com.zsdsh.dsh/files/payload
```

## 雷电

社区现成 APK 的 node 是 aarch64，雷电会 `Exec format error`。本壳按 `Build.SUPPORTED_ABIS` 选 `runtime/<abi>/bin/node`。雷电必须放 x86_64 的 bionic node。
