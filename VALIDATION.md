# 2.1.1 验证记录

验证日期：2026-10-25。基于 `1b7a8ac`，使用 Java 25 和本机缓存的 Gradle 9.7.1 离线完成 `clean build`，42 项行为检查通过。

新增检查覆盖无缩进的 YAML 正则列表、自动内部编号、任意项命中即 DROP、真实日志过滤、正则转义与锚定、无效正则/空白/非字符串/混合列表重载保留旧规则，以及成功重载替换旧规则。测试显式使用无文件 appender 的 Log4j 配置。

已部署到 Leaf 26.2 build 124 测试服：BOOTSTRAP 阶段成功加载原有 7 条规则；7 条原过滤文本逐条 dry-run 均返回 DROP，普通消息返回 PASS，`csf reload` 成功，实际计数已有命中。原配置原样保留，Skript 脚本加载无错误。

成品 `ConsoleSpamFixReborn-2.1.1.jar`，35,805 字节，SHA-256：`32df671581c21635abbff16edd171559b440b1b77bf9aa18d5528c69b3e53cdb`。部署与命令证据：`/server/codex/tasks/consolefix-regex-list-fix-20261025/verification.json` 和 `command-output.log`。

# 2.1.0-paper.1 历史验证记录

验证日期：2026-10-24。基线：上游 2.1.0，提交 `1123b718e2146383164d9a3e6d993d93f1d1b5bc`。

## 构建及行为检查

使用 JDK 25、Gradle 9.6.1，以及公开 Maven 依赖构建成功：

```sh
./gradlew clean build
```

`behaviorTest` 共 32 项检查通过，覆盖：

- 1.x/2.x 配置迁移、原文件备份、重复迁移及无效配置不落盘。
- logger、消息、级别、异常因果链、阶段及第一条 KEEP 规则。
- 无效规则重载保留旧配置，重复重载不叠加过滤器。
- Log4j 配置替换后重新挂载，禁用时保留其他过滤器。
- 8 线程共 16,000 个事件的过滤计数。
- 安装阶段、需重启的归档参数、错误启动配置的诊断。
- 原始异常归档、文件轮换及归档丢弃计数。

无网络构建路径也通过同一组检查。

## 独立服务器检查

运行 Leaf 26.2 build 124，Java 25。测试实例使用独立目录、世界和回环地址端口，未替换原服务器插件。

使用名为 Nova 的 Paper 测试插件，同时提供 bootstrap 和服务端事件探针。配置指定 ConsoleSpamFixReborn 在 Nova 之前加载。

| 检查 | 结果 |
| --- | --- |
| Paper loader 解析 Configurate 依赖 | 通过 |
| Nova bootstrap 中的目标消息被过滤，其他消息可见 | 通过 |
| 带 SocketTimeoutException 的事件整体过滤 | 通过 |
| 归档保留原始异常和命中规则 | 通过 |
| status、stats、test、reload 命令可执行 | 通过 |
| 重载后过滤计数与挂载正常 | 通过 |
| 禁用插件后相同消息恢复输出，服务器正常退出 | 通过 |

本记录不代表已验证其他服务端版本。更早的 Paperclip、扫描、重映射及本插件 loader 日志覆盖边界见 README。
