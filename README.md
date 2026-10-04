# DiskMark

Android 手机存储测速 App，仿 CrystalDiskMark。

| 测试 | 说明 |
|---|---|
| SEQ1M Q8T1 | 1 MiB 顺序读写，8 并发 |
| SEQ1M Q1T1 | 1 MiB 顺序读写，单并发 |
| RND4K Q32T1 | 4 KiB 随机读写，32 并发 |
| RND4K Q1T1 | 4 KiB 随机读写，单并发 |

- 测试引擎是 C 写的（`app/src/main/cpp/diskbench.c`），用 `O_DIRECT` 和 4K 对齐缓冲区绕过页缓存；若文件系统不支持 O_DIRECT，会退回带缓冲的 I/O，并在界面上标出
- 队列深度 QD 用 QD×T 个线程同步 `pread`/`pwrite` 模拟（Android 应用没有通用的异步 I/O 接口）
- 可调：测试次数（取最大值）、文件大小、每次时长、显示单位（MB/s、GB/s、IOPS、μs）
- 模拟器的数据会偏高，因为它的虚拟磁盘落在宿主机的页缓存上。要拿到可信的数据请用真机

## 工作流（Android CLI + Maestro）

```bash
android create empty-activity --name=DiskMark --output=./DiskMark   # 创建工程
android sdk install ndk/29.0.14206865 cmake/3.31.6                 # 安装 NDK
android emulator create medium_phone && android emulator start medium_phone
./scripts/e2e.sh                                                    # 单测 → 构建 → 安装 → Maestro
```

`scripts/e2e.sh` 依次执行：Gradle 单元测试并构建，`android run` 安装并启动，最后跑 `.maestro/` 下的全部 flow，JUnit 报告和截图输出到 `.maestro/out/`。

### Maestro 注意事项
- Compose 的 `testTag` 要设置 `testTagsAsResourceId = true` 才会成为 Maestro 能用的 `id`。DropdownMenu 弹出的是独立窗口，要在它上面再设置一次
- Button 的 id 在父节点上，文字在子节点上，所以选择器要写成 `{ text: "All", childOf: { id: btn_all } }`
- 测试运行时进度条一直在动，Maestro 点击后会一直等界面稳定。需要立刻检查中间状态时，给 `tapOn` 加 `waitToSettleTimeoutMs`
- `android layout` 会留一个 UiAutomation 进程常驻，它会导致 Maestro driver 启动超时。跑 Maestro 前先执行 `adb shell am force-stop com.android.cli.interact.instrumentation`
