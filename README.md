# BiliFilterHelper (B站净化助手 · 免Root无障碍版)

基于 Android 原生 `AccessibilityService`（无障碍服务）+ `WindowManager`（硬件加速悬浮遮罩）实现的**免 Root、不修改官方 APK** 的哔哩哔哩（B站）推广视频自动跳过与遮罩工具。

## ✨ 核心功能

1. **🚀 竖屏视频（Story模式）双模态极速跳过推广视频**
   - **引擎 1（无障碍节点树 0ms 直判）**：实时扫描 B 站竖屏视频流左下角推广标签（`创作推广`、`商业推广`、`花火推广`、`起飞推广` 等）、内联文本 Span 以及顶部 `广告内容...` 声明，命中即刻触发 `dispatchGesture` 向上划走。
   - **引擎 2（Android 11+ 免权限静默截图 + 双模态小火箭像素引擎）**：针对 B 站将小火箭图标直接内嵌在 `"XX万播放"` 文本末尾且不在无障碍节点树暴露文字的情况，通过 `"XX播放"` 节点纵坐标位移门控（`|Δtop| <= 14px` 即判落位）第一时间触发静默截图，支持 **模式 A（`[放] + [⌄] + [🚀]` 带简介展开箭头）** 与 **模式 B（`[播] + [放] + [🚀]` 无展开箭头直连）** 双模态无彩度亮白像素轮廓识别，并结合左上角空白象限校验与视频签名缓存实现毫秒级精准秒切、零误判。
2. **🛡️ 首页双列推荐流推广卡片实时滑动跟随遮罩**
   - 自动识别首页推荐流中的推广视频卡片并覆盖手势穿透遮罩（`TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_NOT_TOUCHABLE`）。
   - **三重实时滑动跟随引擎**：在手指拖动与列表惯性滑行全过程中绝不隐藏遮罩，通过卡片容器节点 `anchorNode.refresh()` 实时坐标直读 + `TYPE_VIEW_SCROLLED` 滚动增量 + `120Hz Choreographer` 速度外推与 `RecyclerView` 视口上下边缘动态裁剪（`clipBounds`），让遮罩紧贴推广卡片同步移动，并在滑动途中每 95ms 自动为新滑入屏幕的推广卡片追加遮罩。
3. **🔍 内置“嗅探节点”调试悬浮球与实时日志面板**
   - 开启后可在任意界面点击悬浮球，一键把当前屏幕所有可见控件的 `viewIdResourceName`、`text`、`contentDescription` 和坐标输出到面板日志中，方便适配任意版本的 B 站客户端。

## 🛠️ 构建与安装

### 方式一：使用内置 PowerShell 一键构建脚本（Windows）
无需启动 Android Studio，确保本地已安装 JDK 17 与 Android SDK（API 34），在项目根目录执行：
```powershell
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
```
编译并签名后的 APK 位于 `app/build/outputs/apk/debug/BiliFilterHelper-v1.0.0.apk`。

### 方式二：使用 Android Studio 打开
1. 打开 **Android Studio** -> **Open** -> 选择本工程目录。
2. 等待 Gradle Sync 完成后，点击 **Run 'app'** 或 **Build -> Build APK(s)**。

## 📄 开源协议

MIT License
