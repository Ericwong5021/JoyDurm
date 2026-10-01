# JoyDurm

[![Android APK](https://github.com/Ericwong5021/JoyDurm/actions/workflows/android.yml/badge.svg)](https://github.com/Ericwong5021/JoyDurm/actions/workflows/android.yml)

四个 Joy-Con + Android 手机，演奏一套可摆放在现实空间里的虚拟架子鼓。

**这是 v0.2.0 软件验证候选版本，尚未完成真实手机与四个 Joy-Con 的硬件验收。**
原版 Joy-Con 的 Android IMU 直连取决于手机驱动和权限；不能承诺所有普通
Android 手机都能直接读取运动数据。项目同时交付电脑 HIDAPI 桥接，可绕开
手机的原始 HID 限制。Switch 2 Joy-Con 暂不支持。

## 已实现

| 功能 | 实现 |
|---|---|
| Android App | Kotlin 原生界面，Android 8+，横屏、离线鼓垫演奏 |
| 手柄接入 | 系统蓝牙配对入口、Android 12+ 控制器 IMU 能力检测、raw HID 开发设备接入、LAN 桥接 |
| 四肢分配 | 四个独立设备绑定左手、右手、左脚、右脚，实时在线状态 |
| 陀螺仪校准 | 三秒静置零偏校准、运动样本拒绝、归中、参数持久化 |
| 打击识别 | 向下挥击峰值、回弹拒绝、防重复间隔、力度估算、可配置轴向与反向 |
| 鼓件选择 | 姿态分区、指向绑定、过远手势拒绝；不是绝对空间碰撞 |
| 左脚踩镲 | 归中后记录闭合／全开两点，自动铰链轴与时间平滑，快速闭镲 CHICK、失联独立止音 |
| 右脚地鼓 | 配置轴向与下踩符号，去重力有符号加速度，抬起不触发、下踩一次、防重复 |
| 空间布局 | 整组缩放、旋转、八个鼓件 XYZ 位置保存 |
| AR 相机 | SceneView + ARCore 摄像头、水平面识别、点击锚定、跟踪状态、重新摆放 |
| 3D 交互 | 独立镲片衰减摆动、鼓皮振动、地鼓槌动作、可触摸鼓件 |
| 音色 | 三套原创合成鼓、闭/半开/开踩镲、音量、WAV 导入、节拍器 |
| 模型素材 | 自带原创 CC0 GLB 鼓组与生成脚本，支持外部 GLB 导入 |
| 构建交付 | Gradle Wrapper、算法单测、Android Lint、GitHub Actions APK 及标签预发布 |

## 安装和第一次演奏

1. 在 [Actions](https://github.com/Ericwong5021/JoyDurm/actions/workflows/android.yml)
   打开一次成功的构建，下载 `JoyDurm-debug-*` artifact，解压安装 APK。
2. 首次进入会显示「首次使用」引导；之后可从「设备 → 首次使用步骤」再次打开。
   无需联网，可以点下方鼓垫或屏幕里的鼓件演奏。
3. 将手机固定在支架上。连接手柄后，在「设备」中分别绑定四肢。
4. 在「校准」逐个做静置校准；双手朝前、左脚平放，再归中。
5. 调整挥击方向、轴向和阈值。用「绑定一个鼓件方向」完成手势分区。
6. 左脚归中后，平放时点「记录闭镲点」，抬脚至全开时点「记录开镲点」。右脚先设置实际安装轴与下踩方向，再验证仅抬脚不响、一次下踩只响一次。
7. 在「布局」调整鼓组尺寸、旋转与鼓件位置；每次布局或 AR 锚点变化后，重新归中并绑定双手鼓件方向。
8. 点「AR 相机」，允许摄像头，扫描地面后点击摆放。手机不支持 ARCore
   时使用普通 3D 模式。

第一次验证用手机扬声器或有线耳机。蓝牙耳机的延迟可能盖过输入算法的表现。
断连、后台恢复、长采样间隙会进入「需要归中」；左脚需重新记录两点。可从「设备」导出能力报告及最近 IMU 样本。
这是原生 SceneView／ARCore，独立网页和 WebAR 尚未实现。
软件修复、未验项见 [F01–F21 关闭矩阵](docs/REVIEW-CLOSURE.md)。
详细的新手操作、断连与桥接关闭验收见 [真机验收步骤](docs/TESTING.md)。
现有自动测试与模拟器不能代替真实手机、四只 Joy-Con 和 AR 的实测。

## Joy-Con 接入路径

**系统传感器直连**：在 Android 蓝牙设置中配对原版 Joy-Con。长按手柄轨道上的
同步按钮进入配对。Android 12+ 如果厂商驱动通过 `InputDevice.sensorManager`
开放加速度计和陀螺仪，App 会直接读取。这与「能按按钮」是两件不同的事。
App 不会拿手机传感器冒充手柄传感器。

**原始 HID 直连**：开发 ROM/已配置权限的设备可在高级入口打开 `/dev/hidrawN`。
App 开启 IMU、读取 0x30 报告并尝试读取工厂校准。普通应用默认没有节点权限。
项目不会自动提权、修改 SELinux 或请求 root。此路径不是普通手机的普适方案。

**电脑桥接**：Windows / macOS / Linux 用开源 HIDAPI 读取四个 Joy-Con，将
运动数据通过 Wi-Fi 传到 App。需要电脑参与，但 Android 手机负责全部鼓组、
校准、演奏、音频和 AR。

```sh
cd tools/bridge
python3 -m venv .venv
# macOS/Linux
source .venv/bin/activate
# Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -r requirements.txt
python joydurm_bridge.py --list
```

电脑与手机连接同一个可信 Wi-Fi，关闭会隔离客户端的访客网络。
在手机系统 Wi-Fi 详情里查看手机的局域网 IPv4 地址（不要填写电脑 IP 或 `127.0.0.1`）。
App「设备」中保留默认 UDP 端口 `18185`，点击「开启桥接监听」，再「复制令牌」。
令牌为 16–128 个字母、数字、下划线或连字符；建议保留自动生成值。
命令行填写当前 App 里的同一值。
将下面 IP、令牌和 `--list` 输出的四个互不相同 ID 替换为实际值：

```sh
python joydurm_bridge.py --host 192.168.1.20 --token TOKEN_FROM_APP \
  --bind LEFT_HAND=LEFT_HAND_ID \
  --bind RIGHT_HAND=RIGHT_HAND_ID \
  --bind LEFT_FOOT=LEFT_FOOT_ID \
  --bind RIGHT_FOOT=RIGHT_FOOT_ID
```

也可点「复制电脑桥接命令」，它包含当前 IP、端口和令牌，仍需替换 ID1–ID4。
复制的命令从**项目根目录**运行；下方手写示例则从 `tools/bridge` 运行。
若显示多个本机 LAN 地址，在「手机 LAN 地址」输入框选择电脑能访问的
Wi-Fi IPv4，再复制命令；不要盲用 VPN 地址。
如果修改端口，桥接命令也加 `--port 实际端口`。
启动后回到 App「设备 → 连接诊断」，查看 UDP 监听端口、接受/拒绝计数，
逐个核对四只手柄的设备 ID、最后样本时间和持续增加的样本数。列表每秒刷新。
接受计数不增时检查 IP、端口和网络；拒绝计数增加时检查令牌或数据格式。配对成功
并不代表收到加速度/陀螺仪。确认各设备样本持续更新，再校准、归中及记录左脚两点。协议 v1 已停止接受，请同步升级电脑桥接。

停止电脑桥接用 `Ctrl+C`；App「设备 → 关闭桥接监听」会关闭 UDP 并保存关闭状态，
切到后台再回来也不会自动重新打开；需手动点击「开启桥接监听」恢复。

防火墙应允许电脑到手机的 UDP 18185。Linux 如遇 HID 节点权限问题，按
发行版方式为当前用户安装仅匹配 Nintendo VID 057e 的 udev 规则。macOS
如系统提示输入设备权限，应在系统设置允许当前 Python/终端进程。

没有硬件时可显式运行诊断模拟器，App 中设备名称会标为 `Diagnostic simulator`：

```sh
python joydurm_bridge.py --host 192.168.1.20 --token TOKEN_FROM_APP --simulate
```

## 构建 APK

安装 JDK 17、Android SDK 35 / Build Tools 35.0.0；在 Android Studio 打开根目录。
配置 `ANDROID_HOME` 或本机 `local.properties` 的 `sdk.dir`。

```sh
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
# Windows: gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。
桥接测试：`python3 -m unittest discover -s tools/bridge -v`。

CI 在 main push、PR、手动运行时执行单测、debug/release Lint 和两种 APK 构建，
上传可安装 debug APK；未配置签名时本地 release 输出为 unsigned APK，不可直接安装。
推送 `v*` 标签会创建 GitHub 预发布；无签名配置时交付 debug APK。
Debug 签名可能在不同构建环境间变化，更新安装遇签名不匹配时需先卸载。

正式签名配置以下 GitHub Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、
`KEY_ALIAS`、`KEY_PASSWORD`。标签构建会额外生成签名 release APK。
私钥只从 Secrets 解码到临时目录，不应提交到仓库。

## 开源组件

| 组件 | 用途 | 授权 |
|---|---|---|
| [SceneView 2.3.0](https://github.com/sceneview/sceneview) | 3D 场景、模型加载、点击、AR 生命周期 | Apache-2.0 |
| [Filament](https://github.com/google/filament) | PBR 渲染与 glTF | Apache-2.0 |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support/) | Activity / Lifecycle | Apache-2.0 |
| Android SoundPool | 原生多音轨短音色播放 | Android 平台 API |
| [HIDAPI](https://github.com/libusb/hidapi) / [Python hidapi](https://github.com/trezor/cython-hidapi) | 跨平台手柄桥接 | BSD 等上游授权 |
| [trimesh](https://github.com/mikedh/trimesh) | 离线生成 GLB | MIT |
| [Nintendo Switch Reverse Engineering](https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering) | 协议参考 | 按上游说明引用，未复制驱动源码 |

ARCore SDK / Google Play Services for AR 是 Google 的专有运行依赖；本项目不把它
称为开源。没有 Unity 许可证、云服务或收费 AR 平台依赖。

WAV 导入仅支持不超过 1 MB、最长 3 秒的 8–96 kHz 单/双声道 PCM16。
界面先显示加载中，SoundPool 实际解码成功才显示「音色已加载」并保存替换；
失败保留原音色。闭合踩镲敲击和闭镲 chick 都会截断正在播放的开镲尾音。

## 模型与 Sketchfab

默认模型为本项目原创：`app/src/main/assets/models/joydurm-kit.glb`，CC0。
它含独立鼓件、鼓皮、镲片和地鼓槌，可直接演奏与动画。

```sh
pip install -r tools/model-requirements.txt
python tools/generate_kit.py
```

可从 [Sketchfab](https://sketchfab.com/feed) 下载明确允许再分发的 CC0 / CC-BY
模型。保留作者、作品链接、许可证和修改说明。该网站下载可能需要本人登录；
仓库不抓取受限资源，也未打包未经授权的第三方模型。

外部 GLB 在 Blender 等工具中整理为米制、Y-up，八个独立父节点命名为：
`kick`、`snare`、`tom1`、`tom2`、`floor`、`hat`、`crash`、`ride`。
可选动画子节点：`kick_head` 等 `*_head`、`hat_cymbal`、`crash_cymbal`、
`ride_cymbal`、`beater`。所有贴图嵌入 GLB，模型不超过 20 MB。
通过「布局 → 导入 GLB 模型」加载。不符合节点结构的模型会明确拒绝。

## 工程结构

```text
app/src/main/java/ai/joydurm/
  core/     IMU 协议、零偏校准、姿态分区、击打状态机
  input/    Android 控制器 IMU、raw HID、UDP 输入
  audio/    SoundPool 音色、离线合成、WAV 替换
  render/   SceneView 鼓组、锚点、独立部件动画
  ui/       连接、校准、布局、音色、演奏界面与设置
tools/bridge/             HIDAPI 桥接与协议测试
tools/generate_kit.py     可重复生成原创 3D 鼓组
.github/workflows/        APK 打包与标签发布
docs/PROTOCOL.md          输入协议
docs/TESTING.md           真机验收清单与已知边界
```

代码 MIT；默认模型 CC0；第三方依赖按各自许可证。
