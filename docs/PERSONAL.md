# 小企鹅雾凇

适用于 arm64 安卓设备的输入法，基于 Fcitx5 Android，内置 Rime 和雾凇拼音全拼方案。

## 安装

下载本仓库 Releases 中的 `fcitx5-ice-arm64.apk` 并安装。打开“小企鹅雾凇”，按向导启用并选择键盘。首次启动自动部署词库；部署完成后即可输入。输入 `rq` 可以显示日期候选。

包名为 `org.fcitx.fcitx5.android.bw1145`，可以与官方 Fcitx5 同时安装。应用使用离线输入，不申请联网权限，默认关闭剪贴板历史记录。

## 配置和更新

Rime 和词库随 APK 一起打包。雾凇源码固定在 `third_party/rime-ice` 子模块所记录的提交。首次启动建立全拼配置；后续启动保留已有配置和用户词库。

安卓系统仍要求用户亲自启用输入法并选择默认键盘。

## 构建

本仓库的 `Personal Ice APK` 工作流编译 arm64 安装包，并通过安卓模拟器验证首次启动、中文候选和日期功能。构建成功并通过验证后，工作流把安装包发布到本仓库 Releases。

签名使用本仓库的 `PERSONAL_SIGNING_KEY` 和 `PERSONAL_SIGNING_PASSWORD` GitHub Actions secrets。更新安装包须沿用同一签名。

## 来源和许可

- Fcitx5 Android：https://github.com/fcitx5-android/fcitx5-android
- Fcitx5 Rime：https://github.com/fcitx/fcitx5-rime
- Rime：https://github.com/rime/librime
- 雾凇拼音：https://github.com/iDvel/rime-ice

各组件保留原许可证；雾凇拼音的 GPL-3.0 许可证随词库打包。本仓库提供此安装包的对应源码和依赖提交。
