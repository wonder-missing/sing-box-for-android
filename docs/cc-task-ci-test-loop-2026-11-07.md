# Claude Code 任务：CI 单测闭环 + 落地崩溃修复（2026-11-07，主人已点头）

本机不装 JDK，单测反馈全靠 GitHub Actions。

1. 新增 `.github/workflows/test.yml`：on push 到 dev（paths 忽略 docs/**、*.md）+ workflow_dispatch。按 release.yml 现有做法准备 libbox.aar（可复用其步骤/缓存），跑 `./gradlew :app:testOtherDebugUnitTest`，失败时上传测试报告 artifact。不签名、不出包、不发 Release、不碰 secrets 里的签名 key。
2. 审一遍当前工作区的崩溃修复（brief `docs/cc-task-pending-log-crash-2026-11-07.md`），连同 test.yml 一起 commit，`git push origin dev`。
3. 闭环：`gh run list --branch dev --workflow test.yml -L 1` 找到这次 run，`gh run watch <id> --exit-status`；红了用 `gh run view <id> --log-failed` 看原因、修、再推，直到绿。同一问题修 5 轮仍红就停下写明原因。
4. 不要 git tag、不要强推、不要改版本号/签名。
5. 完成时回话：commit 列表、绿的 run 链接、测试数、还剩什么问题。
