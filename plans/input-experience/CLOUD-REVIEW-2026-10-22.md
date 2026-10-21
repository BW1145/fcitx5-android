# 云端接手复核（本地验证完成，开始安卓验收）

接手提交：`94a5a2b85db865628346e2ab491b17433d86ee9d`。
对照范围：交接批次相对 `973f1dd`，以及本轮功能更新前的 `dee9943`。保留用户原定功能，优先检查实际输入、候选导航、词库和生命周期。

## 接手后补充修正

1. **键盘隐藏后继续输入**：`onFinishInputView` 增加了输入目标编号，却没有同步原生引擎。同一编辑器继续使用时，后续原生回调会被当成旧目标丢弃。现在在原有有序队列内先同步目标，再执行原有焦点处理。
2. **日语候选空格导航**：交接版本在转换开始后返回 Anthy 原始顺序，仍会选中已隐藏候选，也不遵循显示顺序。现在按同一显示映射推进预览，并处理分页、隐藏整页和循环；保留转换段，不把空格导航变成提交。
3. **词库恢复中关闭页面**：调用被取消后，回滚也可能因协程已取消而跳过。现在等待回滚完成再结束；恢复与回滚都失败时保留原始错误并附带回滚错误。每个词库页面持有独立连接，页面关闭后等待其任务收尾再断开。
4. **设置搜索恢复**：重新创建页面时，搜索框文字恢复，但结果仍可能是全部设置。现在在偏好状态恢复后重新按当前搜索词筛选。
5. **备份规则校验一致性**：原生 TSV 解析拒绝额外的第五列（含空尾列），与 Kotlin 四列规则格式一致。

## 验证情况

- `git diff --check`：通过。
- C++ 候选顺序回归：通过。覆盖降低优先级、隐藏当前候选、全局循环、分页局部索引、跳过隐藏整页及所有候选隐藏时终止。
- 原生规则回归：通过。使用固定 Fcitx 提交的真实 UTF-8 校验代码，覆盖方案/读音迁移、合并冲突保留当前选择、共享缓存、无效 UTF-8 和额外 TSV 列。
- 原生学习存储回归：通过。使用真实 LevelDB 存储，验证完整 `c/d/t` 与全局 tick 序列化、新 `learning/` 与旧 `db/` 合并路径、偏好合并，以及部分修改失败后经原始数据库、同步文件和偏好恢复的存储路径。Rime Levers 合并调用使用受控替身；此项不是实际 Rime 合并算法、Android 链接或设备验收。
- 固定版本的 Anthy、Rime 补丁生成：通过。候选分页接口与 UI 刷新路径已对照固定 Fcitx/Anthy 源码核验；生成的 Anthy 预览方法另用小型主机替身验证首次转换只执行一次、后续预览更新不提交或重置。
- 新增 Kotlin 回归：键盘隐藏后同编辑器继续输入、候选提交替换拼音后继续输入、词库取消回滚与双重错误、恢复前校验、设置搜索重建。首轮五组共 30 项检查通过 29 项。词库错误传播用例进一步发现协程异常恢复会丢弃附加在中间异常副本上的回滚错误；现在仅在回滚同时失败时创建保留原始原因的新异常，并保持取消语义。词库组最终 7/7 通过，输入目标组 6 项、界面组 7 项、输入事件组 3 项、工作负载组 7 项首轮全部通过。最终完整 JVM 回归：11 个测试类、45 项全部通过，失败/错误/跳过均为 0。
- 最终源码的应用 Kotlin/Java、JVM 测试源码及 Android 集成测试 Kotlin 源码编译通过。未生成 APK，未运行设备用例。
- 输入目标同步修正已由两个独立代码审查交叉检查。

## 尚需完成

- 按已获得的构建许可，统一验证 Android 集成、原有微信组合文本问题、重复打开菜单问题，以及轻量版覆盖升级。菜单缓存优化不等于已证明用户原来的卡死原因已解决。

本地验证阶段未生成 APK；2026-10-21 23:09 UTC 用户已明确批准推送本轮修复、完整构建和安卓验收，通过后发布轻量版 APK。

## 原生主机检查复现

仓库内新增/扩充的检查源码（本轮尚未提交）：

- `scripts/test_candidate_preferences.cpp`
- `scripts/test_preferred_candidate.cpp`
- `scripts/test_rime_learning_snapshot.cpp`

检查使用的头文件、固定上游源码及临时二进制保留在本机 `/tmp/fcitx-native-review/`，不纳入仓库。Fcitx 源码为交接固定提交 `1e00551899f9d0fa5418d899f468b6e421401adf`，Rime 头文件为 `1.17.0`；本机 LevelDB 使用现有 classic-level 随附 1.20 源码的临时副本，未修改 Android 依赖或锁定文件。Fcitx 的临时导出头仅将导出标记定义为空。以下命令只编译独立主机检查，不触发 Gradle、APK 或 CI。

```sh
NATIVE=/tmp/fcitx-native-review
FCITX=$NATIVE/fcitx/fcitx5/src/lib
RIME=$NATIVE/rime/librime/src
LEVELDB=$NATIVE/leveldb-test

g++ -std=c++17 -O2 -I"$FCITX" scripts/test_candidate_preferences.cpp \
  "$FCITX/fcitx-utils/cutf8.cpp" -o "$NATIVE/test_candidate_preferences"
"$NATIVE/test_candidate_preferences" "$NATIVE/candidate-data"

g++ -std=c++17 -O2 -Wall -Wextra -pedantic scripts/test_preferred_candidate.cpp \
  -o "$NATIVE/test_preferred_candidate"
"$NATIVE/test_preferred_candidate"

g++ -std=c++17 -O2 -Wall -Wextra -I"$RIME" -I"$LEVELDB/include" -I"$FCITX" \
  scripts/test_rime_learning_snapshot.cpp "$FCITX/fcitx-utils/cutf8.cpp" \
  "$LEVELDB/out-static/libleveldb.a" -pthread -o "$NATIVE/test_rime_learning_snapshot"
"$NATIVE/test_rime_learning_snapshot" "$NATIVE/learning-data"

python3 scripts/apply_personal_patches.py --component fcitx5-anthy \
  --source "$NATIVE/fcitx/fcitx5-anthy/src" --output app/build/dot-native-review/anthy
python3 scripts/apply_personal_patches.py --component fcitx5-rime \
  --source "$NATIVE/fcitx/fcitx5-rime/src" --output app/build/dot-native-review/rime
```

未把这些主机检查接入 Android 冒烟入口，避免混淆设备用例与主机替身、引入无关构建步骤。完整原生链接和真实 Android 候选交互仍等待用户允许新版构建后统一验证。

## 最终本地验证记录

2026-10-21 22:47:40 UTC：完整 JVM 测试 45/45 通过。22:48:26 UTC：最终 Android 测试源码编译通过。本轮可复用的隔离测试命令与日志位于云端 `/workspace/shared/fcitx-test-tools/`，`run-focused-tests.sh` 保留只运行主机检查的限制；`verified-kotlin-source-sha256.txt` 记录本轮测试代码校验值。用户已同意 SDK 许可，并于 23:09 UTC 批准本轮构建及验收后发布。

## 首轮安卓验收后的修正

GitHub Actions `39691790123`（提交 `4686053b`）完成正式 arm64 包构建、签名与离线内容检查。完整/轻量两种 Android 15 冒烟均遇到相同的两个问题，兼容性、升级与发布步骤按门槛跳过：

1. 主机候选测试未携带固定 Fcitx 的 UTF-8 头文件/实现。改为共用 `scripts/run_native_candidate_checks.sh`，显式编译真实 `cutf8.cpp`，仅在构建目录生成导出标记头，并运行候选顺序检查。缺少源码或编译失败仍使最终检查失败。
2. 搜索框自定义宏开始时存在组合文字，交接补丁将整个宏的清空动作都限为“只清组合文字”，导致 `ab` + 组合 `c` 后执行清空/文本/复制/粘贴宏得到 `abLOCAL!`。面板现在只为实际退格手势保留组合区范围，普通宏恢复清空自己的草稿。原输入框的行为未改动。

两种 Android 15 冒烟其余 32/33 项均通过；失败操作加入 `InputTargetTest`。修正后目标组 7/7、全量 JVM 46/46 通过。主机候选脚本已在固定上游源码、无 CMake 生成头、默认检出路径与含空格路径下验证通过。后续以修正提交的完整 CI 结果为准。
