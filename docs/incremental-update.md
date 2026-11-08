# 增量更新评估（APK delta）

状态：评估与规划，未实施。2026-11-08。

目标：像刷新网页一样更新 WonderSing，只下载变化的部分；不损失功能，不拆散上游代码结构。

## 结论

1. **先做归档感知的 APK 差量补丁**（archive-aware patch），不做运行期下载 `.so`。
2. **暂不做 base + core 双 APK / split**。它能让冷安装拆开，但 Android 的更新安装必须提交完整 split 集，省下载要靠客户端自己缓存未变的 split，复杂度高于补丁，收益相近。
3. **「一次构建两个输出」本身不减少更新体积**。CI 已经做到只重编变动部分：`libbox.aar` 由 Test 在 `dev` 上构建并缓存，Release 直接复用；改 Kotlin 时不重编 Go。真正决定更新体积的是安装包怎么分发。
4. 运行期下载 Go 运行时库不可行：Android 10+ 禁止从可写数据目录 `dlopen`，native 库必须随 APK（或 split）安装。

## 实测数据

用已发布的 wonder.14 / wonder.15 / wonder.7 的 arm64 APK 在本机测：

| 项目 | 大小 |
|---|---|
| 全量 APK | 36.41 MB |
| 整包 zstd 补丁（wonder.14 → wonder.15） | 5.03 MB |
| 按未压缩 zip 条目做补丁（同一次更新，估算） | **2.14 MB** |
| 其中 `classes.dex` | 2.05 MB |
| 其中 `resources.arsc` + 全部 res XML | 0.07 MB |
| `libbox.so` 未压缩补丁（两次构建只差 8 字节时） | 10 KB |

wonder.14 → wonder.15 只改了 Kotlin/Compose 与版本资源，`libbox.so` 字节相同。归档感知补丁约 2.1 MB，是当前体积的 5.9%。

wonder.7 → wonder.14 的 `libbox.so` 有少量字节差异，整包 zstd 补丁仍有 5.06 MB；说明「Go 库有一点改动就整包重下」不是必然。真正的大版本 core 升级（大量函数地址变化）补丁会变大，保守估计 10–20 MB，但仍小于全量。

为什么不能到 8 MB：`libbox.so` 压缩后 26.92 MB（未压缩 78.60 MB），`classes.dex` 压缩后 4.97 MB，`resources.arsc` 2.21 MB。不损失功能时，冷安装 APK 现实下限约 30 MB。增量更新解决的是「每次更新下载多少」，不是「首次安装多大」。

## 方案对比

| 方案 | 代码版更新下载量 | 冷安装 | 客户端复杂度 | 结论 |
|---|---|---|---|---|
| 全量 APK（现状） | 36 MB | 36 MB | 无 | 保留为兜底 |
| 整包二进制补丁（bsdiff / zstd） | 5.0 MB | 36 MB | 中，需要 native patch 库或纯 Java 实现 | 可用，但不优先 |
| 归档感知补丁（archive-patcher 风格） | **2.1 MB** | 36 MB | 中，applier 是纯 Java | **推荐** |
| base APK + core split | 只下变化的 split | 36 MB（拆分装） | 高，多 APK 安装会话、split 版本与缓存管理 | 暂缓 |
| 运行期下载 `libbox.so` | 只下变化的库 | 可很小 | 高 | Android 10+ 不可行 |
| Play Feature Delivery / App Bundle | 平台增量 | 平台处理 | 低 | 只适用于 Play 渠道，GitHub 侧载用不上 |

## 推荐路线

### 阶段 0（已做，随下次发版验证）

- Release 打开 `isShrinkResources`。
- Release CI 传 `-PwonderLocales=en`，构建期只保留英文资源；不改 app 代码，便于合并上游。
- 下次发版记录 APK 实际体积，确认资源收缩和语言过滤的收益。

### 阶段 1：CI 生成补丁资产

- 每次 Release 构建完成后，下载前一版（或最近 3 版）的 arm64 APK，用归档感知补丁生成器产出：
  - `SFA-<newVersion>-from-<oldVersion>-arm64-v8a.patch`
  - 清单 `update-manifest.json`：新版本号、APK 大小、APK SHA-256、每个补丁的基线版本、补丁大小、补丁 SHA-256。
- 补丁与 APK 一起挂在同一个 GitHub Release 下；保留最近 3 个基线，旧补丁不跨版本复用。
- 生成器用 Google `archive-patcher` 的 `FileByFileV1` 格式：逐 zip 条目解压、做字节差、再压缩。它对 native 库尤其有效（实测未压缩 `libbox.so` 只差几字节时补丁 10 KB）。
- 生成器只在 CI 跑，不进 APK。

### 阶段 2：客户端差量下载

- 应用启动或首次检查更新时，把当前已安装 APK（`ApplicationInfo.sourceDir`）复制到应用私有目录，作为下一次补丁的基线；只保留一份，约 36 MB。
- 检查更新时，从 Release 资产里找 `from-<当前 versionName>` 的补丁；没有匹配补丁就回退全量 APK。
- 下载补丁 → 用纯 Java applier 重建新 APK → 校验 `update-manifest.json` 里的 SHA-256 → 交给现有 `PackageInstaller` 流程安装。
- 任一环节失败（补丁缺失、校验失败、磁盘不足、安装失败）自动回退到全量 APK，只回退一次。
- 所有网络调用带连接/读取超时，文件与流用 `use {}` 关闭；重建过程在 IO 线程，不阻塞 UI。

### 阶段 3：实测与收敛

- 连续 3 个版本记录真实补丁大小、生成耗时、失败率。
- 如果代码版补丁稳定在 2–4 MB，保留；如果某类更新补丁接近全量，直接发全量，不强行用补丁。

### 阶段 4（仅当冷安装体积成为问题时）

- 再做 base APK + core split：base 只含 Kotlin/Compose 与资源，core split 含 `libbox.so`。
- 安装时两个 APK 必须同版本、同签名，由 `PackageInstaller` 多 APK 会话一次提交；客户端可复用本地缓存的 core split，只下载变化的 base。
- 这会引入 split 命名、版本锁定、缓存失效、卸载残留等一整套问题，收益与阶段 2 相近，所以排在后面。

## 关键风险

- **签名**：补丁重建的是「已签名的上一版 → 已签名的下一版」的完整字节，客户端只校验 SHA-256；APK 签名由 Android 安装器验证。换签名密钥后旧补丁全部失效，必须回退全量。
- **补丁库维护**：`archive-patcher` 是纯 Java、格式稳定，但上游更新不活跃。可先作为依赖引入，若出现兼容问题再把 applier 源码收进仓库。
- **CI 时间**：每个补丁生成约 10–30 秒，最近 3 个基线可接受。
- **资产数量**：每个 Release 多 1 个 APK + 最多 3 个补丁 + 1 个清单；GitHub Release 资产上限远高于此。
- **更新检查解析**：现有 `GitHubReleaseFeed` 只解析 `.apk` 资产；需要扩展为同时识别 `.patch` 与清单，且不把补丁当 APK。
- **`isShrinkResources` 的资源 ID 变化**：补丁按字节重建，不受影响；但 R8 资源收缩后的资源名变化会让补丁稍大，属于可接受范围。

## 不做什么

- 不删调试符号（主人明确要求保留，便于排查问题）。
- 不在运行期下载并 `dlopen` `libbox.so`。
- 不为增量更新改动上游的模块结构或安装框架；补丁层放在 `update` 包内，保持上游合并面最小。
- 不在本阶段实现任何代码；等主人点头后再从阶段 1 开始。
