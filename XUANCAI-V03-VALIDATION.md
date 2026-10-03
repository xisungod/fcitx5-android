# V03 词库验证

使用 Ubuntu 24.04 的 librime 1.10.0 对与插件相同的源数据组合进行部署验证。Android 插件的原生 Rime 为 1.12.0，此验证用于检查词库、配置依赖和候选输出，不代表 Android 实机测试。

- `rime_deployer --build <user> <shared> <build>` 返回 0。
- 雾凇、英文、拆字词典及原有朙月/笔画方案成功编译。
- `scripts/check-xuancai-rime.c` 使用公开 librime API 创建会话、选择雾凇、输入拼音并检查前页候选。
- `nihao` → 你好；`zhongguo` → 中国；`jisuanji` → 计算机；`hello` → hello，全部通过。
- 候选同时出现 👋、🇨🇳、🖥，验证 Emoji 转换数据已加载。

可用 `cc scripts/check-xuancai-rime.c -lrime -o check-rime` 编译，再传入三个目录运行。
