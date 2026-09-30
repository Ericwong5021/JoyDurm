# Validation and real-device acceptance

Build checks:

```sh
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
python3 -m unittest discover -s tools/bridge -v
```

CI compiles both debug and unsigned release variants and runs Lint for both.
It publishes an installable debug APK plus test/Lint reports. It does not claim to
test Bluetooth hardware or AR tracking. A successful APK build is not hardware
certification.

Pushing a `v*` tag creates a GitHub pre-release after the checks pass. Without
release secrets it contains the debug APK. Configure `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` to also publish a production
signed APK. Signing runs in a separate job that does not execute repository
code; pull requests and manual workflow runs cannot sign or publish a release.
The tag must point to reviewed code. Protect release tags and restrict who can
push them. A debug APK uses an ephemeral CI debug key, so later CI debug APKs
may require uninstalling the previous installation (which deletes its data).

## 新手真机操作：按顺序记录结果

下列是待执行的验收步骤，不是已经通过的硬件测试。记录手机型号、Android
版本、Joy-Con 型号、输入路径、APK 版本、耳机类型和每项实际结果。
尚无真实手机与四只 Joy-Con 的验收记录。

1. **先检查声音与画面。** 安装 APK，断开网络后点下方鼓垫。确认鼓组显示且
   不同鼓垫有声音。首次引导可从「设备 → 首次使用步骤」重新打开。
   使用手机扬声器或有线耳机；首次先保持普通 3D 模式。
2. **先验证一只手柄的输入。** 在「设备 → 打开蓝牙配对」用系统设置配对。
   返回 App 刷新连接状态。看到已配对名字只能证明蓝牙配对；必须有实时的
   加速度和陀螺仪数据才能校准和挥击。Android 12+ 也可能因驱动未开放 IMU
   而没有运动数据；此时使用下面的电脑桥接，不要反复校准无数据的设备。
3. **配置桥接。** 电脑与手机连同一可信 Wi-Fi。在手机 Wi-Fi 详情读取手机
   IPv4 地址。App「设备」设端口 `18185`、开启桥接监听并复制令牌。电脑执行
   README 中的安装命令和 `--list`，用手机 IP、同一令牌和四个不同设备 ID
   运行 `--bind` 命令。「复制电脑桥接命令」的命令从项目根目录运行，需替换
   ID1–ID4；多网卡时在「手机 LAN 地址」填电脑可达的手机 Wi-Fi 地址再复制。
   自定义端口时双方必须一致。
   路由器关闭客户端隔离，
   防火墙允许电脑到手机的该 UDP 端口；令牌不匹配时 App 不会接收输入。
4. **核对四肢。** 按设备 ID 区分四只同名手柄，依次绑定左手、右手、左脚、
   右脚。逐只轻轻转动，确认对应角色有新数据，其他角色不被替换。「连接诊断」每秒刷新，检查
   最后样本时间保持很小、样本数持续增加、接受计数增加；拒绝计数增加则检查
   令牌和格式。“实时 IMU”允许最多三秒无新样本，不能只靠该标记判断连续输入。
   演奏页
   四个角色应显示「实时数据」，而不是「等待数据」或「未绑定」。
5. **逐角色静置校准。** 让手柄完全静止，点该角色「静置校准 · 3 秒」并
   等待完成。再故意晃动重复校准，确认显示失败。稳定校准后双手朝前、左脚
   平放，逐角色归中。确认重启 App 后角色与成功校准仍保存。
6. **只调整一个动作。** 用「绑定一个鼓件方向」选择手部鼓件，指向预期位置
   完成绑定。单次向下挥击应只响一次，上行回弹不响；若不响，检查挥击轴、
   正负方向和阈值。分别完成左右手测试再测试快速交替。
7. **测试脚部。** 左脚平放时踩镲闭合，抬脚变开、放下产生 chick 并停止
   开镲尾音；右脚单次踩下只触发一次地鼓，静止时不响。用固定且不妨碍动作
   的方式佩戴手柄；先做小幅动作。
8. **测试断流与恢复。** 电脑按 `Ctrl+C` 停止桥接。确认角色变成等待数据、
   无旧击打继续响；再启动桥接，确认实时状态恢复且不补发断流前的击打。
   App 切到后台再回来，确认已启用的桥接恢复监听；随后点「设备 → 关闭桥接监听」，
   再切到后台和返回，确认保持关闭。关闭输入后触屏鼓垫仍能演奏。
9. **最后检查 AR。** 点「AR 相机」、授权相机并按系统提示安装 AR 服务，
   移动手机扫描地面，点击水平面摆放。手机移动时鼓组应保持锚定；重新摆放
   后旧位置不残留。记录不支持 ARCore 的手机是否仍可用普通 3D 模式。

若只有模拟器通过，第 2–9 项硬件结论仍应标为“未实测”。模拟器可以检查
UDP 连通和界面反馈，不能证明真实 IMU、蓝牙、踩击、端到端延迟或 AR 的表现。

## Phone acceptance

| Test | Required result |
|---|---|
| Offline start | 3D kit visible; touch pads play sound without network |
| Role binding | Four unique device IDs assigned, no duplicate role ownership |
| Direct IMU detection | Separate status for pairing vs live motion availability |
| Stationary calibration | Stable 3s accepted; movement rejected; bias persists |
| Direction mapping | Point/bind each target; intended downstroke selects it |
| Rebound rejection | One event per swing; upward rebound does not trigger |
| Rapid alternating hits | Left and right hand strokes retain independence |
| Kick | One event per stomp, quiet foot produces no events |
| Hi-hat | Flat closed, toe lifted open; closure produces chick and chokes open sample |
| Disconnection | Input becomes inactive; no old stroke fires on reconnect |
| AR plane placement | Kit remains fixed as camera moves; re-placement detaches old anchor |
| AR unsupported | Clear message and continued ordinary 3D playing |
| GLB import | Eight named roots required; per-piece animation still works |
| Custom WAV | Valid bounded PCM16 WAV reports success after decode; invalid/oversized files fail visibly and preserve the old sound |
| Lifecycle | Pausing closes inputs; resuming reopens enabled bridge |
| Latency | Measure controller motion → audible output, not only audio buffer delay |

For first tests use phone speaker or wired headphones. Bluetooth headphones can
dominate end-to-end latency. Record high-frame-rate video with the controller and
phone audio together; compare physical strike to audible onset. Repeat with all
four controllers active and AR running.

## Explicit hardware limitations

Original Joy-Con has no absolute position measurement and no magnetometer.
Yaw drifts; periodically recenter. The current complementary orientation filter
is intended for calibrated gesture sectors, not optical-quality six-DoF tracking.
Large compound rotations may need re-centering or per-target re-binding.

Direct Android motion works only if the phone's controller driver exposes IMU
sensors (Android 12+) or if the OS grants this application raw HID access.
Most stock phones may expose buttons only. The supplied desktop HIDAPI bridge is
the supported alternative; this repository does not claim universal no-root
direct Joy-Con IMU access. Device models tested with real Joy-Cons: **none yet**.

ARCore's runtime is proprietary even though the application and rendering stack
are open source. Phones need the appropriate Google Play Services for AR runtime.
The current release uses ARCore camera mode; a separate CameraX overlay mode is
not implemented. An unsupported phone uses 3D mode.
