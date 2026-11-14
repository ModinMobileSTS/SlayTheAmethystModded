# Android 差分基准工具

**选型结论：使用 HDiffPatch。** 默认以单线程、256 KiB step 的文件流式能力为
Delta Core 后续实现基础。工具仅用于实验，不代表已接入生产或完成安全验收。

## 文档与数据

- [选型结论与验收边界](DECISION.md)：正式决策、关键对比、剩余风险。
- [持久化测试数据](results/README.md)：可提交 CSV、来源与计量口径。
- [流式实验](STREAMING.md)：配置、资源限额、失败及缓存修正。
- [源码版本和许可](NATIVE_SOURCES.md)：固定 commit、编译与算法参数。
- [历史实验记录](EXPERIMENT_HISTORY.md)：按阶段保留的原始说明，不覆盖最终决策。

## 工具结构

| 入口 | 职责 |
| --- | --- |
| `prepare_native.py` | 显式准备固定版本原生源码；Gradle 不自动联网下载 |
| `prepare_corpus.py` | 准备 Git 文本/本地 JAR 版本对及来源摘要 |
| `run_phases.py` | 授权设备上的随机批测、分进程、温度门禁、日志与汇总 |
| `report.py` | 早期 smoke 日志的离线汇总 |
| `src/main/cpp/stream_bridge.cpp` | 文件流式适配、输出限制、xdelta 分配预算及源块 LRU |
| `src/main/java/.../PhaseInstrumentation.java` | prepare/generate/apply/limits 独立执行入口 |
| `src/main/java/.../BenchmarkInstrumentation.java` | 保留早期 smoke 与安全探针，非默认性能入口 |

旧候选及失败配置保留作回归/对照，不代表采用多引擎生产策略。无额外队列、生产更新入口
或设备自动安装任务。独立包名 `io.stamethyst.deltabench`，arm64-only，非 debuggable。
只写独立包私有 `files/runs/` 和 `files/phases/`，不读写用户模组/游戏文件。

## 构建与离线验证

```bash
python3 tools/delta-benchmark/prepare_native.py
python3 tools/delta-benchmark/prepare_corpus.py
./gradlew --offline :tools:delta-benchmark:testBenchmarkUnitTest :tools:delta-benchmark:assembleBenchmark
python3 -m unittest discover -s tools/delta-benchmark -p 'test_*.py'
```

JAR 样本须显式传入 `--jar-base`、`--jar-target`、`--label`，见历史记录。
不提交或默认分发第三方样本；包含样本的 APK 仅本地测试，分发前需审查许可/通知义务。
源码在忽略的 `build/native-vendor/`；样本在 `build/corpus-assets/`；临时日志在
`agent-tmp/`，可提交结果在 `results/`。没有将第三方 JAR 或设备地址写入结果文件。

本机离线缺少 lint 依赖，打包验证曾明确排除以下任务（不宣称 lint 通过）：
`lintVitalAnalyzeBenchmark`、`lintVitalReportBenchmark`、`lintVitalBenchmark`，均为
`:tools:delta-benchmark:` 下的任务。不要为了打包修改启动器发布签名规则。

## 真机执行

目标设备与独立 APK 安装须获得当次授权。连接只通过仓库 `sts` / `local_adb`，不使用
全局 adb、不提交端点、不清数据、不卸载启动器。先核对型号，再安装独立 APK；命令示例
见历史记录。不要在批次运行期间覆盖安装 APK。

推荐 HDiff 复现引擎：`native-hdiff-stream256-deflate6`。`run_phases.py --help` 查看
参数，显式传 `--engines`；默认列表仍保留五种历史对照。文件夹每批唯一，失败保留在
`failures.json`，不能只展示成功汇总。主机超时不保证设备进程已终止，下一批前需核实，
必要时仅停止独立基准包。私有历史工作集未自动回收，运行大批次前检查可用空间。
