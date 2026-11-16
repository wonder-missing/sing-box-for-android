# 更新安装与更新后服务恢复

本文记录两件事：安装更新时系统提示的来源，以及更新后如何恢复更新前的服务运行状态。

## 安装链路

1. `UpdateDialog`、`AppUpdateButton` 或 `AppSettingsScreen` 调用 `Vendor.downloadAndInstall`。
2. `ApkDownloader` 把 APK 下载到应用缓存。
3. `ApkInstaller.install` 先 `stopServiceIfRunning()`，再按安装方式分发。
4. release flavor 的默认方式 `SystemPackageInstaller.install` 用 `FileProvider.getUriForFile` 生成 `content://<package>.cache/...`，再发 `Intent.ACTION_VIEW`，MIME 为 `application/vnd.android.package-archive`，带 `FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`。
5. 系统安装器拿到的是应用缓存里的 URI，不是浏览器 `DownloadManager` 或文件管理器里的下载项。

## 关于「要不要扫描」

安装时的扫描提示来自 Android、手机厂商安全中心或 Google Play Protect，不是 WonderSing 的界面，也不在本仓库代码里。它的出现条件由系统、Play Protect 设置、安装来源和用户此前的选择决定。

我们能观察到的是安装来源差异：

- 应用内更新把 `content://` URI 直接交给系统安装器，没有经过浏览器或文件管理器。系统可以跳过浏览器下载链路里的扫描确认，直接进入更新确认页。
- 从飞书、浏览器或文件管理器打开同一个 APK，扫描确认通常属于那条链路的提示。
- 这是更新包，包名相同且签名证书相同，系统本来就把它当作已知应用的升级，而不是全新安装。

2026-11-15 安装 wonder.75 时没有出现扫描提示，最可能的原因是这次走的是应用内 Update 页，而不是浏览器下载。安装链路最近一次改动是 `f275cd0`（2026-11-04），把 `PackageInstaller` session 换成 `ACTION_VIEW` + FileProvider；wonder.73 到 75 都没有改安装代码。

不要把「没有出现扫描提示」当成安全保证。真正的边界是包名和签名校验；Play Protect 也可能在后台静默校验。需要复核时，用同一个 APK 分别从应用内 Update 和浏览器各装一次，记录提示差异和 Play Protect 设置，不要只看一次现象就下结论。

项目约定：测试更新体验走应用内 Update 页；不要为了让安装更「干净」而把 release APK 交给浏览器或第三方文件管理器。也不要自己加扫描对话框。

## 更新后恢复运行状态

更新会把旧进程杀掉，`ApkInstaller.stopServiceIfRunning()` 在安装前主动停掉服务。原来的停止路径会把 `started_by_user` 设为 false，所以 `BootReceiver` 收到 `ACTION_MY_PACKAGE_REPLACED` 时不会重启服务。

现在的流程：

1. `stopServiceIfRunning()` 发现 `filesDir/command.sock` 存在，说明服务在跑，先写 `restore_service_after_update=true`，`Settings.dataStore.flush()` 落盘，再 `BoxService.stop()`。
2. 安装完成后，Android 给应用发 `ACTION_MY_PACKAGE_REPLACED`，`BootReceiver` 在 `started_by_user` 或 `restore_service_after_update` 为 true 时启动服务。
3. 启动前清掉 restore 标记，避免下一次开机重复启动；有未读 crash report 时仍然不自动启动，并清掉两个标记。
4. 启动的是 `Settings.serviceClass()`，VPN 模式和代理模式都按更新前的模式恢复。
5. 用户如果手动启动服务，`MainActivity` 也会清掉 restore 标记。
6. wonder.86 起，`BootReceiver` 写 `service_restore` 遥测（action、两个标记、unread crash、start/skip/fail），`ApkInstaller` 写 `update_install`（prepare/install/error、socket 与 started_by_user），启动时如果 restore 标记还在，`MainActivity` 再做一次前台 fallback。这样即使 `MY_PACKAGE_REPLACED` 没到、被 Samsung 拦截或 start 被系统拒绝，下一次打开 App 也能补上或留下明确证据。restore 标记只在启动命令被接受后才清掉；启动失败会留给 fallback。

边界：

- 更新前服务没在跑，不会写标记，也不会重启。
- 安装被取消或失败时标记会留着，下一次开机恢复更新前的状态；用户可以再手动停掉。
- 这个恢复只对「由带此逻辑的版本发起的更新」生效。当前 75 发起的更新不会写标记；要从 76 更新到 77 才能在真机上验证。
- VPN 授权仍由系统 `VpnService.prepare` 保证；恢复启动不会绕过用户授权。

## 代码位置

- `app/src/other/java/io/nekohasekai/sfa/vendor/ApkInstaller.kt`
- `app/src/github/java/io/nekohasekai/sfa/vendor/SystemPackageInstaller.kt`
- `app/src/main/java/io/nekohasekai/sfa/bg/BootReceiver.kt`
- `app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt`（`startedByUser` 的写入和停止路径）
- `app/src/main/java/io/nekohasekai/sfa/database/Settings.kt`

## 验证

- 单元测试：`ServiceRestorePolicyTest` 覆盖 `started_by_user` 与 restore 标记的组合。
- 真机（2026-11-15，76→77）：76 会话里 `network_snapshot` 显示 `vpnActive=true`、`serviceMode=vpn`、core 在跑；走应用内 Update 安装 77 后，同一会话再次 `network_snapshot` 仍为 `vpnActive=true`、core 在跑，期间没有手动启动服务。恢复逻辑通过。
- 首次 75 到 76 的更新不会恢复，因为 75 还没有写标记；以后从 76 起的更新都适用。
