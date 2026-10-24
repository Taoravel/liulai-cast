# 本批方法与验证

采用 diagnosing-bugs：先在真实运行的旧APK上确认屏幕休眠时仍有3个广播，建立 check-standby.ps1 失败断言，再修复生命周期门控。优先处理关屏撤销、休眠中的恢复路径、休眠时启动服务；不是将应用界面未打开等同于待机。

必要回归：validation/standby-result.json 记录5个休眠／网络／唤醒场景。沿用既有播放逻辑和测试结果，不重复全量扫描或叠加审查。

滑动卡顿尚未取得 iPhone/抖音实机跟踪。发现接收端缺少动态尺寸预留，使用 H.264 横竖屏回放检查其能力准备与解码器复用。平台 getInputFormat 不回传这些可选配置提示，因此未将该结果作为性能断言。真实 H.264 回放连续三次横竖屏切换，解码器实例保持不变、输出方向正确。没有真实抖音复现或延迟测量，不宣称已经消除用户实机上的停顿。

Android API依据：
- https://developer.android.com/reference/android/os/PowerManager#isInteractive()
- https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_OFF
- https://developer.android.com/reference/android/media/MediaFormat#KEY_MAX_WIDTH
- https://developer.android.com/reference/android/media/MediaCodec#Adaptive_playback_support
- https://developer.android.com/reference/android/graphics/SurfaceTexture#setDefaultBufferSize(int,%20int)

源码不包含测试设备、临时录屏样本、发行私钥或口令。正式APK签名和对齐另行验证。

