# 小企鹅输入法定制版

适用于 arm64 安卓设备的输入法，基于 Fcitx5 Android，内置 Rime、雾凇拼音全拼方案和 Anthy 日语。

## 安装

下载本仓库 Releases 中的 `fcitx5-custom-arm64.apk` 并安装。打开“小企鹅输入法定制版”，按向导启用并选择键盘。首次启动自动部署词库；部署完成后即可输入。输入 `rq` 可以显示日期候选。覆盖安装沿用原签名和包名，保留用户配置。

点击待选拼音栏可以移动光标修改拼音；左右滑动候选栏查看更多词。长按删除键后向上滑动，有待选拼音时清空待选内容，否则清空正文光标前的文字。

工具栏的书签图标打开常用内容面板，入口紧挨剪贴板。主动新增内容后，点击插入，长按编辑、删除或上移下移。常用内容随“导出用户数据”备份；光标控制在三个点菜单中。

日语首次使用自动加入输入法列表，语言切换键可选择 Anthy。使用罗马字输入，例如 `nihongo`，输入假名时显示候选，按一次空格转换为 `日本語` 并显示汉字候选；选择候选后按回车提交。

包名为 `org.fcitx.fcitx5.android.bw1145`，可以与官方 Fcitx5 同时安装。普通输入使用本地词库和模型，默认关闭剪贴板历史记录。AI 仅在主动发送或测试连接时联网；语音测试调用设备开放的识别服务，并按需申请录音权限。

支持 Android 6 及以上系统。剪贴板拆词和搜索删除在 Android 6 上使用兼容实现。搜索期间的输入、选择、删除和宏均作用于搜索栏；选择结果后才会把结果插入原输入框。

外部插件需要使用本仓库构建的配套版本，其包名、发现入口和服务权限与定制版主程序对应。内置的 Rime 和 Anthy 无需另装插件。

发布页另外提供 `fcitx5-custom-chewing-arm64.apk`（注音）和 `fcitx5-custom-clipboard-filter.apk`（剪贴板过滤），按需要安装。

## 体验设置

设置首页可搜索用途并直接定位。按键触感、四套艺术主题、词库与历史备份、AI 助手和语音测试均有独立入口。

AI 在当前键盘内使用，设置中填写接口、Key、模型和固定提示词；会话保存在本机，用户主动选择是否将答案写入原应用。Key 在本机加密，换机后重新填写。全部数据备份包含会话、指令、词库历史和候选偏好。

长按候选可忘记或降低优先级，在词库页面恢复。长按空格可切换隐私模式；已有宏绑定继续优先执行。

## 配置和更新

Rime 和词库随 APK 一起打包。雾凇源码固定在 `third_party/rime-ice` 子模块所记录的提交。首次启动建立全拼配置；后续启动保留已有配置和用户词库。

安卓系统仍要求用户亲自启用输入法并选择默认键盘。

## 构建

本仓库的 `Personal Custom APK` 工作流编译 arm64 安装包及配套插件，通过 Android 15 模拟器验证中文、日语、拼音编辑、批量删除、搜索隔离、常用内容备份与插件连接，并在 Android 6 上执行搜索编辑、拆词、链接清理和 ZIP 导入回归。全部构建与测试通过后，工作流把安装包发布到本仓库 Releases。

签名使用本仓库的 `PERSONAL_SIGNING_KEY` 和 `PERSONAL_SIGNING_PASSWORD` GitHub Actions secrets。更新安装包须沿用同一签名。

工作流为每次发布设置递增的 `versionCode`（`3000 + 工作流运行编号`），并检查最终 APK 的包名、版本、架构和证书。手动构建可使用 `-PbuildVersionName=... -PbuildVersionCode=...`；ABI 拆分不会覆盖显式指定的版本号。主程序与插件共用 `-PmainApplicationId=...`，默认值为本定制版包名。

## 来源和许可

- Fcitx5 Android：https://github.com/fcitx5-android/fcitx5-android
- Fcitx5 Rime：https://github.com/fcitx/fcitx5-rime
- Rime：https://github.com/rime/librime
- 雾凇拼音：https://github.com/iDvel/rime-ice
- Anthy：https://github.com/fcitx/fcitx5-anthy

各组件保留原许可证；雾凇拼音的 GPL-3.0 许可证随词库打包。本仓库提供此安装包的对应源码和依赖提交。

