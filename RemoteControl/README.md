# AlphaSync 内嵌遥控拍摄

基于 [hhb/sony-pmca-webcontrol](https://github.com/hhb/sony-pmca-webcontrol) 1.0.0，
Copyright (c) 2026 haibo，MIT 许可证。原许可证保存在
`../LICENSES/Sony-PMCA-WebControl-MIT.txt`。

## 使用

1. 手机端安装 v36，相机端安装 v13。这两个安装包沿用之前改进版的签名。
2. 在相机 AlphaSync 菜单选择“遥控拍摄”，保持相机屏幕处于这个模式。
3. 手机 AlphaSync 顶部选择“遥控”，填入相机 IP（默认端口 8080）。
4. 遥控页面提供原项目的取景、拍摄、曝光、对焦、放大和照片浏览等界面。
5. 手机返回后恢复预览和下载调度；相机 MENU 返回传图界面并释放 CameraEx。

在相机硬件上尚未验证。具体接口取决于机身固件及镜头能力。
主机端 Python 自动保存和 OpenFocus 景深合成没有移植到 Android。
遥控页面中直接下载的照片通过系统文件选择器保存；AlphaSync 文件页下载流程保持独立。

## 集成边界

- 手机通过带随机路径的 loopback HTTP 代理访问相机，RPC POST、SSE、JPEG/MJPEG
  均复用 AlphaSync 已选中的 `Network`，不绑定整个进程网络。
- 代理只接受当前会话的路径，目标固定为用户指定的相机 IPv4/端口，最多 8 个工作线程。
- 原项目的参数调试功能仍在网页内，未另建原生 RPC 调试界面。
- 相机使用独立 Activity 打开 CameraEx，原 AlphaSync 管理网络和 PTP 服务。
- 进入时不强制修改对焦、驱动和数码变焦模式；退出时取消待处理的参数目标。
- 取景限一个 MJPEG 客户端、约 15 fps，超过 2 MiB 的拍摄 JPEG 不驻留 Java 堆，
  下载改从 SD 卡流式读取；进入遥控时释放可重建的大预览缓存。

## 构建

使用 Node.js >= 22.12，在 `RemoteControl/web-client` 执行：

```sh
npm ci
npm run build:camera
```

该命令进行 TypeScript 检查、构建单文件网页，并更新
`Camera/app/src/main/assets/index.html.gzip`。网页资源已随源码保留，
仅构建 APK 时不需要重新安装前端依赖。

相机采用既有 Java 6 字节码 / SDK 10 / DX / v1-only 签名构建链。
`Camera/app/libs/webcontrol-stubs.jar` 仅供编译，来源为 OpenMemories-Framework
的 JitPack `master-SNAPSHOT`，SHA-256：
`6ffca47cf4218289728e28535664a78d4ea4f79ff6b48b4d96e3084ba9b33fb5`。
这些 SDK 桩不得作为运行时代码装入 APK。源接口参考：
ma1co/OpenMemories-Framework commit `f8df3504d8684433a331ee869e64f67cc8be9730`。

NanoHTTPD 2.1.1 源码随相机端保留，版权和 BSD 许可见对应源码头及
`../LICENSES/NanoHTTPD.txt`。其他归属见仓库 `NOTICE`。
