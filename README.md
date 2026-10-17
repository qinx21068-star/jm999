# 一根葱

> 干净、无广告的第三方 jmcomic2 客户端。仅作为访问接口的工具，不提供任何内容资源。

基于 Kotlin + Jetpack Compose + Material 3 的 Android 漫画阅读器，支持直连与后端两种模式，内置离线下载、内容屏蔽、多线路测速、阅读进度记忆等。

[下载最新版](https://github.com/qinx21068-star/jm999/releases/latest) · [MIT License](LICENSE)

---

## 下载安装

| 项目 | 说明 |
|---|---|
| 安装包 | `app-release.apk`（通用包，含 arm64-v8a / armeabi-v7a / x86 / x86_64） |
| 包名 | `com.yigencong`（可与旧版 `com.jmreader` 共存） |
| 系统要求 | Android 7.0（API 24）及以上 |
| 签名 | `yigencong` 正式密钥，APK Signature Scheme v2 |

校验用证书指纹见 [docs/SIGNING.md](docs/SIGNING.md)。

---

## 主要功能

- **阅读器**：上下滚动 / 左右翻页；双指连续缩放平移，放大后可单指拖动，竖向到边缘继续滚动，双击放大还原；沉浸全屏、音量键翻页、自动滚动、夜间滤镜、预加载下一章。
- **搜索**：关键词（最新 / 观看 / 点赞 / 图片数）、标签、作者、批量 JM 号、搜索模板、以图搜图。
- **书架**：本地收藏（分组 / 稍后再看）、站点收藏、浏览历史、阅读历史。
- **下载**：整本下载、按图片续传、外部目录（SAF）、仅 Wi-Fi / 仅充电、并发控制、失败原因提示。
- **内容屏蔽**：屏蔽标签 / 作者 / 名称关键词（繁简归一），可选完全隐藏或仅遮封面。
- **讨论区与评论区**：紧凑排版，长文与回复可展开，JM 编号可点击跳转。
- **网络**：域名池测速切换、自定义域名、图片质量档位、图片 CDN 锁定、网络诊断。
- **数据与隐私**：备份 / 恢复（含导入预览与快照）、应用锁、隐身模式、禁止截图、通知栏稍后再看。

---

## 技术栈

Kotlin 2.1.0 · Jetpack Compose（BOM 2024.12.01）+ Material 3 · MVVM + StateFlow · Navigation Compose · OkHttp（直连）/ Retrofit + Moshi（后端）· Coil · DataStore · Gradle 8.10.2 / AGP 8.7.3 · compileSdk 36

---

## 从源码构建

```bash
git clone https://github.com/qinx21068-star/jm999.git
cd jm999

# Android SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Debug
./gradlew :app:assembleDebug

# Release（R8 混淆 + 资源压缩，需签名配置）
./gradlew :app:assembleRelease
```

环境要求：JDK 17，Android SDK（compileSdk 36 / build-tools 36.0.0）。

**正式版签名**：仓库不保存任何密钥。CI 通过 4 个 Actions 密钥（`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`、`ANDROID_KEY_ALIAS`）签名，未配置时 Release 任务会失败，不会产出未签名包。密钥保管与恢复流程见 [docs/SIGNING.md](docs/SIGNING.md)。

---

## 项目结构

```
jm999/
├── app/src/main/java/com/jmreader/        # 源码包名 com.jmreader（applicationId 为 com.yigencong）
│   ├── MainActivity.kt                    # 入口：闪屏、免责声明、主题、应用锁
│   ├── core/                              # 日志、加解密、设备状态
│   ├── data/
│   │   ├── api/                           # 网络层（direct 直连 / 可选后端）
│   │   ├── download/                      # 下载管理器
│   │   ├── local/                         # DataStore 与本地存储
│   │   ├── repository/                    # 仓库层（Resource 模式）
│   │   └── AppContainer.kt                # 依赖注入
│   └── ui/
│       ├── components/                    # 通用组件（含卡片、评论）
│       ├── nav/                           # 导航
│       ├── theme/                         # Material 3 主题
│       └── screen/                        # 页面：home / search / favorites / history /
│                                          #       downloads / forum / comment / detail /
│                                          #       reader / settings / imagesearch /
│                                          #       author / lock / logs
├── backend/                               # 可选 Python 后端（jmcomic）
├── docs/SIGNING.md                        # 签名与密钥保管
└── gradle/libs.versions.toml              # 依赖版本目录
```

---

## 版本记录

### v1.3.1（2026-10-16）
- 讨论区与评论区统一紧凑排版，长文 / 回复可展开，作品引用合并为单个附件。
- 所有卡片显示 JM 号与真实发布日期，旧收藏后台补全元数据。
- 阅读器支持双指连续缩放平移、放大后单指拖动、竖向边缘续滚、双击还原。
- 随机入口移入「随机」页签；修复英文多词误判批量编号、自动搜索抢收键盘等问题。
- 包名改为 `com.yigencong`，换用 `yigencong` 签名（与旧版共存）。

### v1.3.0（2026-10-10）
- 阅读器沉浸式全屏；CI 自动构建 Debug APK 与单元测试，`v*` 标签产出 Release。
- 代理 / 图片 CDN / 下载并发设置提前应用；域名刷新保留自定义域名。

> 更早版本的变更见 [提交历史](https://github.com/qinx21068-star/jm999/commits/main)。

---

## ⚖️ 免责声明

**本项目仅供个人学习、技术交流和研究使用。**

1. 本项目为第三方开源客户端，不提供任何漫画内容资源，仅作为访问接口的工具。
2. 项目所有使用者必须遵守所在国家/地区的法律法规。
3. 本项目不鼓励、不支持任何侵犯版权的行为。请尊重原作者和版权方的合法权益，支持正版内容。
4. 项目作者不承担任何因使用本软件产生的法律责任、账号封禁、数据丢失或其他任何形式的后果。
5. 未成年人禁止使用本软件。
6. 使用本软件即表示您已阅读并同意本声明。如不同意，请立即停止使用并删除本软件。

首次启动会弹出上述声明，需阅读 5 秒后方可继续；也可在「设置 → 关于」随时查看。

---

## 贡献

欢迎提 Issue 或 PR。反馈时请附设备型号、Android 版本、App 版本，以及「设置 → 关于 → 查看日志」导出的日志。

**请勿在 Issue / PR 中粘贴密钥库、口令、Cookie 或任何凭据。**

## 许可证

[MIT License](LICENSE)
