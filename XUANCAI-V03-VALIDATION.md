# V03 词库验证

使用 Ubuntu 24.04 的 librime 1.10.0 对与插件相同的源数据组合进行部署验证。Android 插件的原生 Rime 为 1.12.0，此验证用于检查词库、配置依赖和候选输出，不代表 Android 实机测试。

- `rime_deployer --build <user> <shared> <build>` 返回 0。
- 雾凇、英文、拆字词典及原有朙月/笔画方案成功编译。
- `scripts/check-xuancai-rime.c` 使用公开 librime API 创建会话、选择雾凇、输入拼音并检查前页候选。
- `nihao` → 你好；`zhongguo` → 中国；`jisuanji` → 计算机；`hello` → hello，全部通过。
- 候选同时出现 👋、🇨🇳、🖥，验证 Emoji 转换数据已加载。

可用 `cc scripts/check-xuancai-rime.c -lrime -o check-rime` 编译，再传入三个目录运行。

## Android 成品验证

[构建 37114667012](https://github.com/xisungod/fcitx5-android/actions/runs/37114667012) 成功完成，构建源码提交 `50e288de5a54c8cc548ae622baa831b87b262a3e`。

- 主程序与插件成功编译，`:app:testDebugUnitTest` 通过，两个 APK 均通过 `apksigner verify --verbose`。
- 下载 Release 成品后验证 ZIP 完整性、arm64 ELF 架构、V03 包名与主程序/插件声明配对。
- 两个 APK 的签名证书一致，SHA-256 与发布清单一致。
- 插件内雾凇全部数据文件与固定上游版本逐一核对 SHA-256，默认方案为 `rime_ice`。
- 独立词库 ZIP 的数据、许可证和来源清单与插件匹配。
- 未进行目标手机上的布局、点击和帧率实测。

主程序 SHA-256：`434228944b60d98994b51a23e8d4e18293c5330020cf552ed3e5dde9ed8546b1`

Rime SHA-256：`42fafa7e333c47066b35a81299a9dc209556884905ab9a79842cdeaf896bf589`
