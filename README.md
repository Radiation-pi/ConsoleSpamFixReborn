# ConsoleSpamFixReborn — Paper fork

Paper 专用分支，基于 [At87668/ConsoleSpamFixReborn 2.1.0](https://github.com/At87668/ConsoleSpamFixReborn/tree/2.1.0)。保留上游 Git 历史及作者来源。原项目基于 [CraftersLand 的 Console Spam Fix](https://www.spigotmc.org/resources/console-spam-fix.18410/)。

目标为 **Paper 26.2 / Java 25**，已在 Leaf 26.2 build 124 的独立实例验证。当前版本 `2.1.1`。不提供 Bukkit/Spigot、Folia、代理或 Mod 加载器版本。

## 行为

- 通过 `paper-plugin.yml` 和 `PluginBootstrap.bootstrap` 尽早安装过滤器，覆盖后续 bootstrap、认证初始化、插件加载和运行日志。
- 通过 Paper `PluginLoader` / `MavenLibraryResolver` 导入固定版本 Configurate 4.2.0，使用 Paper 默认 Maven Central 镜像。Log4j 使用服务器提供的版本，不打包或单独加载。
- 简写规则列表接受 Java 正则字符串；任意一条正则命中格式化消息就执行 DROP，自动生成内部规则编号，不用逐条写 ID 或 action。正则默认在消息任意位置查找，可用 `^`/`$` 锚定。
- 高级规则仍可使用 logger 名、级别、消息、异常因果链及 STARTUP/RUNNING 阶段组合匹配；高级规则中的首条匹配规则决定 DROP 或 KEEP。KEEP 不会覆盖其他 Log4j 过滤器的决定。
- 1.x 列表、2.x contains/regex 配置会保留原文件备份后迁移，保留旧规则和自定义字段；无效配置不会覆盖源文件。
- `/csf reload` 验证全部规则后一次替换；格式、未知字段、正则或空关键词错误会保留之前的完整规则集。
- 计数使用 LongAdder；过滤器在 Log4j 配置替换后重新挂载，插件禁用时移除自身过滤器和监听器，关闭自己的归档线程。
- 可选有界异步归档保留事件时间、logger、级别、线程、规则 ID 和完整异常；队列满时丢弃归档副本并计数，不阻塞日志等待磁盘。

移除了跨平台适配、旧版引擎、反射兼容层、遥测和 Shadow 打包。成品只包含本插件代码和配置；无 NMS、字节码修改或 Java agent。

## 安装与配置迁移

1. 停服，将原 ConsoleSpamFixReborn JAR 移出 `plugins/`，保存备份。不要同时安装两个版本。
2. 放入本分支编译的 JAR。保留 `plugins/ConsoleSpamFixReborn/config.yml`，首次启动会进行格式迁移，原始内容备份为 `config-pre-paper-<时间>.yml.bak`，其他旧字段保存在 `upstream-config` 节供审阅。
3. 初次安装使用默认配置时，所有示例规则均关闭，不会自动扩大过滤范围。按需要编写规则后执行 `/csf reload`。

迁移保留旧规则的匹配范围，不会自动把过宽的字符串规则变成精确规则。默认安装阶段为 BOOTSTRAP；可选择 ON_LOAD / ON_ENABLE，改动需重启。

```yaml
schema-version: 1
installation:
  stage: BOOTSTRAP
rules:
- xxx
- yyy
archive:
  enabled: false
```

列表项是 Java 正则，任意一项匹配就 DROP；匹配使用 `find()`，所以未加 `^`/`$` 的表达式可命中消息中的一段。要按普通文本匹配时，请转义正则元字符，例如句点写成 `\.`。包含冒号等 YAML 标点的表达式可用引号包住，例如 `- 'java\.net\.ConnectException: timeout'`。也可写 `rules: []` 表示不过滤。

旧的高级映射规则仍兼容；同一个 `rules` 列表不混用正则字符串和映射。高级规则的不同字段之间为 AND，单个 `*-any` 列表内为 OR。logger 名和异常类名精确匹配；异常遍历因果链，最多 64 层并防循环。省略 phases 时适用两个阶段。ServerLoadEvent 后进入 RUNNING。

所有 DROP 同时作用于根日志路径上的控制台和文件。归档位于插件目录 `filtered-logs/filtered.log`，达到阈值后轮换为 `.1` 等；单个超大事件可能超过文件阈值。归档不做网络输出。配置中的归档参数和安装阶段修改需重启；运行规则可热重载。内部管理诊断 logger 保持可见。

## 命令

通过 Paper BasicCommand 注册，权限为 `csf.admin`（默认 OP）：

| 命令 | 用途 |
| --- | --- |
| `/csf status` | 当前阶段、安装状态、规则数、总过滤数、归档错误与丢弃数 |
| `/csf reload` | 原子重载规则；失败时保留旧规则 |
| `/csf stats` | 启动以来总过滤数及最近一次重载以来的每条规则计数 |
| `/csf test <LEVEL> <logger> <message>` | 不修改计数、不输出事件的匹配预览；不提供 Throwable，因此带异常条件的规则不会在此命令中命中 |

## 配置插件加载顺序

编辑仓库根目录的 `load-order.properties`，重新构建并重启：

```properties
bootstrap.before=Nova,CraftEngine
bootstrap.after=
server.before=Nova,CraftEngine
server.after=
```

`before` 表示 ConsoleSpamFixReborn 在目标之前。构建时生成 Paper 的 `dependencies.bootstrap/server`；依赖均为可选且不共享插件 classpath。目标不存在时仍可启动。也可通过 `-PloadOrderFile=/path/custom.properties` 选择另一份文件。

普通运行 config 无法追溯改变 Paper 已经解析的依赖关系。本插件不会修改 Paper 内部依赖图或手动重排其他插件。循环加载关系需由管理员在构建配置中消除。

## 构建与测试

```sh
./gradlew clean build
```

需要 JDK 25；Gradle wrapper 9.6.1 带 SHA-256 校验。输出 `build/libs/ConsoleSpamFixReborn-2.1.1.jar`。`check` 包含基于真实 Log4j 的行为测试，不依赖 MockBukkit 或 JUnit。GitHub Actions 自动构建并保留 JAR artifact。

无网络环境可使用已安装服务器的库编译：

```sh
./gradlew -PserverLibraries=/path/to/server/libraries clean build
```

此参数仅影响本地编译/测试；运行时依然通过 Paper loader 加载 Configurate。

## 覆盖边界

bootstrap 之前的 Paperclip/Origami、LeafConfig、扫描/重映射和插件 loader 日志不能追回。JVM 自身输出、原始 stdout/stderr、独立 LoggerContext 或不传播到根 logger 的独立 appender 不保证被过滤。需要覆盖第一批日志时，应另行配置启动前生效的 Log4j 配置。

过滤隐藏信息，不修复错误。不要用全局 `Exception`、`at `、`Caused by` 关键词清理堆栈；参数化 Log4j 事件在完整格式化后匹配，带 Throwable 的事件可一起过滤。命中一条 DROP 规则后，插件会按线程在 2 秒窗口内继续隐藏标准 Java 堆栈续行（异常头、`at`、`Caused by`、`Suppressed` 和 `... N more`）；遇到普通消息会立即结束续行状态。

## 许可来源

上游仓库未附 LICENSE。本分支保留来源和历史，不替上游声明新的开放许可。
