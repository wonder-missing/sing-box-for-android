# 本地单元测试（Mini）

状态：2026-11-15 在 clawdy-mini（M4 / 16 GB，macOS 26.6.2）装好并跑通。
范围只到 JVM 编译和 `testOtherDebugUnitTest`。签名、发布、飞书通知和 `libbox` 编译仍归 CI。

## 边界

- 本地跑编译检查和 JVM 单测；CI 仍是发布门。本地绿不代替 CI 绿。
- 没有模拟器，UI 交互和渲染需要真机确认。
- 本地不签 release，见 [`signing.md`](signing.md)。
- 本地不编 `libbox`；从 CI 的 artifact 取现成的 `libbox.aar`。

## 依赖清单

| 组件 | 版本 | 路径 / 说明 |
|---|---|---|
| OpenJDK | 17.0.21.0.3 | Homebrew formula `openjdk@17`；`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Android cmdline-tools | 15859902 | Homebrew cask `android-commandlinetools`；`sdkmanager` 在 `/opt/homebrew/bin/sdkmanager` |
| Android SDK root | - | `~/Library/Android/sdk`，不要用 brew 的默认 SDK root |
| platform-tools | 37.0.1 | 提供 `adb`，单元测试不依赖 |
| platforms;android-37.1 | 1 | App 的 `compileSdk=37`、`compileSdkMinor=1` |
| build-tools;37.0.0 | 37.0.0 | App |
| platforms;android-36 + build-tools;36.0.0 | 2 / 36.0.0 | `third_party/libxposed-api` 的 `compileSdk=36` |
| Gradle | 9.7.0 | `gradle/wrapper/gradle-wrapper.properties` 自动下载，不用装系统 Gradle |
| libbox | CI artifact | `app/libs/libbox.aar` 和 `libbox-legacy.aar`，两份内容相同，从 Test 运行下载 |

不装 Go、NDK、模拟器和系统 Gradle；不配置本地 release keystore。

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
  "platforms;android-36" "build-tools;36.0.0"
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

完成后跑一次全量，确认 433 个用例全绿：

```sh
./scripts/local-test.sh
```

实测：首次全量 4 分 18 秒（含 Gradle 和依赖下载）；清构建产物后重编加全量 40 秒；没有改动时 2–3 秒；单个测试类约 2 秒。全量为 433 个用例。

## 日常使用

```sh
./scripts/local-test.sh                                      # 全量
./scripts/local-test.sh --tests 'io.nekohasekai.sfa.agent.AskUserToolTest'
./scripts/local-test.sh --size                               # 只看磁盘占用
./scripts/local-test.sh --clean                              # 清构建产物和旧 daemon 日志
```

只编译不跑测试：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:compileOtherDebugKotlin --console=plain
```

## 磁盘预算与清理

2026-11-15 实测基线：

| 路径 | 占用 |
|---|---|
| `~/.gradle` | 1.6 GB |
| `~/Library/Android/sdk` | 704 MB |
| `app/build` | 103 MB |
| `app/libs` | 54 MB（两份 AAR） |
| Homebrew `openjdk@17` | 306 MB |

软上限：`~/.gradle` 3 GB，SDK 2 GB，`app/build` 1.5 GB，合计 6 GB。脚本在可用空间低于 5 GB 时拒绝运行。

- 每个开发批次结束跑一次 `--size`；`app/build` 超过 1.5 GB 或可用空间低于 10 GB 时跑 `--clean`。
- `./scripts/local-test.sh --clean` 会停 Gradle daemon、跑 `./gradlew clean`、删除根目录的 problems report 和 14 天前的 daemon 日志；不动依赖缓存，下次测试仍快。
- 升 Gradle wrapper 后，旧的 `~/.gradle/wrapper/dists/gradle-*` 不会自动删除；确认没有其他项目共用该版本后手动删旧目录。
- 依赖缓存 `~/.gradle/caches` 让 Gradle 自己淘汰闲置条目。只有确实需要时再删 `modules-2`，代价是重新下载约 1.5 GB。
- Android SDK 只保留当前需要的包。升 `compileSdk` 后先 `sdkmanager --list_installed`，再用 `sdkmanager --uninstall` 卸掉旧 platform 和 build-tools。AGP 在 license 已接受时会自动补包，升 SDK 后要复查。
- `app/libs` 只放当前两份 AAR；换 core 时直接覆盖，不留历史版本。
- `gh run download` 的临时目录用 `mktemp -d` 加 `trap` 清理。
- 如果本地跑过 `assemble`，装完或拷走 APK 后删 `app/build/outputs/apk`。
- CI artifact 的 14 天保留在 GitHub 侧，不占本机。

## 重建检查

换机器或重装系统后，按「从零重装」执行，再确认：

1. `./scripts/local-test.sh --size` 能打印占用，可用空间不低于 5 GB。
2. `./scripts/local-test.sh` 全量 433 个用例全绿。
3. 失败时依次查 `JAVA_HOME`、`ANDROID_HOME` 下的 platform 37.1/36、`app/libs` 两份 AAR。

本文件是本地工具链版本的落点；升级 JDK、SDK 包或 Gradle 时同步更新上面的表格和命令。
