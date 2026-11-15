# GGNext

GGNext 是一款面向 Android 平台的开源内存分析与调试工具，基于 Kotlin、Jetpack Compose 与 C++20 原生引擎构建。

项目源于在 Android 平台上对独立内存扫描引擎与现代化调试界面的实践重构，用于在本地开发测试、故障排查与安全研究场景中查看、检索和修改目标进程的运行期内存。

## 主要功能

- 支持 Root 直读与免 Root 共享 UID 两种工作模式
- 进程筛选与常规 Android 虚拟内存区域分类统计
- 精确数值、区间范围、模糊变化、联合结构、字节掩码与异或编码搜索
- 实时内存多类型浏览、地址跳转与数值锁定

## 构建环境

需要安装 JDK 17、Android SDK 与 Android NDK，在项目根目录执行：

```bash
./gradlew assembleDebug
```

构建产物默认输出至 `app/build/outputs/apk/debug/app-debug.apk`。

## 参考来源

- [AGameGuardian](https://github.com/AGameGuardian/agameguardian.github.io) —— 感谢其优秀的界面设计，本项目在界面布局与部分交互上从中获取了灵感。

## 开源协议与声明

本工具仅限用于合法授权的本地调试与技术研究，严禁用于任何违法或侵犯他人权益的用途，一切使用责任由使用者自行承担。

本项目严格遵循 [GNU General Public License v3.0](LICENSE) 协议开源。
