# 榴来投屏

Android 电视、投影仪与盒子的 AirPlay / DLNA / 安卓屏幕接收工具，基于 [F-e-n-y-x/airplay-dlna-receiver](https://github.com/F-e-n-y-x/airplay-dlna-receiver) 和 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) 定制。与“榴来TV”影视应用是两个独立项目。

## 下载与安装

[榴来投屏 1.0.4 发布页](https://github.com/Taoravel/liulai-cast/releases/tag/v1.0.4)

Android 8.0 以上，包含 ARM32 / ARM64 / x86_64。沿用既有发行签名，可覆盖安装。私有签名密钥和口令不公开。

## 本次改动

- 待机／屏幕关闭时撤销 AirPlay、RAOP、安卓屏幕接收的 mDNS 广播，暂停 DLNA，并释放 Wi-Fi 与服务保活锁。
- 亮屏后恢复发现；服务在待机中启动、网络重连或重试时不会重新广播。
- 对支持自适应解码且尺寸范围合适的设备，预留横竖屏切换尺寸；设备拒绝自适应配置时回退兼容配置。
- 分辨率信息成对更新，运行中不随显示比例通知修改解码 SurfaceTexture 的默认缓冲尺寸。保留完整压缩参考帧和既有固定显示窗口。

## 验证与边界

待机问题已在 Android 15 模拟器复现：旧版休眠时仍有3个广播注册。修复后验证休眠冷启动、亮屏恢复、再次休眠、休眠中网络重连、再次亮屏；休眠中注册为0、DLNA端口关闭、服务保活锁释放，亮屏恢复3个注册。

横竖屏采用真实 H.264 样本回放检查，详见 [验证记录](TESTING.md)。没有真实 iPhone 与抖音端到端测量，不能确认每次卡顿均由横竖屏引起，也不宣称消除所有滑动停顿。

iPhone或其他发送端可能短暂缓存旧设备名称；注销本机广播不能保证所有客户端的旧列表瞬间清空。电视／投影固件必须向 Android 报告正确的屏幕与待机状态，外接盒子不一定能感知物理电视是否关机。

## 构建与许可

构建环境、第三方源码和既有说明见 [BUILDING.md](BUILDING.md)、[上游README](README.upstream.md) 和 [LICENSE](LICENSE)。使用 JDK17、Gradle8.11.1、Android SDK35、NDK27.0.12077973。构建路径请使用无空格目录。

```powershell
.\gradlew.bat :app:assembleRelease
```

配置本机 `local.properties` 的 SDK 路径。源码保留原有组件和许可证，并附原有预编译 OpenSSL 归档及对应源码；没有发布本机配置或私人签名。
