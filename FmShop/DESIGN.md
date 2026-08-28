# FmShop 设计方案 v1.0（与实现对齐）

> 系统商店 + 内置经济：管理端定价、整数分记账、每行固定买卖差价（素表 2×、附魔书 6×），把刷钱回路封死在价格表结构里，而不是靠运维小心。
> 目标平台：Folia / Paper 26.2 · 编译目标 Java 21

## 1. 定位

| 项 | 决定 |
| --- | --- |
| 形态 | 单插件、无硬依赖、一个 jar（产物 `target/FmShop-1.0.0.jar`）；经济内置，同时作为全服经济后端对外开放 |
| 身份 | `FmShop` 1.0.0，主类 `dev.fm.shop.FmShopPlugin`，`api-version: '26.2'`，`folia-supported: true`，作者 `Fm` |
| 平台 | Folia 原生线程模型；Paper 26.2 经同名回退调度器运行同一份代码（依赖 `io.papermc.paper:paper-api:26.2.build.115-stable`，provided） |
| 商店模型 | 管理端定价的系统商店：买入与回收都是与"服务器"成交，不含玩家寄售；玩家间资金流动只有 `/fmshop pay` |
| 货币 | 金币；内部 `long` 分，1 金币 = 100 分；默认初始余额 100.0；上限 `Money.MAX = Long.MAX_VALUE / 4` |
| 价格表 | `prices.yml`，1374 件商品 + 128 行曲线展开的附魔书 / 15 个分类；素表 `buy = 2 × sell`，附魔书 `buy = 6 × sell` |
| 构建 | `mvn -q package` 或 `FmShop/build.ps1`（直连 javac，成功打印 `BUILD_OK`） |
| 运行期文件 | `plugins/FmShop/`：`config.yml`、`prices.yml`、`market.yml`、`tax.yml`、`data/<uuid>.yml`、`audit/<yyyy-MM-dd>.log` |
| 许可 | GPL-3.0（仓库根 `LICENSE`） |

商店插件的两个经典事故是刷钱回路与浮点金额误差。本方案在结构层排除它们：金额全程整数分，永不出现浮点误差；价格表固定差价（素表买价 2×、附魔书 6×）叠加服务端配方体检，让"买材料 → 合成 → 卖成品"在数学上必亏，动态行情也无法撬开这条缝。

## 2. 金额模型：整数分

`util/Money.java`：除配置读取与展示外，全插件没有浮点。

- `SCALE = 100L`（1 金币 = 100 分）；`MAX = Long.MAX_VALUE / 4`，所有余额与金额运算以它为饱和上限；
- `parse(String)` 把小数文本转分，HALF_UP，无法解析返回 -1；`ofDouble(double)` 同样 HALF_UP——浮点只存在于配置文件边界，一进来就固化成整数；
- `format(long)` 输出 `1,234.50`，`format(long, String currency)` 追加货币名；
- `times(long cents, int qty)` 溢出时饱和到 `MAX` 而不是回绕：回绕会把一笔巨款变成负数或零，饱和只是把上限钉死；
- `add(long, long)` 同理饱和，供入账路径使用。

手续费按基点（bp，10_000 = 100%）计。核心实现：

```java
public static long basisPoints(long amount, int bp) {
    if (amount <= 0 || bp <= 0) {
        return 0;
    }
    if (bp >= 10_000) {
        return amount;
    }
    long q = amount / 10_000L;
    long r = amount % 10_000L;
    long fee = q * bp + (r * bp + 5_000L) / 10_000L;
    return Math.min(amount, fee);
}
```

为什么不能直接 `amount * bp / 10_000`：余额上限约 2.3 × 10^18 分，乘以一个四位数基点必然溢出 long；溢出回绕成负数后，一笔卖出手续费会变成白送钱。拆成商 `q` 与余数 `r` 之后，`bp >= 10_000` 已被提前拦截（手续费不可能超过全额），乘法路径里 `bp < 10_000`，于是 `q * bp` 最大不超过 `amount` 本身，`r * bp` 不超过 10^4 × 10^4，都在 long 射程内；余数部分 `+ 5_000` 再除实现 HALF_UP。结果永不为负、永不超过本金。

## 3. 价格表与反套利

价格条目（`store/PriceEntry.java`）：

```java
public record PriceEntry(ItemKey key,
                         String category,
                         long buy,
                         long sell,
                         int dailyBuy,
                         int dailySell,
                         boolean unlockBySell,
                         int lifetimeSell,
                         boolean dynamic,
                         int stepBp,
                         int floorBp,
                         int ceilBp,
                         int recoverBpPerHour) {
```

身份从 `Material` 挪进了 `store/ItemKey.java`（`record ItemKey(Material material, Enchantment enchant, int level)`）：一种材料可以背多行——所有附魔书都是 `ENCHANTED_BOOK`；查找、限额、行情、审计一律以 `ItemKey#id()` 为键（素行 `IRON_INGOT`，变体行 `ENCHANTED_BOOK/SHARPNESS/5`）。分隔符用 `/` 不用 `.`——`.` 是 YAML 路径分隔符，会撞穿 market.yml 一类的键。

`buy > 0` 才可买、`sell > 0` 才可卖；价格为 0 即关闭该方向——只收不卖的怪物掉落物与只卖不收的金币回收口都是这么表达的。

反套利不变量：**每行差价比例固定（素表 buy = 2 × sell，附魔书 buy = 6 × sell）；买卖共用同一个行情倍率，任何倍率下差价比例不变，动态定价永远打不开刷钱回路；/fsa doctor 用服务端真实配方表复查。**

为什么 2 倍差价够：当配料的买价已是其卖价的 2 倍，任何配方产物都不可能比配料更值钱，"买材料 → 合成 → 卖成品"必亏。

全表 1374 件素商品在 `items:` 里手写，另有 128 行附魔书由 `enchanted-books:` 按曲线展开（见本节下文），合计 1502 行，覆盖服务端 26.2 全部可交易物品。非天然物品的买价不是手填的，而是按服务端真实配方递推：`buy(产物) = 1.25 × Σ min(每格最便宜合法材料的买价) ÷ 产量`。25% 加工费叠在 50% 回收率之上，任意深度的合成链都满足 `0.5 × 1.25 = 0.625 < 1`，链越长亏得越多；"方块 9× 定价"与"熔炼产物卖价 ≤ 原料买价"不再是额外的人工约束，而是这条递推的自然结果（`IRON_BLOCK` 买价 54.00 = 9 × `IRON_INGOT` 6.00）。

天然产出（矿石、花草树叶、怪物掉落、结构战利品）无从递推，逐条手工定锚，梯度按**获取难度 / 可自动化程度**而非稀有度：可无限刷的产出（刷怪塔、铁傀农场、作物农场）压得极低；受时运影响的矿物按时运 III 期望产量（约 2.2×）折价；无法量产的物品（下界之星、潜影壳、鞘翅）定高价。代表性条目：

| 商品 | 买价 | 卖价 | 日限额 | 动态 step-bp |
| --- | --- | --- | --- | --- |
| COAL | 1.20 | 0.60 | daily-sell 4096 | 1 |
| IRON_INGOT | 6.00 | 3.00 | daily-sell 2048 | 2 |
| GOLD_INGOT | 14.00 | 7.00 | daily-sell 1024 | 3 |
| DIAMOND | 80.00 | 40.00 | daily-sell 256 | 20 |
| ANCIENT_DEBRIS | 260.00 | 130.00 | daily-sell 64 | — |
| CHARCOAL | 1.20 | 0.55 | — | — |

木炭卖价刻意低于煤的卖价：烧炭是一条全自动产线，与煤等价就是一台印钞机。

两类漏洞是 `PriceDoctor` 看不见的，只能在价格表这一侧堵：

- **时运挖矿**：19 件商品（全部 `*_ORE` 与 `SULFUR_SPIKE`）`buy: 0` 只收不卖。矿石的卖价等于它掉落的矿物卖价，若同时出售，一把时运 III 镐就能把 1 份矿石变成 4 份掉落；不出售即无从下手。
- **破方块掉落**：沙砾→燧石、萤石→萤石粉、紫水晶簇→碎片、海晶灯→水晶、西瓜→瓜片、书架→书、雪块→雪球、粘土→粘土球、树脂块→树脂，逐个校验过"方块买价 ≥ 时运 III 上限掉落量 × 掉落物卖价"。沙砾正是被这条改掉的：时运 III 让沙砾 100% 出燧石，旧的 0.06 买价配 0.10 燧石卖价是一台印钞机，现为 0.24。

恰好 3 个只卖不收的金币回收口（有买价无卖价，配置注释写明"只卖不收：金币回收口，防止倒卖回流"）：

| 商品 | 买价 | daily-buy |
| --- | --- | --- |
| EXPERIENCE_BOTTLE | 8.00 | 512 |
| ELYTRA | 1500.00 | 4 |
| ENCHANTED_GOLDEN_APPLE | 2000.00 | 8 |

15 个分类（id / 显示名 / 图标 / order / 件数）：`ores` 矿物 / IRON_INGOT / 1 / 48、`stone` 石材 / STONE / 2 / 209、`wood` 木材 / OAK_LOG / 3 / 217、`nature` 自然 / OAK_SAPLING / 4 / 133、`farm` 农牧 / WHEAT / 5 / 61、`food` 食物 / COOKED_BEEF / 6 / 29、`mob` 战利品 / ENDER_PEARL / 7 / 105、`nether` 下界 / NETHERRACK / 8 / 63、`end` 末地 / END_STONE / 9 / 33、`redstone` 红石 / REDSTONE / 10 / 89、`deco` 装饰 / WHITE_WOOL / 11 / 221、`gear` 装备 / DIAMOND_SWORD / 12 / 115、`brew` 酿造 / BREWING_STAND / 13 / 18、`misc` 杂项 / NAME_TAG / 14 / 33、`enchants` 附魔 / ENCHANTED_BOOK / 15 / 128（曲线展开，非手写）。14 个手写分类合计 1374 件；hub 的 21 格分类槽用去 15 格；最大的 `deco` 占 5 页（每页 45 格）。

**附魔书不手写，按曲线展开（`store/EnchantedBooks.java`）。** `enchanted-books:` 块展开成每个（附魔 × 等级）一行——26.2 原版 43 种附魔共 128 行，不进 `items:`。128 行手写必然互相矛盾，而且游戏更新加一种附魔（26.2 加了 `lunge`）表里就静静缺一行，没人会发现；所以价格来自曲线：

```
sell = base-sell × 等级^level-exponent × rarity^rarity-exponent × 诅咒系数
buy  = sell × buy-multiplier
```

出厂值：`base-sell: 24`、`level-exponent: 1.7`、`rarity-exponent: 1`、`buy-multiplier: 6`、`curse-multiplier: 0.25`、`category: enchants`、`lifetime-sell: 1`、`unlock-by-sell: true`。`rarity` 默认取原版 `Enchantment#getAnvilCost()`（1 / 2 / 4 / 8）——它本来就是价值倍率（原版铁砧公式正是 anvil cost × 等级），恰好是 `getWeight()`（10 / 5 / 2 / 1）的倒数，也是 API 里唯一表达"更值钱"而不只是"更少见"的量。只靠等级的曲线会在精准采集 / 无限 / 引雷上错得离谱：三者最高 1 级且非宝藏，等级曲线把它们标成锋利 I 的价，而原版给这三个 anvil 8。

原版稀有度与玩家实际需求不一致的地方，`overrides` 只改 `rarity`——写死 `sell` 会把 I..V 全压成一个价，改 `rarity` 则等级曲线原样保留：

| 附魔 | rarity | 原因 |
| --- | --- | --- |
| MENDING | 25 | 原版 anvil 只有 4，但它是唯一不可再生、且人人都要的附魔 |
| THORNS | 0.75 | anvil 8 反映附魔台稀缺度，不反映强度 |
| IMPALING | 0.55 | 只对水生生物有效，5 级曲线会把它顶到锋利 V 之上 |
| BLAST_PROTECTION | 1.2 | anvil 4 = 摔落保护的 4 倍，用途却窄得多 |
| SMITE | 0.5 | anvil 2 = 锋利的 2 倍，实战严格弱于锋利 |
| BANE_OF_ARTHROPODS | 0.4 | 同上——不压下来就出现"更弱的附魔更贵"的倒挂 |

展开结果：128 行，全表 sell 合计 31,042.94（实测；由服务端附魔注册表推导，改一次注册表就变，不是可写死的常量）——因 `lifetime-sell: 1`，这也是每名玩家终身能从附魔书套现的上限。行按附魔注册名的字母序稳定输出：注册表迭代顺序未规定，不稳定会让商店页面在两次重启之间洗牌。

**`lifetime-sell: 1` 是结构件，不是装饰**：治好的僵尸村民图书管理员能用 1 颗绿宝石换一本可交易附魔书（原版 `tradeable` 标签，含经验修补）——不封顶的回收就是无上限刷钱；配套的 `unlock-by-sell: true` 要求先卖出过一本才解锁购买，保住"自己附魔 / 找村民换"这段玩法不被商店抹掉。

加载规则（`PriceCatalog`）：先分类后商品，再展开附魔书，按 `order` 排序，每个分类保证有（可能为空的）桶；行表以行 id 为键（`LinkedHashMap`），另备 `EnumMap<Material, List<PriceEntry>>` 反向索引，手持堆经 `match(ItemStack)` 按 `ItemKey#matches` 精确落回唯一一行。分类图标经 `Material.matchMaterial` + `isItem()` 校验，非法则记入 `problems` 并退化为 STONE；价格为负记入 `problems` 并取 0；`buy == 0 && sell == 0` 的行跳过；`category` 缺省 `misc`。未知材料 ID（`matchMaterial == null`）**不致命**，进 `unknown` 列表：为 1.21.x 写的配置可能用了后续版本改名的材料，26.2 又新增了老服没有的 SULFUR/CINNABAR 系——`/fsa doctor` 会列出它们，其余有效行照常工作。物品解析 `match(String)` 先精确 id（大小写不敏感）、再材料名素行、再唯一前缀、最后唯一子串——`/fmshop price sulfur_sp` 命中 SULFUR_SPIKE，`/fmshop price sharpness/5` 命中锋利 V 书，裸 `sharpness` 跨五级有歧义则返回空，而不是随便挑一级。

`PriceDoctor` 是结构约束之外的复查：遍历服务端真实配方注册表（`ShapedRecipe`、`ShapelessRecipe`、`CookingRecipe`、`StonecuttingRecipe`），用商店**买价**给最便宜的合法配料组合定价，与产物的商店**卖价**比较，分三级上报（`enum Severity { ERROR, WARN, INFO }`）：

- 正利润 = ERROR——一条可以直接跑的刷钱回路；
- 配料商店不出售 = WARN——玩家农场可能供货，需人工判断；
- 其余说明性发现 = INFO。

只在基准 100% 倍率下校验，因为买卖两侧共用同一个倍率（见 §4）。启动时 `doctor(true)`、重载时 `doctor(false)`：`enforce` 会下架所有被 ERROR 指名的商品，且只在 `doctor.strict: true` 时执行。会话中途把商品从玩家脚下抽走，比一条运维开服就看过的警告更糟，所以重载永不强制下架；而 `doctor.strict` 默认 `false`——一个刚上手改价格的运维应该被告知问题，而不是看着商品悄悄从商店里消失。

## 4. 动态行情

`store/MarketState.java`：`BASE_BP = 10_000` 即 100%；每行商品一行 `Row { int mulBp = BASE_BP; long lastMs; long boughtTotal; long soldTotal; }`，存于 `HashMap<String, Row>`、以行 id 为键——两条附魔书行共享 `ENCHANTED_BOOK` 但行情各自独立，`settle()` 用 `e.id()` 定位；落盘 `plugins/FmShop/market.yml`，键是行 id 原样（`<行 id>.mul-bp`）——素行的 id 仍是材料名，旧 market.yml 不用迁移。

**买卖两侧共用同一个倍率。**这是反套利不变量的动态半边：任何倍率下配置的买卖差价比例不变，动态定价永远无法打开刷钱回路；若两侧独立漂移，倍率终会交叉，回路就出现了。

- 成交即推动：`onBuy` 做 `mulBp = min(ceilBp, mulBp + stepBp * qty)`，`onSell` 做 `max(floorBp, mulBp - stepBp * qty)`——`step-bp` 按件累加，并被 floor/ceil 夹取；
- 回归是读取时**惰性结算**，没有定时任务（`settle`）：`elapsed < 60_000L` 直接跳过；否则 `recovered = recoverBpPerHour * elapsed / 3_600_000L`，从任一侧向 `BASE_BP` 靠拢。惰性结算让冷商品的行情不为零流量付利息——没人看的商品根本不占 CPU；
- `scale(long cents, int mulBp)` 同样 HALF_UP 并拆商余数，末尾 `Math.max(1, …)`：有价商品不会被缩到免费；
- `multiplierBp / buyUnit / sellUnit / onBuy / onSell / reset / bought / sold / movedCount` 全部 `synchronized`——行情被所有 region 线程共享；`reset(null)` 清全表，`reset(id)` 清一行。

配置在 `prices.yml`：`dynamic-defaults`（`enabled: false`、`step-bp: 2`、`floor-percent: 50`、`ceil-percent: 200`、`recover-bp-per-hour: 300`）给全表兜底，单品可内联 `dynamic: { … }` 覆盖；缺省 `dynamic-defaults` 时硬编码兜底 `Dyn(false, 25, 5_000, 20_000, 500)`。读取时 `floor-percent`/`ceil-percent` ×100 转 bp，强制 `floor > 0`（否则取 100）且 `ceil >= floor`，`step-bp`/`recover-bp-per-hour` 下限 0。全表共 7 件商品带 `dynamic:`。

## 5. 交易引擎契约

`tx/TxEngine.java` 的契约：**先把订单夹到"实际可能的量"（限额、背包空间、持有量），再扣钱，最后交货**；任何早期失败都让钱和物都不动。只有与某一行探针精确匹配的物品可卖——`ItemStack#isSimilar` 对每行的 `ItemKey#probe()` 比对：锋利 V 书恰好命中自己的行，多带一条附魔的书、改过名的书、损伤 / 改名的工具都对不上任何行——因为这类物品的价值高于表价，收进来就是一条洗钱通道。引擎运行在操作玩家所属的 region 线程。

```
                 want（玩家想要的数量）
                   │
                   ▼
   ┌────────── 夹：订单 → 实际可能的量 ──────────┐
   │ 单次上限 max-per-action（买/卖共用）          │
   │ 今日限购/限售余量（≤ 0 → quota-buy/-sell）    │
   │ 买：背包空间（= 0 → no-space）                │
   │ 卖：持有数量（= 0 → nothing-to-sell）         │
   └───────────────────┬───────────────────────┘
                       ▼   qty = 夹后的实际成交量
            计价 gross = 当前行情单价 × qty
            fee  = basisPoints(gross)（fee 豁免 → 0）
                       ▼
   ┌────────── 扣：先钱后货，失败原样返回 ─────────┐
   │ 买：withdraw(gross + fee)，不够 → no-money    │
   │ 卖：take() 取走物品后才入账 net = gross - fee │
   └───────────────────┬───────────────────────┘
                       ▼
   ┌────────── 交货与记账（顺序固定）──────────────┐
   │ 交货/入账 → 限额计数 → save                   │
   │ → market.onBuy/onSell → collect(fee)          │
   │ → 审计 BUY/SELL → 音效 → TxResult.done        │
   └─────────────────────────────────────────────┘
```

各操作的完整判定顺序与失败消息键：

- **买 `buy`**：`not-buyable` → 夹到 `[1, max-per-action]` → `unlock-by-sell` 行从未卖出过报 `locked` → 今日限购余量 ≤ 0 报 `quota-buy{limit,left}`（否则夹住）→ 背包空间 ≤ 0 报 `no-space`（否则夹住）→ 算 `gross / fee / total` → 扣款失败报 `no-money{need}`（`need = total - 余额`）→ 成功后依次 `give → addBought → save → market.onBuy → collect(fee) → 审计 BUY → 音效 → buy-ok{qty,gross,fee,total}`；
- **卖 `sell`**：`not-sellable` → 创造模式 `no-sell-creative` → 持有为 0 `nothing-to-sell` → 夹到持有量 / 单次上限 → 今日限售 `quota-sell` → 终身限售 `quota-lifetime` → `take` 取货 → 算 `gross / fee / net` → 入账 → `addSold（带 unlock/lifetime 的行另记 addSoldEver）→ save → market.onSell → collect → 审计 SELL → 音效 → sell-ok{taken,gross,fee,net}`；
- **清仓 `sellAll`**：创造模式返回空 Sweep；从 `getStorageContents()` 逐堆 `match(ItemStack)` 解析到行——解析不到任何行的堆直接跳过，绝不落回同材料的素行——去重后**对每一行单独调一次 `sell(p, entry, maxPerAction)`**，某一行被限额拦住不会中断整轮清仓；最后汇总 `Sweep{kinds, items, gross, fee, net}`；
- **转账 `pay`**：金额低于下限 `pay-too-small{min}` → 转给自己 `pay-self` → 算手续费与总额 → 余额不足 `no-money` → **先扣手续费再转本金，若 `transfer` 失败（收款方触到余额上限）则退还手续费**报 `pay-failed`——所以没有任何路径会收了钱却不转本金；审计 `PAY`（`mat = null`，物品字段写 `-`，数量 1）。

手续费豁免走权限节点 `TxEngine.FEE_EXEMPT = "fmshop.fee.exempt"`（对买 / 卖 / 转账同样生效）。`collect(fee)` 只在 `fees.destination = tax-pool` 时把手续费计入税池；`void` 就是凭空消失（通缩）。背包空间按"空格数 × 堆叠上限 + 同类未满堆的余量"估算，到 4096 提前返回。音效：买 `ENTITY_ITEM_PICKUP`（音高 1.0），卖 `ENTITY_EXPERIENCE_ORB_PICKUP`（音高 1.4），音量 0.7，受 `gui.sounds` 控制。

`TxResult` 只携带数据（`ok / key / qty / gross / fee / net / need / limit / left`），`qty` 是**实际成交量**——被夹住的订单如实上报，而不是整单拒绝。措辞全部在 `TxReport`：`buy-ok` 映射 `{item}{n}{cost}`，`sell-ok` 映射 `{item}{n}{gain}`（`fee > 0` 时追加 `sell-fee`），`no-money` 填 `{need}`，`quota-*` 填 `{item}{limit}{left}{reset}`。引擎只返回数据不说话，所以同一笔交易从指令和菜单读起来完全一致，且所有措辞都在配置里。

## 6. 限额与日切

`daily-buy` / `daily-sell` 是每件商品的日限额（下限 0），按**玩家**分别计数——额度不在玩家之间共享，买入与卖出也是两个独立计数器（卖出不回冲买入额度，商店侧无库存概念）：计数存在 `PlayerData`：`long day`（计数器所属的 epoch day）+ `Map<String, Integer> bought / sold`（以行 id 为键）；另有同键的终身账本 `soldEver`，驱动 `unlock-by-sell` 解锁与 `lifetime-sell` 上限。

日号算法（`Settings.today()`）：

```java
LocalDateTime.now(ZoneId.systemDefault()).minusHours(resetHour()).toLocalDate().toEpochDay()
```

把当前本地时间往前推 `limits.reset-hour` 小时再取日期——日号因此在每天 `reset-hour` 点（默认凌晨 4 点，服务器本地时间）翻页，而不是午夜：凌晨在线的玩家不会在 0 点整看着限额突然重置。`untilReset()` 返回距下次翻页的毫秒，供 GUI 倒计时与 `quota-*` 消息的 `{reset}` 占位符。

`boughtToday / soldToday / addBought / addSold` 都先 `roll(today)`：日号变了就把两张日表清零再计数——`roll` 刻意不动 `soldEver`，终身账本半夜清空会把所有解锁行重新上锁。`resetQuotas()` 清两张日表，供 `/fsa resetlimit`；`resetLifetime()` 清 `soldEver`，供 `/fsa resetunlock`。

## 7. 存档与并发

`store/DataStore.java`：每玩家一份 YAML，`plugins/FmShop/data/<uuid>.yml`。

- **每次改动直写，而不是定时刷盘**："购买成功但还没刷盘就崩服"等于白送物品；玩家数据没有可接受的丢失窗口；
- 写路径是先快照成 YAML 字符串，再排队给单条守护 IO 线程 `FmShop-IO` 异步落盘并清脏标记——region 线程永不碰磁盘；
- 缓存上限 `CACHE_CAP = 256`；`get` 只查缓存，`loadSync` 阻塞（仅管理路径用），`loadAsync` 的回调切回 global region scheduler；
- 损坏文件隔离为 `.bak-<时间戳>`，不覆盖、不硬删；
- `close()` 最多等 10 秒排空队列，然后同步写出所有缓存玩家；另有 `fileCount()` 供 `/fsa status`。

`PlayerData` 的**每个访问器都 `synchronized`**：买卖跑在玩家 region 线程，而管理指令与关服刷盘在别的线程，同一份数据没有专属线程。余额夹到 `[0, Money.MAX]`；`deposit` 饱和并累加 `totalEarned`；`withdraw` 只在够付时成功。

`Balances.transfer(from, to, cents)` 按 UUID 顺序（`from.compareTo(to) <= 0`）对两个 `PlayerData` 加锁，避免两笔对转死锁；并且**先**检查收款方余额上限（`balance > Money.MAX - cents` 即拒绝）——饱和存入一个已达上限的账户会把刚扣掉的钱直接销毁。

`PlayerListener`：玩家加入时 `loadAsync` 预载——首次购买不必等磁盘，且新账户的初始余额在首次加入时就固定下来；退出时脏则刷盘。

税池（`economy/TaxPool.java`）独立存 `plugins/FmShop/tax.yml`，让运维能读 / 清税池而不碰玩家数据；默认 `void` 去向时它恒为 0、零成本。方法全 `synchronized`；保存先写 `tax.yml.tmp` 再 `Files.move(REPLACE_EXISTING)` 原子替换——半截文件不会顶替好文件。

## 8. 经济 API

对外接口 `economy/FmEconomy.java`（单位一律分，每个账户的每次写操作原子）：

| 方法 | 语义 |
| --- | --- |
| `long balance(UUID)` | 查余额 |
| `boolean has(UUID, long)` | 是否至少持有某金额 |
| `boolean withdraw(UUID, long)` | 扣款；不够则一分不动，返回 `false` |
| `boolean deposit(UUID, long)` | 存款；饱和到 `Money.MAX` |
| `long set(UUID, long)` | 直接设定余额，返回设定后的值 |
| `boolean transfer(UUID, UUID, long)` | 转账；双账户按 UUID 顺序加锁，收款方触顶则整笔不动 |
| `String format(long)` | 格式化为 `1,234.50` |
| `String currency()` | 货币名（金币） |

集成路径是 Bukkit 服务：启用时 `getServer().getServicesManager().register(FmEconomy.class, balances, this, ServicePriority.Normal)`（`FmShopPlugin.java:77`），停用时 `unregisterAll(this)`（`:98`）。消费方按标准服务查找获取：

```java
FmEconomy eco = getServer().getServicesManager().load(FmEconomy.class);
if (eco != null) {
    long bal = eco.balance(uuid);
}
```

注：`FmEconomy.java:15-16` 的 javadoc 提到 `FmShopPlugin` 上的 `fmBalance` / `fmWithdraw` / `fmDeposit` / `fmFormat` 四个反射逃生口，但 1.0.0 并未实现它们——`ServicesManager` 查找是唯一受支持的集成路径。

管理端改钱也一律走 `FmEconomy` 而不直接碰 `PlayerData`：离线目标与在线目标走同一条路，且每次余额变动都带操作者名字入审计（见 §9）。

## 9. 审计日志

`audit/AuditLog.java`：追加写 `plugins/FmShop/audit/<yyyy-MM-dd>.log`，UTF-8，`CREATE, APPEND`，单条守护线程；日期取 `LocalDate.now()`，即服务器本地时间。

行格式（`HH:mm:ss` + 空格分隔字段，javadoc 示例）：

```
12:04:31 BUY Steve IRON_INGOT x64 gross=384.00 fee=0.00 net=384.00 bal=1216.00
```

物品字段写行 id（`key.id()`，如 `IRON_INGOT`、`ENCHANTED_BOOK/SHARPNESS/5`），`log(...)` 收 `ItemKey`；`PAY` 等纯资金事件 `key == null`，物品字段写 `-`。管理操作走 `logAdmin`，行尾带操作者：

```
HH:mm:ss ADMIN <target> <what> x1 gross=… fee=0.00 net=… bal=… by=<actor>
```

实时告警：`|net| >= audit.alert-above`（默认 10000.0）时，用 MiniMessage 向所有在线持有 `fmshop.admin` 者与控制台推送 `<gray>[<gold>审计</gold>]</gray> …`——在调用线程即时发出，早于落盘排队，大额异动不等 IO。

`/fsa audit` 走 `tail(playerName, limit, Consumer)`：在审计线程倒序扫描当天文件，回调切回 global region scheduler——所以 RCON 调用者会先拿到一条空回复，结果随后异步送达（这正是 e2e 断言账本文件而不是断言指令输出的原因）。`prune()` 删除早于 `audit.keep-days`（默认 14，下限 1）的文件。

## 10. GUI

### 10.1 框架

- `View` 本身就是 `InventoryHolder`：点击靠 `instanceof` 直接落回它所属的界面——没有全服槽位账本会在玩家掉线时泄漏；`live()` = 物品栏非空 && 玩家在线 && 玩家当前打开的就是这个物品栏；
- `GuiListener` 以 `EventPriority.LOW` 监听：只要 holder 是 `View` 就**先** `setCancelled(true)`，**包括**菜单打开时点自己背包的情况——否则 shift 点击会把物品塞进菜单并丢失；非持有者点击、`getClickedInventory() != getInventory()` 的点击忽略；`view.click` 包 try/catch，异常打 `菜单点击处理失败` 并关闭界面；拖拽事件直接取消；
- 交互模型：**左键买 / 右键卖**挂在同一个图标上，玩家永远不用找模式开关；**Shift+右键**立即卖出该项全部持有，是唯一值得加的快捷键，因为它不可能花超——卖的是自己持有的东西，引擎还会再夹一道；
- 自动刷新 ticker：`gui.auto-refresh-seconds <= 0` 空转，否则按 `max(20, 秒 × 20)` tick 挂在 global region scheduler 上；每轮先剔除离线玩家，再把每次重绘 hop 到 `player.getScheduler().run(...)`——Folia 上只能从拥有该玩家的 region 线程碰他的界面；
- lore 里的价格是那一瞬的单价，成交会重算：菜单挂着跨过一次价格波动时按新价成交，而不是兑现旧数字；
- 所有图标名称 / lore 强制取消斜体（MC 会把自定义显示名斜体化，整个菜单看起来像占位符），只隐藏属性与无法破坏两行，经数据组件 `DataComponentTypes.TOOLTIP_DISPLAY`（`TooltipDisplay#addHiddenComponents(ATTRIBUTE_MODIFIERS, UNBREAKABLE)`）——`ItemFlag.HIDE_ADDITIONAL_TOOLTIP` 在 26.2 已废弃，而且会连附魔书的存储附魔一起藏掉；存储附魔刻意保留可见——在书上它就是这一行的身份，藏了所有书图标就长得一模一样。`Icons.of` 收调用方给的 `ItemStack`（该行的探针）并先克隆再套 meta，探针要拿去 `isSimilar` 比对，改了调用方的实例会腐蚀后续所有匹配；点击音 `UI_BUTTON_CLICK`（0.5 / 1.2），拒绝音 `BLOCK_NOTE_BLOCK_BASS`（0.6 / 0.8）。

### 10.2 HubView（54 格，标题键 `gui-title-hub`）

| 槽位 | 内容 |
| --- | --- |
| 10–16、19–25、28–34 | 分类图标（`CATEGORY_SLOTS`，三排共 21 格：价格表可长到 21 个分类而无需给 hub 分页） |
| 47 | 我的余额（`balance` 图标，lore 含累计支出 / 累计收入） |
| 49 | 一键回收（`bag` 图标，lore `卖出背包中所有可回收物品` / `点击预览`） |
| 51 | 关闭（`close` 图标） |
| 其余 | `filler` 边框 |

空分类直接隐藏——"打开什么都没有的图标比没有图标更糟"；分类图标 lore 为 `N 种商品` + `点击查看`。

### 10.3 CategoryView（54 格，标题 `gui-title-buy`，`{category}` 填分类显示名）

| 槽位 | 内容 |
| --- | --- |
| 0–44 | 商品，按配置序（`PAGE_SIZE = 45`） |
| 45 | 上一页（仅该页存在时渲染） |
| 47 | 我的余额 |
| 49 | 返回（→ HubView） |
| 51 | 页码（`Material.PAPER`，`第 N/M 页` + `共 K 种商品`） |
| 53 | 下一页（仅该页存在时渲染） |
| 末行其余 | `Gui.fillRow(..., 45)` 铺底 |

点击路由：商品格 `buying = !type.isRightClick()`，方向关闭则播拒绝音；`SHIFT_RIGHT` 直接 `sell(player, e, maxPerAction())`（引擎会夹到限额 / 持有 / 单次上限）后重绘；其余打开 `ConfirmView`。

### 10.4 ConfirmView（27 格，标题 `gui-title-confirm`，`{item}`）

```
      0     1     2     3     4     5     6     7     8
   ┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
 0 │     │     │     │     │     │     │     │     │     │  filler 边框
   ├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
 1 │     │ -64 │ -16 │ -1  │预览 │ +1  │ +16 │ +64 │     │  9=边框
   ├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
 2 │返回 │     │     │一组 │确认 │上限 │     │     │余额 │
   └─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘
     18                  21   22   23                  26
```

- `+N` 用 `buy` 图标（EMERALD），`-N` 用 `cancel` 图标（RED_DYE）；预览图标以堆叠数显示当前数量，lore 为 `单价 / 数量 / 小计 / 手续费 / 应付|可得 / 上限 N (原因)`；
- 槽 21 置数量为 64（`一组 (64)`），槽 23 置为上限；`bump(delta)` 夹到 `[1, cap]`，动不了就播拒绝音；
- 上限取**第一个**生效的限制并用中文点名（`record Cap(int max, String reason)`）：买路径 `单次上限 → 今日限购 → 背包空间 → 余额`，卖路径 `单次上限 → 今日限售 → 持有数量`；`cap.max() > 0` 时确认位用 `confirm` 图标，否则用 `cancel` 图标、标题 `<red>无法交易` 并写出原因；
- 可负担量 `affordable(now, ceiling)` 先按单价估算再逐步下调——手续费是对**订单总额**取整而非逐件，逐件估会少算；
- 预览**含手续费**（对持有 `fmshop.fee.exempt` 者显示 0）：不含手续费的预览是一句"付完钱才被发现"的谎话。

### 10.5 SellAllView（54 格，标题 `gui-title-bag`）

| 槽位 | 内容 |
| --- | --- |
| 0–44 | 可回收清单，每行一种材料（`PAGE_SIZE = 45`），图标堆叠数显示 `×qty`（上限 64），lore 给 `可得 net` 与 `单价 gross/qty` |
| 45 | 返回 |
| 49 | 合计（`物品种类 / 合计 / 手续费（仅 > 0 时）/ 预计可得`） |
| 53 | 确认：空清单渲染 `<red>没有可回收的物品`（lore 注明快捷栏与盔甲栏之外的物品也会被扫描），否则 `<green>确认全部回收` |

`scan()` 遍历 `getStorageContents()`，跳过未定价与不可卖的堆——每堆先经 `PriceCatalog#match(ItemStack)` 解析到唯一一行，带意外 NBT 的堆解析不到行、绝不落回同材料的素行——按行合并；施加与引擎**完全相同**的夹取（先 `maxPerAction`，再 `dailySell - soldToday`），用同一套 `Money.times` / `Money.basisPoints` 与豁免规则算价——预览即所得，确认键只是让引擎重跑一遍。点确认 → `sellAll` + 汇总回报；空 Sweep 播拒绝音，界面仍活着则重绘。

## 11. 配置键全表

`plugins/FmShop/config.yml`（头部写明：金额单位为金币，内部按分整数存储，永不出现浮点误差）：

| 键 | 默认值 | 夹取 | 作用 |
| --- | --- | --- | --- |
| `currency.name` | `金币` | — | 货币显示名，`Money.format(cents, currency)` 追加 |
| `currency.starting-balance` | `100.0` | — | 新账户初始余额（金币，进系统即转分） |
| `fees.sell-bp` | `500` | — | 卖出手续费基点（500 = 5%） |
| `fees.buy-bp` | `0` | — | 买入手续费基点 |
| `fees.pay-bp` | `200` | — | 转账手续费基点（2%） |
| `fees.destination` | `void` | `void` / `tax-pool` | 手续费去向：凭空销毁（通缩）或进税池 |
| `limits.max-per-action` | `2304` | [1, 4096] | 单次交易件数上限 |
| `limits.min-pay` | `1.0` | 下限 1 分 | 转账最小金额 |
| `limits.reset-hour` | `4` | [0, 23] | 日限额翻页的本地时刻（见 §6） |
| `gui.auto-refresh-seconds` | `5` | 0 = 关闭 | 打开中菜单的自动重绘周期 |
| `gui.sounds` | `true` | — | 菜单与交易音效总开关 |
| `gui.icons.<key>` | 见下表 | 非物品材质退回默认 | 11 个图标位 |
| `audit.enabled` | `true` | — | 审计日志总开关 |
| `audit.keep-days` | `14` | 下限 1 | 审计文件保留天数 |
| `audit.alert-above` | `10000.0` | — | 单笔 `|net|` 达到该值实时推送管理员 |
| `doctor.strict` | `false` | — | 启动体检时自动下架 ERROR 商品（见 §3） |

`gui.icons` 默认值：`filler`=GRAY_STAINED_GLASS_PANE、`buy`=EMERALD、`sell`=GOLD_INGOT、`bag`=CHEST、`balance`=PLAYER_HEAD、`back`=ARROW、`prev`=ARROW、`next`=ARROW、`close`=BARRIER、`confirm`=LIME_DYE、`cancel`=RED_DYE。

## 12. 项目结构

```
FmShop/
├── build.ps1                        # 直连 javac 的构建脚本（成功打印 BUILD_OK）
├── pom.xml                          # Maven 构建（产物 target/FmShop-1.0.0.jar）
├── README.md                        # 面向玩家与运维的使用文档
├── DESIGN.md                        # 本文件
└── src/main/
    ├── java/dev/fm/shop/
    │   ├── FmShopPlugin.java        # 主类：装配、生命周期、价格表体检
    │   ├── Settings.java            # config.yml 读取与夹取
    │   ├── PlayerListener.java      # 加入预载、退出刷盘
    │   ├── audit/AuditLog.java      # 按天审计文件、管理员实时告警
    │   ├── cmd/ShopCommand.java     # /fmshop
    │   ├── cmd/AdminCommand.java    # /fmshopadmin
    │   ├── economy/FmEconomy.java   # 对外经济接口（Bukkit 服务）
    │   ├── economy/Balances.java    # FmEconomy 实现、转账加锁
    │   ├── economy/TaxPool.java     # 税池（tax.yml，原子替换落盘）
    │   ├── gui/View.java            # 界面基类（即 InventoryHolder）
    │   ├── gui/Gui.java             # 包私有工具：边框、余额图标、价格 lore、音效
    │   ├── gui/GuiListener.java     # 点击 / 拖拽 / 关闭事件
    │   ├── gui/ShopGui.java         # 活菜单登记与自动刷新 ticker
    │   ├── gui/HubView.java         # 主界面（54 格）
    │   ├── gui/CategoryView.java    # 分类页（54 格）
    │   ├── gui/ConfirmView.java     # 数量确认页（27 格）
    │   ├── gui/SellAllView.java     # 一键回收预览页（54 格）
    │   ├── gui/Icons.java           # 图标工厂（去斜体、隐藏 flag）
    │   ├── store/PriceEntry.java    # 价格条目 record
    │   ├── store/PriceCatalog.java  # prices.yml 加载与物品解析
    │   ├── store/ItemKey.java       # 行身份：材料 + 可选存储附魔，id 为查找 / 存档键
    │   ├── store/EnchantedBooks.java # enchanted-books: 按曲线展开为 128 行
    │   ├── store/Category.java      # 分类 record
    │   ├── store/MarketState.java   # 动态行情（共用倍率、惰性回归）
    │   ├── store/PriceDoctor.java   # 反套利体检（服务端配方表）
    │   ├── store/DataStore.java     # 每玩家存档与 FmShop-IO 线程
    │   ├── store/PlayerData.java    # 余额与日限额计数（全 synchronized）
    │   ├── tx/TxEngine.java         # 交易引擎（先夹后扣再交货）
    │   ├── tx/TxResult.java         # 交易结果 record
    │   ├── tx/TxReport.java         # 结果 → 消息（措辞全部在配置）
    │   └── util/                    # Money / TextUtil / TimeUtil
    └── resources/
        ├── plugin.yml
        ├── config.yml
        └── prices.yml               # 1374 件商品 + 128 行曲线附魔书 / 15 个分类
```

## 13. 指令与权限

`/fmshop`（别名 `fsh`，权限 `fmshop.use`）：

| 子命令 | 说明 |
| --- | --- |
| （无参） | 打开商店主界面；控制台得到 `players-only` |
| `help`（`?`） | 帮助；仅当发送者持有 `fmshop.pay` 时才列出 pay 行 |
| `balance [玩家]`（`bal`、`money`） | 查余额；查别人需 `fmshop.admin`（否则 `no-permission`），查不到报 `player-not-found` |
| `price <物品>`（`p`） | 按**当前行情**报价；关闭的方向填 `-`；动态商品附行情百分比与趋势（走高 / 走低 / 持平） |
| `buy <物品> [数量]`（`b`） | 买入；数量缺省 1 |
| `sell <物品\|hand\|inv> [数量]`（`s`） | 卖出；`hand` 卖手上整组（空手报 `hand-empty`），`inv`/`all` 一键回收（创造模式显式报 `no-sell-creative`，否则空 Sweep 会被读成"没东西可卖"） |
| `pay <玩家> <金额>` | 转账，需 `fmshop.pay`；成功回报发送者，收款方在线则同时收到到账提示 |
| 未知子命令 | 打帮助——未知子命令就是打错了，帮助列表比一行训斥有用 |

物品解析走 `PriceCatalog.match`（精确 id → 材料素行 → 唯一前缀 → 唯一子串；锋利 V 书写作 `sharpness/5`），失败报 `unknown-item`；离线玩家查找只用缓存（`getPlayerExact` 后 `getOfflinePlayerIfCached`），绝不让指令线程阻塞在 Mojang 往返上。Tab 补全按子命令给物品 id / `hand,inv` / 在线玩家名，数量位给 `1,8,16,32,64`，每类上限 60 条。

`/fmshopadmin`（别名 `fsa`，权限 `fmshop.admin`）：

| 子命令 | 说明 |
| --- | --- |
| `give\|take\|set <玩家> <金额>` | 调整余额；一律走 `FmEconomy`（离在线同路径），审计 `GIVE/TAKE/SET` 带操作者 |
| `price <物品> <买价> <卖价>` | 改价；`买价 > 0 且卖价 ≥ 买价` 直接拒绝（否则可无限刷钱）；写入 `prices.yml` 后重载——改文件而不是内存覆盖，改动活过重启、可见可回退；审计 `PRICE` |
| `market <物品> [reset]` | 看行情倍率、现价 / 基准价、累计成交量；`reset` 清该商品行情，审计 `MARKET-RESET` |
| `resetlimit <玩家\|*>` | 清今日限额；`*` 遍历在线玩家并报告人数，审计 `RESETLIMIT` |
| `resetunlock <玩家>` | 清终身卖出账本：所有 `unlock-by-sell` 行重新上锁、`lifetime-sell` 额度回补；刻意没有 `*` 形式，审计 `RESETUNLOCK` |
| `reload` | 重载配置与价格表（不动余额与行情，见 §14） |
| `status` | 商品 / 分类 / 未知 ID 数、账户（已加载 + 存档）、手续费率与去向、税池、行情波动数、打开中的界面数、日志文件数、限额重置倒计时、严格模式开关 |
| `audit [玩家] [n]` | 倒序查当天审计（`n` 夹 [1, 50]，缺省 10）；异步回调送达（见 §9） |
| `doctor` | 价格表体检；通过则报 `价格表体检通过：未发现套利路径`，否则前 20 条按级别配色（ERROR 红 / WARN 黄 / INFO 灰），其余转控制台 |
| `tax [take <玩家> <金额>]` | 查税池余额与累计；`take` 从税池扣、给玩家存，**存入失败则把钱加回税池**；审计 `TAX-TAKE` |
| `help` | 帮助 |

权限节点（plugin.yml）：

| 节点 | 默认 | 含义 |
| --- | --- | --- |
| `fmshop.use` | true | `/fmshop` 全部玩家子命令与菜单 |
| `fmshop.pay` | true | `/fmshop pay` 转账 |
| `fmshop.fee.exempt` | false | 免收买 / 卖 / 转账手续费 |
| `fmshop.admin` | op | `/fmshopadmin` 全部；也放开 `/fmshop balance <玩家>` |

## 14. Folia 线程模型

| 工作 | 线程 |
| --- | --- |
| 买 / 卖 / 清仓 / 转账、菜单渲染与点击 | 操作玩家所属 region 线程 |
| 菜单自动刷新 ticker | global region scheduler 起，逐个 hop 到 `player.getScheduler()` |
| 玩家数据落盘、审计写入 | 各自单条守护线程（`FmShop-IO`、审计线程） |
| 5 分钟 `flush()` | `Bukkit.getAsyncScheduler().runAtFixedRate`，`FLUSH_MINUTES = 5` |
| `loadAsync` / `tail` 回调 | 切回 global region scheduler |
| 关服 | `gui.closeAll()` → 取消任务 → 注销服务 → market/tax/data/audit 依次收尾 |

启动顺序（`onEnable`，刻意为之）：`saveDefaultConfig()` → 释放 `prices.yml` → `Settings.load()`（其余一切都读它）→ `PriceCatalog` + `loadPrices()` → `MarketState.load()` → `DataStore` → `Balances` → `TaxPool.load()` → `AuditLog` + `prune()` → `TxEngine` → `ShopGui` → `doctor(true)` → 注册 `FmEconomy` 服务与监听器 → 绑定指令 → **最后**才 `gui.startTicker()`（避免菜单开在半成品目录上）→ 每 5 分钟 `flush()`（`saveDirty` + 脏时写行情与税池）。

关服逆序，**菜单先关**：`gui.closeAll()` 最先执行——处理器没了的菜单会把自己的图标当物品发出去；随后取消异步与 global region 任务、注销服务，再依次 `market.write(snapshot())` → `tax.save()` → `data.close()` → `audit.close()`。

`reload()` 只做 `reloadConfig() → settings.load() → 新建 PriceCatalog → loadPrices() → doctor(false)`。**余额与行情刻意不重读**：重载是配置操作，从磁盘重读活钱会把上次刷盘之后的一切回滚（e2e 断言 D1"余额在配置重载后存活"钉的就是这条）。

## 15. 边界规则汇总

| 场景 | 处理 |
| --- | --- |
| `prices.yml` 解析失败 | 打警告并让商店为空，不崩服 |
| 未知材料 ID / 非法图标 / 负价格 | 进 `unknown` / `problems` 列表，`/fsa doctor`、`/fsa status` 可见，其余行照常 |
| 创造模式出售 | `sell` 拒绝 `no-sell-creative`；`sellAll` 返回空 Sweep；`sell inv/all` 显式拒绝 |
| 附魔书 | 有自己的行：与某行探针精确 `isSimilar`（如锋利 V 书命中 `ENCHANTED_BOOK/SHARPNESS/5`）即可回收，按该行价格成交 |
| 其余附魔 / 改名 / 损伤物品 | 与任何行的探针都不匹配，不可回收（洗钱通道关闭） |
| 订单超限额 / 空间 / 持有 | 夹到实际可成交量并如实上报，不整单拒绝 |
| `pay` 收款方触到余额上限 | `transfer` 整笔不动，退还手续费，报 `pay-failed` |
| 存档文件损坏 | 隔离为 `.bak-<时间戳>` |
| 配置重载 | 只重读 `config.yml` 与 `prices.yml`；余额与行情不动 |
| RCON 调 `/fsa audit` | 先收到空回复，结果由异步回调随后送达 |
| 菜单挂着跨过价格波动 | 按成交时的新价结算，不兑现 lore 旧数字 |
| 关服时菜单仍开着 | `gui.closeAll()` 最先执行，吞掉线程归属已错的异常 |
| 未知子命令 | 打帮助而非报错 |

**以上为与实现对齐的方案基线（v1.0）。**
