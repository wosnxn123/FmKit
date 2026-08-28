# FmKit

> 扫地不删除 —— 把地面掉落物扫进回收站，而不是让它们消失。

FmKit 是一个 Minecraft 服务端插件（Folia / Paper）。传统扫地插件周期性地 `remove()` 地面掉落物；FmKit 把同样的扫地流程改成**分流进双回收站**：

- **私人回收站**：你丢弃的、你死亡掉落的物品，扫进你自己的箱子，随时取回；
- **公共回收站**：无主物品（漏斗/机器吐出、农场产出、关闭回收的玩家掉的）进公共箱，先到先得；
- 到期自动流转：私人箱条目到期后按你本人的设置**转公共或销毁**，箱子永不膨胀。

## 特性

- **扫地节奏不变**：周期收集 + 全服倒计时广播（固定时间点必播），配置键兼容常见扫地插件，旧配置可平滑迁移；
- **死亡掉落保护**：死亡掉落自动打上死者标记，被扫到即进死者私人箱（离线也保留）；每位玩家一个统一回收开关，同时管丢弃与死亡；
- **双清单**：
  - 贵重清单（152 种内置）——只过滤"只提醒贵重"档的到期提醒，不影响收集；
  - 忽略清单——命中的物品不收集、留在原地自然消失（默认关闭）；
- **条目合并**：同物品同主人自动并组，堆叠上限内不拆条，每条带各自的到期倒计时（绿 → 黄 → 红）；
- **54 格 GUI**：翻页、三态排序、全部取回、右键转公共、Shift+右键两步销毁、到期预览、自动重绘；
- **每人自选**：回收开关 / 到期提醒档（关 / 只提醒贵重 / 全提醒）/ 到期去向（转公共 / 销毁），管理员可代设（离线可改）；
- **管理端**：清空公共（两步确认）、清空/打开任意玩家私人箱（离线可操作）、热重载、运行期扫地开关、立即扫地、改间隔、双清单增删、运营统计；
- **Folia 线程安全**：扫地逐实体走 region/entity scheduler，周期任务走 global region scheduler，GUI 动作回到玩家所在 region 线程；Paper 上同样可用；
- **全中文文案**：所有提示均为 MiniMessage，可在 `config.yml` 完整改写。

## 版本要求

| 项 | 要求 |
|---|---|
| 服务端 | Folia / Paper 1.20+（实测 Folia 26.1.2 与 26.2） |
| Java | 运行需 21；从源码构建需 JDK 25（见[构建](#构建)） |

## 安装

1. 将 `FmKit-1.2.0.jar` 放入服务端 `plugins/` 目录；
2. 启动服务端，首次运行自动生成 `plugins/FmKit/config.yml` 与 `bins/` 存档目录；
3. 游戏内输入 `/fmkit` 打开回收站。

> 从旧白名单版扫地插件升级：旧键 `sweep.whitelisted-items` / `whitelist-mode` / `whitelist-enabled` 会在首次启动自动迁移到双清单并删除，见 [配置指南](docs/配置指南.md#旧配置迁移)。

## 快速上手

| 你想 | 操作 |
|---|---|
| 打开回收站 | `/fmkit`（选择页）或 `/fmkit private`、`/fmkit public` |
| 关闭/开启自己的回收 | `/fmkit toggle [on\|off]`，或私人箱 GUI 槽 1 |
| 取回物品 | 私人箱内左键条目；槽 52 一键全部取回 |
| 不要了 | 右键条目立即转公共；Shift+右键两步销毁 |
| 改到期提醒 | 私人箱槽 2 三档循环（新玩家默认"只提醒贵重"） |
| 改到期去向 | 私人箱槽 6：转公共 ⇄ 自动销毁 |

`/fmkit` 可简写 `/fkt`，`/fmkitadmin` 可简写 `/fkta`。完整指令与权限见 [指令手册](docs/指令手册.md)，全部配置项见 [配置指南](docs/配置指南.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/配置指南.md](docs/配置指南.md) | `config.yml` 全量键位说明、默认值、旧配置迁移 |
| [docs/指令手册.md](docs/指令手册.md) | 玩家/管理指令、权限、GUI 操作 |
| [docs/构建与测试.md](docs/构建与测试.md) | 从源码构建、自动化测试套件（mineflayer 机器人） |
| [DESIGN.md](DESIGN.md) | 设计方案 v1.1（架构、数据模型、线程模型、边界规则） |

## 目录结构

```
├── src/main/java/dev/fm/kit/   # 插件源码（Java 21）
├── src/main/resources/         # plugin.yml、config.yml
├── pom.xml                     # 依赖声明（paper-api，provided）
├── build.ps1                   # 免 Maven 直编脚本（javac + jar）
├── DESIGN.md                   # 设计方案
└── docs/                       # 参考文档（指令手册、配置指南、构建与测试）
```

## 构建

`pom.xml` 是 CI 的构建入口（`mvn -B -ntp package`，见 `.github/workflows/build.yml`）；`build.ps1` 是本地免 Maven 的直编脚本（javac + jar，成功打印 `BUILD_OK`）。构建需要 **JDK 25**（paper-api 26.2 的字节码为 Java 25，低版本 javac 读不了它的 class 文件），产物仍以 `--release 21` 编译，服务端只需 Java 21：

| 构建脚本 | 产物 |
|---|---|
| `build.ps1` | `target/FmKit-1.2.0.jar` |

详细步骤见 [构建与测试](docs/构建与测试.md)。

## PlaceholderAPI 占位符

装上 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 后自动注册扩展 `fmkit`（软依赖，没装照常启用），TAB 的 tablist/侧边栏/BossBar 可直接使用：

| 占位符 | 含义 | 作用域 | 示例值 |
|---|---|---|---|
| `%fmkit_private_entries%` | 自己私人回收站的条目数 | 玩家 | `5` |
| `%fmkit_private_max%` | 私人回收站容量上限（配置，0 显示"不限"） | 玩家 | `54` |
| `%fmkit_private_collect%` | 自己的回收开关状态 | 玩家 | `开启` |
| `%fmkit_private_next_expiry%` | 私人箱最早一条的剩余保留时间（空箱显示"无"） | 玩家 | `2天3小时` |
| `%fmkit_public_entries%` | 公共回收站当前条目数 | 服务器 | `128` |
| `%fmkit_public_max%` | 公共回收站容量上限（配置） | 服务器 | `512` |
| `%fmkit_sweep_countdown%` | 距下次清扫的秒数（清扫关闭显示 `-`） | 服务器 | `42` |
| `%fmkit_sweep_countdown_formatted%` | 同上，`分:秒` 格式 | 服务器 | `4:32` |
| `%fmkit_sweep_enabled%` | 周期清扫是否在跑 | 服务器 | `开启` |
| `%fmkit_sweep_interval%` | 清扫周期（秒，配置） | 服务器 | `300` |
| `%fmkit_last_sweep_entries%` | 上轮清扫收走的堆叠数（未扫过显示 `-`） | 服务器 | `37` |
| `%fmkit_last_sweep_items%` | 上轮清扫收走的物品总个数 | 服务器 | `214` |

占位符在异步线程刷新是安全的：全部读内存中的并发容器 / `volatile` 快照，不碰世界实体、不做 IO（实现细节见 [DESIGN.md](DESIGN.md) 第 14 节）。

## 自动化测试

`fmkit_test.js` 是一次性自检套件：mineflayer 机器人进服 + RCON 驱动控制台，覆盖扫地分流、死亡掉落、双清单、GUI 交互、到期流转、提醒三档、管理指令、指令别名、分页、并发安全，输出 PASS/FAIL/FATAL（有失败退 1，FATAL 退 2）。套件不在本仓库：它住在作者本地与各插件仓库并列的 `bot/` 测试台里（Fm 系列插件共用一套 mineflayer + RCON 脚手架）。跑法见 [构建与测试](docs/构建与测试.md#自动化测试)。

## 许可证

[GPL-3.0](LICENSE)
