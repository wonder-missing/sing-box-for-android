# 本地单元测试（Mini）

状态：2026-11-21 在 clawdy-mini（M4 / 16 GB，macOS 26.6.2）装好 JVM 测试和无头 Android 模拟器。
范围是 JVM 编译/`testOtherDebugUnitTest`，以及 `scripts/e2e-emulator.sh` 的启动/UI 冒烟。签名、发布、飞书通知和 `libbox` 编译仍归 CI；VPN/服务行为仍需真机。

## 边界

- 本地跑编译检查和 JVM 单测；CI 仍是发布门。本地绿不代替 CI 绿。
- `scripts/e2e-emulator.sh` 在无头 arm64 模拟器上装 debug APK、启动 `MainActivity`、处理首次更新提示，并断言 Dashboard/Profiles。它只覆盖启动和 Compose 渲染链路，不覆盖 VPN、服务启停、Tailscale/VFS 和真实网络。模拟器用 API 36（当前 SDK 没有 API 37 system image）；app 的 `targetSdk=37` 仍可安装并启动。
- UI 交互和视觉仍以真机为准；模拟器用来提前发现启动崩溃、资源缺失和首屏断言回退。
- 本地不签 release，见 [`signing.md`](signing.md)。
- 本地不编 `libbox`；从 CI 的 artifact 取现成的 `libbox.aar`。

## 依赖清单

| 组件 | 版本 | 路径 / 说明 |
|---|---|---|
| OpenJDK | 17.0.21.0.3 | Homebrew formula `openjdk@17`；`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Android cmdline-tools | 15859902 + SDK-local `latest` 23.0.0 | Homebrew cask 提供 `sdkmanager`；模拟器 `avdmanager` 用 `$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager`，否则找不到 system image |
| Android SDK root | - | `~/Library/Android/sdk`，不要用 brew 的默认 SDK root |
| platform-tools | 37.0.1 | 提供 `adb`，单元测试不依赖 |
| platforms;android-37.1 | 1 | App 的 `compileSdk=37`、`compileSdkMinor=1` |
| build-tools;37.0.0 | 37.0.0 | App |
| platforms;android-36 + build-tools;36.0.0 | 2 / 36.0.0 | `third_party/libxposed-api` 的 `compileSdk=36` |
| Gradle | 9.7.0 | `gradle/wrapper/gradle-wrapper.properties` 自动下载，不用装系统 Gradle |
| Android Emulator | 37.3.10 | `$ANDROID_HOME/emulator/emulator`；HVF 加速 |
| System image | android-36 google_apis arm64-v8a | `$ANDROID_HOME/system-images/android-36/google_apis/arm64-v8a`；约 4.3 GB |
| libbox | CI artifact | `app/libs/libbox.aar` 和 `libbox-legacy.aar`，两份内容相同，从 Test 运行下载 |

不装 Go、NDK 和系统 Gradle；模拟器只装 arm64 API 36 一套；不配置本地 release keystore。

## 从零重装

前置：Homebrew 可用，GitHub CLI 已登录（`gh auth status`），仓库在 `~/workspace/WonderSing`。按顺序执行：

```sh
brew install openjdk@17
brew install --cask android-commandlinetools

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$ANDROID_HOME"

yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;android-37.1" "build-tools;37.0.0" \
  "platforms;android-36" "build-tools;36.0.0" \
  "cmdline-tools;latest" "emulator" \
  "system-images;android-36;google_apis;arm64-v8a"
```

`libbox.aar` 必须来自同一 `CORE_VERSION` 和 `core/patches/dial-attempt.patch` 的 Test 运行。
`version.properties` 刚改过、还没有成功的 Test，或 artifact 已过期时，先推送等 CI 产出 artifact：

```sh
RUN=$(gh run list -R wonder-missing/sing-box-for-android -w Test -L 10 \
  --json databaseId,conclusion \
  --jq '[.[] | select(.conclusion=="success")][0].databaseId')
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
gh run download "$RUN" -R wonder-missing/sing-box-for-android \
  -n libbox-android-arm64 -D "$TMP"
mkdir -p app/libs
cp "$TMP/libbox.aar" app/libs/libbox.aar
cp "$TMP/libbox.aar" app/libs/libbox-legacy.aar
```

完成后跑一次全量，确认 JVM 用例全绿：

```sh
./scripts/local-test.sh
```

实测：首次全量 4 分 18 秒（含 Gradle 和依赖下载）；清构建产物后重编加全量 40 秒；没有改动时 2–3 秒；单个测试类约 2 秒。当前全量为 735 个用例。

## 日常使用

```sh
./scripts/local-test.sh                                      # 全量
./scripts/local-test.sh --tests 'io.nekohasekai.sfa.agent.AskUserToolTest'
./scripts/local-test.sh --size                               # 只看磁盘占用
./scripts/local-test.sh --clean                              # 清构建产物和旧 daemon 日志
./scripts/e2e-emulator.sh                                    # 无头模拟器启动/UI 冒烟
./scripts/e2e-emulator.sh --no-build                         # 复用现有 debug APK
```

只编译不跑测试：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:compileOtherDebugKotlin --console=plain
```

## 磁盘预算与清理

2026-11-21 实测基线（Mini，228 GB 卷）：

| 路径 | 当前 | 预算 | 超预算动作 |
|---|---|---|---|
| `app/build` | 0.5–1.2 GB（累积） | 1.5 GB | 下次运行 `--clean` |
| `build/e2e-emulator` | 184 KB | 128 MB | 删除旧日志/截图 |
| AVD `wondersing-e2e` 数据 | 2.2 GB | 4 GB | 下次启动加 `-wipe-data` |
| AVD snapshots | 0 | 0 | 发现即删（始终 `-no-snapshot`） |
| `~/.gradle` | 3.4 GB | 6 GB | 只报告，不自动清 |
| Android SDK | 6.4 GB | 固定 | 只报告；升 SDK 时手动卸旧包 |
| `app/libs` | 54 MB | 固定 | 两份 AAR |

**日常清理只看自有足迹，不看整盘剩余空间。** `local-test.sh` 在 `app/build` 超过 1.5 GB 时自己跑 `--clean`；`e2e-emulator.sh` 还会删除超 128 MB 的 e2e 日志、清掉 AVD snapshots，并在 AVD 数据超过 4 GB 时用 `-wipe-data` 重置测试盘。Gradle 缓存、SDK/system image 和 `app/libs` 可复用，任何日常清理都不动。

**可用空间只用于下一轮安全余量，不是日常清理阈值。** `local-test.sh` 需要 3 GB 余量，`e2e-emulator.sh` 需要 4 GB（首次安装 SDK 包时 8 GB）。低于余量时先清可再生产物，再检查一次；仍不足就失败，并说明是整卷空间不够，而不是 build 太大。总盘容量不参与判断：10 TB 盘上即使只剩 100 GB，只要 `app/build` 没超预算就不清；`app/build` 超预算时即使还有 1 TB 也清。整卷只剩 1 GB 时，清掉 1.2 GB build 也不会假装能救整块盘，脚本直接报错并指出空间被别处占用。

预算都是 MB 整数，可用环境变量覆盖：`WONDERSING_BUILD_BUDGET_MB`（1536）、`WONDERSING_NEXT_RUN_RESERVE_MB`（3072）、`E2E_BUILD_BUDGET_MB`（1536）、`E2E_ARTIFACT_BUDGET_MB`（128）、`E2E_AVD_DATA_BUDGET_MB`（4096）、`E2E_NEXT_RUN_RESERVE_MB`（4096）、`E2E_SDK_INSTALL_RESERVE_MB`（8192）。

- 每个开发批次结束跑一次 `./scripts/local-test.sh --size`；它会打印 `app/build`、预算、可用空间和“是否会清”的结论。`./scripts/e2e-emulator.sh --size` 还会打印 e2e 产物、AVD 数据、snapshots 和内存。
- `./scripts/local-test.sh --clean` 会停 Gradle daemon、跑 `./gradlew clean`、删除根目录的 problems report 和 14 天前的 daemon 日志；不动依赖缓存，下次测试仍快。
- 升 Gradle wrapper 后，旧的 `~/.gradle/wrapper/dists/gradle-*` 不会自动删除；确认没有其他项目共用该版本后手动删旧目录。
- 依赖缓存 `~/.gradle/caches` 让 Gradle 自己淘汰闲置条目。只有确实需要时再删 `modules-2`，代价是重新下载约 1.5 GB。
- Android SDK 只保留当前需要的包。升 `compileSdk` 后先 `sdkmanager --list_installed`，再用 `sdkmanager --uninstall` 卸掉旧 platform 和 build-tools。AGP 在 license 已接受时会自动补包，升 SDK 后要复查。
- `app/libs` 只放当前两份 AAR；换 core 时直接覆盖，不留历史版本。
- `gh run download` 的临时目录用 `mktemp -d` 加 `trap` 清理。
- `e2e-emulator.sh` 默认会重建 APK；只有确定不再需要复用现有 APK 时，才手动删 `app/build/outputs/apk`。`--no-build` 会保留现有 APK，因此不会为超预算自动清 `app/build`。
- CI artifact 的 14 天保留在 GitHub 侧，不占本机。

## 重建检查

换机器或重装系统后，按「从零重装」执行，再确认：

1. `./scripts/local-test.sh --size` 和 `./scripts/e2e-emulator.sh --size` 显示各项在预算内，且可用空间不低于下一轮余量（local 3 GB / e2e 4 GB；首次装 SDK 8 GB）。
2. `./scripts/local-test.sh` 全量 JVM 用例全绿。
3. `./scripts/e2e-emulator.sh` 输出 `E2E OK`，并生成 `build/e2e-emulator/main.png`。
4. 失败时依次查 `JAVA_HOME`、`ANDROID_HOME` 下的 platform 37.1/36、`app/libs` 两份 AAR。

本文件是本地工具链版本的落点；升级 JDK、SDK 包或 Gradle 时同步更新上面的表格和命令。
