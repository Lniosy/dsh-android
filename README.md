# dsh

非官方 Android 客户端，用来在手机上跑 [DeepSeek Harness (DSH)](https://github.com/deepseek-ai/dsh)。

官方没有 Android 客户端。本仓库不重写 DSH：Kotlin 壳启动内嵌 Node，再用 WebView 打开官方 Web UI。

桌面图标名：**dsh**。包名：`com.zsdsh.dsh`。

## 它怎么跑

```
手机屏幕
  └── WebView  ← token →  127.0.0.1:3080  官方 dsh web
                              ▲
                              │  node --expose-internals bin.js web
                              │  --host 127.0.0.1 --port 3080 --no-open
                              │
应用私有目录 files/payload/
  ├── runtime/<abi>/bin/node     bionic Node（按 ABI 选）
  ├── dshroot/                   @deepseek-ai/dsh 内核
  └── dshhome/                   配置、凭证（本机写入，不进仓库）

特权桥  127.0.0.1:3091
  Root(su -c) 优先，Shizuku 兜底
```

| 层 | 做什么 |
|---|---|
| `app/` | Kotlin 壳：引擎保活、WebView、特权桥 |
| `plugins/dsh-tool-android/` | DSH 工具插件，调本机特权桥 |
| `config/cordis.patch.yml` | 关掉 Android 上不可用的沙箱，注入插件 |
| `scripts/` | 组装本地 payload（不进 git） |
| `runtime/` | 本机准备的 node / dshroot / 凭证，**不入库** |

## 硬限制

| 项 | 值 | 原因 |
|---|---|---|
| `targetSdk` | **必须 28** | ≥29 应用私有目录 noexec，内嵌 node 会 `EACCES` |
| `minSdk` | 24 | — |
| 特权 | Root 优先，Shizuku 兜底 | 没有特权时工具会说明，不假装成功 |
| 密钥 | 设备上自己填 | 仓库和 APK **都不带** API Key |

真机实测路径：arm64 + Magisk。雷电要用 x86_64 的 bionic node；社区 APK 里常见的是 aarch64，模拟器上会 `Exec format error`。

## 小白怎么用

1. 安装完整版 APK（里面已经带了 Node + DSH 内核）。
2. 打开 **dsh**，第一次会解压大约一分钟，然后自动打开界面。
3. 在顶栏粘贴自己的 DeepSeek API Key，点「保存并继续」。
4. 有 Root 最好；没有就装 Shizuku 并授权。

API Key **不会**打进 APK，也不进 git。每人用自己的。

## 自己编

需要 JDK 17、Android SDK。本机若已有 `runtime/payload/`，`./gradlew :app:assembleDebug` 会自动打进约 60MB 的 `payload.zip`，装完即可用。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

| APK 种类 | 条件 | 装完能否直接用 |
|---|---|---|
| 完整版 | 编译机能看到 `runtime/payload/` | 能。首次打开自动解压 |
| 瘦包 | 没有本地 payload | 不能。还要自己塞运行时 |

密钥永远不进 APK。

## payload 是什么

DSH 官方是电脑上的 Node 程序。手机要跑它，得自带一份能执行的运行时，我们叫它 **payload**：

```
payload/
  runtime/<abi>/bin/node     给 Android 用的 Node
  dshroot/                   @deepseek-ai/dsh 内核
  dshhome/                   配置和 Key（只在手机上，不进 APK）
```

完整版 APK 把前两样压成 `payload.zip` 打进去。打开 App 时解压到 `files/payload/`。Git 仓库里没有这份东西（太大，也避免误提交密钥）。

开发者本机准备：

```bash
bash scripts/prepare-runtime.sh
# 本机已 npm 安装 @deepseek-ai/dsh@0.1.5-rc.1 时：
# bash scripts/assemble-payload.sh
python3 scripts/pack-bundled-payload.py
```

没有完整版 APK 时，也可以把目录放到 `/sdcard/dsh/payload`，启动时会导入。

## 权限桥

只绑本机回环，不对外网开放。

```
GET  /v1/status
POST /v1/exec   {"cmd":"...","timeoutMs":30000,"prefer":"ROOT|SHIZUKU"}
```

顺序：Root → Shizuku。都没有时返回引导信息。

## 不在仓库里的东西

这些路径已被 `.gitignore` 挡住，开源仓库里不应出现：

- `runtime/payload/`、`runtime/work/`、本机 Node
- `**/.credentials.yaml`、`.env*`
- `local.properties`（本机 SDK 路径）
- `*.apk`、签名密钥

本项目与 DeepSeek 官方无隶属关系。DSH 内核版权归其各自作者。

## License

MIT
