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

## 真机实测：OnePlus PLZ110（SM8850，Android 17）

设置为 1GiB、3 次、每次 3s，O_DIRECT 生效：

| 测试 | 读 MB/s | 读 IOPS | 写 MB/s | 写 IOPS |
|---|---:|---:|---:|---:|
| SEQ1M Q8T1 | 4240.65 | 4044 | 2626.09 | 2504 |
| SEQ1M Q1T1 | 2192.48 | 2091 | 3033.65 | 2893 |
| RND4K Q32T1 | 1122.38 | 274019 | 79.23 | 19342 |
| RND4K Q1T1 | 46.21 | 11282 | 90.95 | 22204 |

<img src="docs/oneplus_plz110.png" width="300">

## 工作流（全部用 Android CLI）

```bash
android create empty-activity --name=DiskMark --output=./DiskMark   # 创建工程
android sdk install ndk/29.0.14206865 cmake/3.31.6                 # 安装 NDK
android emulator create medium_phone && android emulator start medium_phone
./scripts/e2e.sh                                                    # 单测 → 构建 → 安装 → UI 测试
BENCH=1 DEVICE=<serial> ./scripts/e2e.sh                            # 再跑完整测速，从 logcat 打印结果
```

`scripts/e2e.sh` 依次执行：Gradle 单元测试并构建，`android run` 安装并启动，再运行 `scripts/uitest.py`。连着多台设备时默认选真机，可以用 `DEVICE=` 指定。

### UI 测试（`scripts/uitest.py`）
- `android layout` 读界面结构，`adb shell input tap` 点击，`android screen capture` 截图。除了 Android CLI 自带的 instrumentation，不需要在设备上装别的东西
- 按 Compose `testTag` 找元素。`testTagsAsResourceId = true` 会把它变成 resource-id；DropdownMenu 弹出的是独立窗口，要在它上面再设置一次
- 测试运行时进度条一直在动，`android layout` 默认会等界面静止，所以轮询状态时要加 `--no-idle`
- 包含 4 个冒烟测试：启动、单项测试、全部测试并切换单位、中途停止。加 `--bench` 或设 `BENCH=1` 会再跑一次完整测速
- JUnit 报告和截图输出到 `build/uitest/`；失败时会自动截一张 `FAIL_<test>.png`
- 只跑指定测试：`python3 scripts/uitest.py --device <serial> test_cancel`
