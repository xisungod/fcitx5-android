# 本地打字测试回放

这两个工具分别回放触点假设和汉字候选，不能互相替代。工具不会训练、修改输入法配置或用户词库，也不会上传报告。输入、输出和 `--work` 中间文件含私人文字、触点和候选，请放在仓库外。

## 纯触点 / 拼音回放

`replay.py` 读取 touch.3 的 `axiang-typing-test-v1` 或 touch.4 的兼容 `v2` 导出报告，编译并调用指定版本的生产 Kotlin `PinyinMultiPathTracker` 与公共拼音模型。目标文字、目标拼音、候选、校准标签全部从 JVM 输入中剔除，完成计算后才用于比较。

```sh
python3 scripts/typing-test-replay/replay.py \
  --report /private/AXiang-typing-test.json \
  --source-root /checkout/current \
  --baseline-root /checkout/touch3 \
  --java /path/to/jdk17/bin/java \
  --output /private/touch-replay.json \
  --work /private/replay-work
```

依赖 JDK 17+，以及 Gradle 缓存中的 Kotlin compiler、stdlib、Gson 2.11.0。先完成普通 Gradle 依赖解析；工具不联网安装依赖。可用 `--gradle-cache` 指定缓存。两个版本共用同一份模型文件，默认取 current 中的公共 TSV，也可指定 `--model` 固定版本。若某版新增纯 Kotlin 辅助文件，用 `--additional-source 文件.kt` 或 `--baseline-additional-source 文件.kt` 加入编译。

输出包含每次首轮 DOWN：原始拼音、完整备选、改动位置、评分、生产拒绝原因（该版本暴露时）、模型调用数、每键重复耗时和源文件哈希。`production_spatial_hypotheses` 通过只读反射取得该生产版本真正使用的空间假设，避免在工具中抄一套高斯常量；个性偏移保持空值。`single_key_explanation` 是独立单键解释，不是整串搜索的拒绝原因，也不修改串。旧版本未提供的拒绝原因标记 `not_exposed_by_source`，不能据最后一键的中心保护推断整串为什么被拒绝。

默认每句预热 3 次、计时 5 次。计时范围只有主机 JVM 中的 `recordTap`，不含 JSON、几何构建、独立单键解释、原生 Rime、排队和屏幕绘制。它只能比较搜索热点，不能承诺手机按键 P95。本报告只含首轮 DOWN，没有 MOVE/UP 或完整后续编辑；回放假设引擎按记录接受字母，不能用来证明触摸路由无错。

原串正确时出现其他备选是“建议发生”，不等于自动误改；它需要后续 native/UI 门控和用户选择。目标命中的备选数量也不是整机候选命中率。

## 独立候选回放

第二个工具只读取前一步冻结的原串与最终备选，不再读取触点或生成拼音。它复用 `scripts/touch-diagnostics/candidate_replay.py` 对原生 exporter 的输入保持、引擎版本与字段检查。

```sh
python3 scripts/typing-test-replay/candidate_replay.py \
  --touch-replay /private/touch-replay.json \
  --rime-binary /path/to/rime-candidates \
  --rime-data /path/to/compiled-rime-data \
  --output /private/candidate-replay.json
```

每个互异拼音串使用临时空用户目录，没有此前上屏上下文；相同串复用同一冻结结果。目标只在查询返回后核对。`phone_snapshot` 是原手机已有记录，不能把它和空上下文查询视为同一条件。这个 exporter 经完整 schema 翻译，与输入法中的只读备选查询及候选显示条件不同，所以该工具不能证明 `*` 实际出现、误纠率或手机延迟。原生 exporter / 数据路径和构建方法沿用旧诊断工具。

## 合成回归

```sh
python3 -m unittest discover -s scripts/typing-test-replay -p 'test_*.py'
python3 scripts/typing-test-replay/replay.py \
  --report scripts/typing-test-replay/synthetic-report.json \
  --output /private/synthetic-replay.json
```

公开 fixture 是人工生成的规则键盘中心触点，不来自任何人的导出报告。私有报告不能复制到 fixture、Git 或发行资产里。
