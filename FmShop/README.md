# FmShop

> 服务器商店 —— 自带整数分经济，菜单左键买、右键卖，价格表全表 `buy = 2 × sell` 断掉刷钱回路。

FmShop 是 Folia / Paper 服务端的服务器商店插件，自带经济，不依赖 Vault。金额全程以 `long` 分存储（1 金币 = 100 分），除配置读取与展示外不出现浮点，因此不存在累积误差。玩家用 `/fmshop` 打开菜单买卖，也可以用指令直接成交；两条路径共用同一套交易引擎与同一份文案，读起来完全一致。默认价格表 129 件商品、7 个分类，全表遵守买价等于卖价两倍的差价约定，`/fsa doctor` 再拿服务端真实配方表复查一遍，堵死「买材料、合成、卖成品」的刷钱路径。设计细节、完整配置键表与线程模型见 [DESIGN.md](DESIGN.md)。

## 特性

- **自带经济**：金额全程 `long` 分，上限 `Money.MAX = Long.MAX_VALUE / 4`，加法与乘法溢出饱和而不回绕；无 Vault 依赖，余额实现以 `FmEconomy` 接口（`balance` / `has` / `withdraw` / `deposit` / `set` / `transfer` / `format` / `currency`）注册为 Bukkit 服务，其它插件经 ServicesManager 查找即可调用；
- **图形化商店**：买与卖挂在同一个商品图标的左、右键上，玩家不用找模式开关；Shift+右键立即卖出全部持有；确认界面按 ±1 / ±16 / ±64 调量，预览写明单价、小计、手续费、应付或可得，以及数量上限及其原因；一键回收在成交前列出物品种类、合计、手续费与预计可得，施加与交易引擎完全相同的夹取，且只回收未附魔、未改名、未损伤的素物品；菜单按 `gui.auto-refresh-seconds` 周期重绘，挂着菜单跨过一次价格波动时按新价成交，不兑现旧数字；
- **反套利价格表**：全表 `buy = 2 × sell`；买卖共用同一个行情倍率，任何倍率下差价比例不变，动态定价永远打不开刷钱回路；`/fsa doctor` 用服务端真实配方表复查。配料的买价已是其卖价的两倍，任何配方产物都不可能比配料更值钱；方块（9 合 1）按 9× 精确定价，熔炼保证产物卖价不高于原料买价；复查覆盖有形 / 无形 / 熔炼 / 切石四类配方，存在正利润的路径记为错误；
- **可选动态行情**：默认关闭；开启后买入抬价、卖出压价，倍率夹在 `floor-percent` 与 `ceil-percent` 之间，按 `recover-bp-per-hour` 在读取时惰性回归基准，没有定时任务；
- **每日限额与日切**：每件商品可单独设 `daily-buy` / `daily-sell`，计数在 `limits.reset-hour`（默认 4 点，服务器本地时间）翻页；被限额夹住的订单按实际可成交量成交，并如实上报剩余量与重置倒计时，而不是整单拒绝；
- **手续费与税池**：买 / 卖 / 转账三条路径各有独立费率；`fees.destination` 决定手续费凭空销毁（`void`，通缩）还是计入税池（`tax-pool`）；税池落在独立的 `tax.yml`，`/fsa tax` 查看、`/fsa tax take` 发放，不必碰玩家数据；持有 `fmshop.fee.exempt` 的玩家免收；
- **审计日志与大额提醒**：每笔买 / 卖 / 转账与每次管理端改钱都追加进 `audit/<yyyy-MM-dd>.log`，管理操作额外记录操作者名字；净额达到 `audit.alert-above` 时即时推送给在线管理员与控制台；早于 `audit.keep-days` 的文件启动时自动清理；
- **Folia 原生线程模型**：`folia-supported: true`；买卖、清仓、转账与菜单渲染点击都跑在操作玩家所属的 region 线程，菜单刷新从全局区域线程逐个跳到玩家线程，玩家存档与审计各走一条守护 IO 线程，另有一条每 5 分钟的异步刷盘任务；玩家数据每次改动直写而不靠定时刷盘，避免「买到手但没落盘就崩服」。

## 版本要求

| 项 | 要求 |
| --- | --- |
| 服务端 | Folia / Paper 26.2（`io.papermc.paper:paper-api:26.2.build.115-stable`） |
| Java | 21 |

## 安装

1. 将 `FmShop-1.0.0.jar` 放入服务端 `plugins/` 目录；
2. 首次启动自动生成 `plugins/FmShop/config.yml` 与 `plugins/FmShop/prices.yml`；
3. 按需修改这两个文件后执行 `/fsa reload` 应用；重载只重读配置与价格表，余额与行情刻意不重读，避免把上次刷盘之后的交易回滚。

## 指令与权限

玩家指令 `/fmshop`（别名 `shop`、`fsh`，权限 `fmshop.use`）：

| 指令 | 说明 |
| --- | --- |
| `/fmshop` | 打开商店菜单（仅玩家可用） |
| `/fmshop buy <物品> [数量]` | 按当前行情买入，数量缺省 1 |
| `/fmshop sell <物品> [数量]` | 卖出指定商品，数量缺省 1 |
| `/fmshop sell hand` | 卖出手上这一叠 |
| `/fmshop sell inv` | 清仓：卖出背包内全部可回收物品（`all` 等效） |
| `/fmshop price <物品>` | 查看买入价、卖出价与行情倍率 |
| `/fmshop balance [玩家]` | 查看余额；查他人需 `fmshop.admin` |
| `/fmshop pay <玩家> <金额>` | 转账，需 `fmshop.pay` |
| `/fmshop help` | 帮助；输错子命令也会打帮助 |

物品名支持唯一前缀匹配，`/fmshop price sulfur_sp` 即可命中 `SULFUR_SPIKE`。

管理指令 `/fmshopadmin`（别名 `fsa`，权限 `fmshop.admin`）：

| 指令 | 说明 |
| --- | --- |
| `/fsa give <玩家> <金额>` | 给钱；离线玩家同样生效 |
| `/fsa take <玩家> <金额>` | 扣钱 |
| `/fsa set <玩家> <金额>` | 直接设定余额 |
| `/fsa price <物品> <买价> <卖价>` | 改价：写回 `prices.yml` 后自动重载；卖价不低于买价直接拒绝 |
| `/fsa market <物品> [reset]` | 查看行情倍率、现价、基准价与累计买卖量；`reset` 复位该商品行情 |
| `/fsa resetlimit <玩家\|*>` | 重置今日限额；`*` 作用于全部在线玩家 |
| `/fsa reload` | 重读配置与价格表 |
| `/fsa status` | 商品数、账户数、费率与去向、税池、行情波动数、打开的界面数、日志文件数、限额重置时刻 |
| `/fsa audit [玩家] [条数]` | 查看当天账本，条数夹到 1-50，缺省 10 |
| `/fsa doctor` | 价格表体检：用服务端真实配方表复查套利路径 |
| `/fsa tax [take <玩家> <金额>]` | 查看税池余额与累计；`take` 从税池发放给玩家 |
| `/fsa help` | 帮助 |

权限节点：

| 节点 | 默认 | 含义 |
| --- | --- | --- |
| `fmshop.use` | true | `/fmshop` 全部玩家子命令与菜单 |
| `fmshop.pay` | true | `/fmshop pay` 转账 |
| `fmshop.fee.exempt` | false | 免收买 / 卖 / 转账手续费 |
| `fmshop.admin` | op | `/fmshopadmin` 全部；也放开 `/fmshop balance <玩家>` |

## 配置

`plugins/FmShop/config.yml` 中运维最常改的键。费率单位 bp 为万分之一，`500` 即 5%。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `currency.name` | `金币` | 货币显示名 |
| `currency.starting-balance` | `100.0` | 新账户初始余额，首次加入时固定下来 |
| `fees.sell-bp` | `500` | 卖出手续费，从毛收入中扣 |
| `fees.buy-bp` | `0` | 买入手续费，附加在货款之上 |
| `fees.pay-bp` | `200` | 转账手续费，由付款方承担 |
| `fees.destination` | `void` | 手续费去向：`void` 销毁，`tax-pool` 计入税池 |
| `limits.max-per-action` | `2304` | 单次交易件数上限，夹到 1-4096 |
| `limits.min-pay` | `1.0` | 单笔转账最小金额，下限 1 分 |
| `limits.reset-hour` | `4` | 每日限额翻页时刻（本地时间），夹到 0-23 |
| `gui.auto-refresh-seconds` | `5` | 菜单自动刷新周期，`0` 关闭 |
| `gui.sounds` | `true` | 交易与点击音效 |
| `audit.enabled` | `true` | 审计日志总开关 |
| `audit.keep-days` | `14` | 账本保留天数，下限 1 |
| `audit.alert-above` | `10000.0` | 净额达到该值时提醒在线管理员 |
| `doctor.strict` | `false` | `true` 时启动体检会直接下架存在套利风险的商品 |

商品价格、分类与动态行情参数在 `plugins/FmShop/prices.yml`；两份文件的完整键表、夹取规则与取值理由见 [DESIGN.md](DESIGN.md)。

## 构建

```bash
# 方式一：Maven
mvn -q package                 # 产物 target/FmShop-1.0.0.jar
```

```powershell
# 方式二：免 Maven 直编（javac + 本地 .m2 全量 classpath，成功打印 BUILD_OK）
powershell -File build.ps1     # 产物 target/FmShop-1.0.0.jar
```

`build.ps1` 的 classpath 默认取 `%USERPROFILE%\.m2\repository`，可用环境变量 `M2_REPO` 覆盖。实服端到端回归见 [bot/fmshop_test.js](../bot/fmshop_test.js)（mineflayer 操作菜单 + RCON 下管理指令，断言余额、限额、账本文件与配置重载后的持久性）。

## 目录结构

```
FmShop/
├── build.ps1                       # 免 Maven 直编脚本（javac 直连，成功打印 BUILD_OK）
├── pom.xml                         # Maven 构建
├── README.md
├── DESIGN.md                       # 设计方案（与实现对齐）
└── src/main/
    ├── java/dev/fm/shop/
    │   ├── FmShopPlugin.java       # 主类：启停顺序、重载、体检、定期刷盘
    │   ├── Settings.java           # 配置读取与钳制
    │   ├── PlayerListener.java     # 加入时异步载档、退出时刷盘
    │   ├── audit/
    │   │   └── AuditLog.java       # 按天追加账本、大额提醒、过期清理
    │   ├── cmd/
    │   │   ├── ShopCommand.java    # /fmshop 与补全
    │   │   └── AdminCommand.java   # /fmshopadmin 与补全
    │   ├── economy/
    │   │   ├── FmEconomy.java      # 对外经济接口（单位：分）
    │   │   ├── Balances.java       # 余额实现与转账加锁
    │   │   └── TaxPool.java        # 税池（独立 tax.yml，原子替换写盘）
    │   ├── gui/
    │   │   ├── ShopGui.java        # 活动界面登记与刷新 ticker
    │   │   ├── GuiListener.java    # 点击 / 拖拽 / 关窗
    │   │   ├── View.java           # 界面基类（自身即 InventoryHolder）
    │   │   ├── HubView.java        # 分类首页
    │   │   ├── CategoryView.java   # 分类商品页（左键买 / 右键卖）
    │   │   ├── ConfirmView.java    # 数量确认与含手续费预览
    │   │   ├── SellAllView.java    # 一键回收预览
    │   │   ├── Gui.java            # 边框、余额图标、价格 lore
    │   │   └── Icons.java          # 图标构造（统一去斜体与隐藏属性）
    │   ├── store/
    │   │   ├── PriceCatalog.java   # prices.yml 解析与商品检索
    │   │   ├── PriceEntry.java     # 单商品价格、日限额与动态参数
    │   │   ├── Category.java       # 分类定义
    │   │   ├── MarketState.java    # 行情倍率与惰性回归，落盘 market.yml
    │   │   ├── PriceDoctor.java    # 配方表套利体检
    │   │   ├── DataStore.java      # 每玩家 YAML 存档与守护 IO 线程
    │   │   └── PlayerData.java     # 余额、日号、今日买卖计数
    │   ├── tx/
    │   │   ├── TxEngine.java       # 买 / 卖 / 清仓 / 转账，先夹量再扣钱后交货
    │   │   ├── TxResult.java       # 单笔结果（含实际成交量）
    │   │   └── TxReport.java       # 结果到文案的唯一映射
    │   └── util/
    │       ├── Money.java          # 整数分算术与万分比手续费
    │       ├── ItemNames.java      # 物品名交给客户端语言解析
    │       ├── TextUtil.java       # MiniMessage 与占位符替换
    │       └── TimeUtil.java       # 时长格式化
    └── resources/
        ├── plugin.yml
        ├── config.yml              # 货币、费率、限额、界面、审计、体检
        └── prices.yml              # 129 件商品 / 7 个分类的价格表
```

运行期在 `plugins/FmShop/` 下另行生成：行情状态 `market.yml`、税池 `tax.yml`、玩家存档 `data/<uuid>.yml`、账本 `audit/<yyyy-MM-dd>.log`。这些是活数据，不随配置重载而重读。

## 许可证

[GPL-3.0](../LICENSE)
