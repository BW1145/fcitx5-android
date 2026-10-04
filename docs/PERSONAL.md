# 小企鹅输入法定制版

适用于 arm64 安卓设备的输入法，基于 Fcitx5 Android，内置 Rime、雾凇拼音全拼方案和 Anthy 日语。

## 安装

下载本仓库 Releases 中的 `fcitx5-custom-arm64.apk` 并安装。打开“小企鹅输入法定制版”，按向导启用并选择键盘。首次启动自动部署词库；部署完成后即可输入。输入 `rq` 可以显示日期候选。覆盖安装沿用原签名和包名，保留用户配置。

点击待选拼音栏可以移动光标修改拼音；左右滑动候选栏查看更多词。长按删除键后向上滑动，有待选拼音时清空待选内容，否则清空正文光标前的文字。正文删除后，可通过三个点菜单恢复；恢复内容仅保留在当前编辑会话的内存中。

工具栏的书签图标打开常用内容面板，入口紧挨剪贴板。主动新增内容后，点击插入，长按编辑、删除或上移下移。常用内容随“导出用户数据”备份；光标控制在三个点菜单中。

日语首次使用自动加入输入法列表，语言切换键可选择 Anthy。使用罗马字输入，例如 `nihongo`，第一次按空格转换为 `日本語`，再按空格展开候选；选择候选后按回车提交。

包名为 `org.fcitx.fcitx5.android.bw1145`，可以与官方 Fcitx5 同时安装。应用使用离线输入，不申请联网权限，默认关闭剪贴板历史记录。

## 配置和更新

Rime 和词库随 APK 一起打包。雾凇源码固定在 `third_party/rime-ice` 子模块所记录的提交。首次启动建立全拼配置；后续启动保留已有配置和用户词库。

安卓系统仍要求用户亲自启用输入法并选择默认键盘。

## 构建

本仓库的 `Personal Custom APK` 工作流编译 arm64 安装包，并通过安卓模拟器验证中文、日语、拼音编辑、删除恢复和常用内容备份。构建成功并通过验证后，工作流把安装包发布到本仓库 Releases。

签名使用本仓库的 `PERSONAL_SIGNING_KEY` 和 `PERSONAL_SIGNING_PASSWORD` GitHub Actions secrets。更新安装包须沿用同一签名。

## 来源和许可

- Fcitx5 Android：https://github.com/fcitx5-android/fcitx5-android
- Fcitx5 Rime：https://github.com/fcitx/fcitx5-rime
- Rime：https://github.com/rime/librime
- 雾凇拼音：https://github.com/iDvel/rime-ice
- Anthy：https://github.com/fcitx/fcitx5-anthy

各组件保留原许可证；雾凇拼音的 GPL-3.0 许可证随词库打包。本仓库提供此安装包的对应源码和依赖提交。
