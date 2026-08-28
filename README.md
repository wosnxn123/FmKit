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

1. 将 `FmKit-1.1.2.jar` 放入服务端 `plugins/` 目录；
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

## 同仓库插件：FmShop

> 服务器商店 —— 自带整数分经济，菜单左键买、右键卖，差价写死（素行 `buy = 2 × sell`、附魔书 `buy = 6 × sell`） 断掉刷钱回路。

FmShop 与 FmKit 同源同风格（Folia 原生、全中文 MiniMessage 文案、无 Vault 依赖），自带 `long` 分经济（1 金币 = 100 分，不出现浮点）：`/fmshop`（别名 `fsh`）开菜单买卖，`/fmshopadmin`（别名 `fsa`）管钱、改价、查账本。价格表覆盖服务端 26.2 全部可交易物品 —— 1502 件商品、15 个分类：1374 行手写定价，外加 26.2 全部 43 种附魔按 `enchanted-books:` 价格曲线展开的 128 行附魔书（每种书每名玩家终身限售 1 本，卖出后才永久解锁购买）：非天然物品的买价按服务端真实配方递推（`1.25 ×` 最便宜合法配料买价 ÷ 产量，于是任意合成链恒定亏损），天然产出逐条手工定锚，18 种矿石方块 `buy = 0.00` 只回收不出售以堵死时运套利，`/fsa doctor` 再拿真实配方表复查。指令、权限、配置见 [FmShop/README.md](FmShop/README.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/配置指南.md](docs/配置指南.md) | `config.yml` 全量键位说明、默认值、旧配置迁移 |
| [docs/指令手册.md](docs/指令手册.md) | 玩家/管理指令、权限、GUI 操作 |
| [docs/构建与测试.md](docs/构建与测试.md) | 从源码构建、自动化测试套件（mineflayer 机器人） |
| [FmKit/DESIGN.md](FmKit/DESIGN.md) | 设计方案 v1.1（架构、数据模型、线程模型、边界规则） |
| [FmShop/README.md](FmShop/README.md) | FmShop 使用说明：指令、权限、`config.yml` 与 `prices.yml` |
| [FmShop/DESIGN.md](FmShop/DESIGN.md) | FmShop 设计方案（经济模型、定价推导、反套利、线程模型） |
| [CHANGELOG.md](CHANGELOG.md) | 两个插件各自的版本变更记录 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 构建环境、测试要求、提交与发布约定 |

## 目录结构

```
├── FmKit/          # 回收站插件源码（Java 21，dev.fm.kit）
│   ├── build.ps1   # 实际构建脚本（javac + jar，免 Maven）
│   ├── pom.xml     # 依赖声明（paper-api，provided）
│   └── DESIGN.md   # 设计方案
├── FmShop/         # 商店与经济插件源码（Java 21，dev.fm.shop）
│   ├── build.ps1   # 免 Maven 直编脚本（成功打印 BUILD_OK）
│   ├── pom.xml     # 依赖声明（paper-api 26.2，provided）
│   ├── README.md   # 使用说明
│   └── DESIGN.md   # 设计方案
├── bot/            # 自动化测试：mineflayer 机器人 + RCON 驱动（FmShop 实测 72/72 断言）
└── docs/           # 跨插件参考文档（指令手册、配置指南、构建与测试）
```

## 构建

两个插件各自独立构建。`pom.xml` 是 CI 的构建入口（`mvn -B -ntp package`，见 `.github/workflows/build.yml`）；`build.ps1` 是本地免 Maven 的直编脚本（javac + jar，成功打印 `BUILD_OK`）。构建需要 **JDK 25**（paper-api 26.2 的字节码为 Java 25，低版本 javac 读不了它的 class 文件），产物仍以 `--release 21` 编译，服务端只需 Java 21：

| 插件 | 构建脚本 | 产物 |
|---|---|---|
| FmKit | `FmKit/build.ps1` | `FmKit/target/FmKit-1.1.2.jar` |
| FmShop | `FmShop/build.ps1` | `FmShop/target/FmShop-1.0.0.jar` |

详细步骤见 [构建与测试](docs/构建与测试.md)。

## 自动化测试

`bot/fmkit_test.js` 是一次性自检套件：mineflayer 机器人进服 + RCON 驱动控制台，覆盖扫地分流、死亡掉落、双清单、GUI 交互、到期流转、提醒三档、管理指令、指令别名、分页、并发安全，输出 PASS/FAIL/FATAL（有失败退 1，FATAL 退 2）。跑法见 [构建与测试](docs/构建与测试.md#自动化测试)。

`bot/fmshop_test.js` 是 FmShop 的实服端到端回归，72 条断言：mineflayer 操作商店菜单买卖、翻页（`装饰` 分类 221 件商品共 5 页）、一键回收预览，RCON 下管理指令，断言余额、每日限额、行情浮动、账本文件与 `/fsa reload` 后的持久性，并覆盖附魔书规则（128 行全为附魔书、6 倍差价、未解锁拦截、终身限售、精确匹配卖出、`/fsa price` 拒改生成行等）。

## 许可证

[GPL-3.0](LICENSE)
