# Gummy 外教实时翻译（Android）

面向外教课麦克风场景的 Android 应用：输入阿里云百炼 API Key，使用
`gummy-realtime-v1` 将英语实时识别并翻译为中文。界面采用纸质暖色视觉与
Android 原生 View，避免引入重量 UI 框架。

## 当前功能

- 暖色纸张风格的首页、课堂、课程记录和设置页面
- 定制书页声波品牌图标，支持 Android 自适应启动图标
- 统一衬线字体、卡片层级、按钮阴影与底部导航选中状态
- 设置页保存并在线测试 API Key
- 麦克风实时双语字幕，支持暂停、继续和结束课堂
- 当前字幕大字显示，已完成字幕自动形成上下文列表
- 使用 Android SQLite 自动保存课程和双语字幕
- 首页最近课程可直接进入详情
- 课程历史查看、单条删除、全部清空与系统分享
- 可选课堂期间屏幕常亮
- 原生麦克风环境降噪、回声消除与可选自动增益
- 应用内自适应 PCM 增益与防削波，改善远距离和轻声尾音识别
- 可调断句灵敏度与实时字幕字号
- Android 15 edge-to-edge 安全区域适配
- 按 Gummy `sentence_id` 和 `sentence_end` 合并异步识别/翻译结果
- 当前字幕限制为三行，避免长句挤压历史与控制区
- 字幕历史使用跟随模式，新句完成后平滑滚动到底部
- 退出课堂可选择保存或丢弃整次记录
- 独立课程详情页支持选中文本、复制全文、分享和删除
- 连接失败或没有字幕时不生成空课程记录
- 0.4 工程版通过前台麦克风服务和 CPU 唤醒保护增强锁屏、后台运行稳定性

## 环境

- Android 8.0+，当前 APK 仅打包 `arm64-v8a`
- Android 录音与联网权限
- 华北 2（北京）地域可用的百炼 API Key

## 构建

```powershell
./gradlew.bat assembleDebug
```

生成文件：`app/build/outputs/apk/debug/app-debug.apk`

## 安装

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次使用时输入 API Key，并允许麦克风权限。

## 说明

API Key 保存在 Android 应用私有的 SharedPreferences 中，不会写入源码或构建产物。
这是个人自用模式；若公开分发，应改为服务端签发临时 API Key。

阿里云 2026 年新版移动 SDK 的示例代码已将 Gummy 标注为 Deprecated/下线，但公开
Gummy 模型文档仍然保留该模型。若服务端返回模型不可用，需要将引擎替换为当前可用
的实时翻译模型。
