# FmKit 设计方案 v1.1（与实现对齐）

> 扫地不再删除，而是分类收进回收站。单插件、原创底层、可开源。
> 目标平台：Folia/Paper 1.20+（实测 26.1.2 / 26.2），编译目标 Java 21。

---

## 1. 定位

| 项 | 决定 |
|---|---|
| 形态 | **单一插件**，替代原 FengNiansaodi jar（原 jar 留档不安装） |
| 底层 | **原创 clean-room 重写**扫地逻辑，行为等同、不复制反编译代码，可开源 |
| 核心变化 | 扫地从 `entity.remove()` 改为**分流进双回收站** |
| 包名 | `dev.fm.kit`（沿用 dev.fm.* 惯例） |

---

## 2. 功能总览

```
扫地/收集子系统 ──► 双回收站存储 ──► GUI 浏览/取回 ──► 到期流转/清理
     │                  │                  │
  倒计时广播          私人按UUID分桶       切换/翻页/转公共/销毁
  双清单跳过          公共全局+容量上限    管理指令(独立命令)
  阈值清理
```

---

## 3. 物品分流规则（唯一判定，无歧义）

```
玩家丢弃 ──原版落地（不拦截）──► 落地物品天然带 thrower=丢弃者，与死亡掉落同等对待
死亡掉落 ──插件接管生成：打上死者 thrower 标记──► 与玩家亲手丢出同等对待
落地物品 ──扫地收集──► 有主人 且 主人回收开关=开 ──► 该玩家私人箱（死者/离线按UUID存）
                      无主人 或 开关=关 ──► 公共箱（机器吐出/无主/玩家关闭回收）
豁免清单物品（ignore.enabled=true 且属 sweep.ignore.items）──► 不收集，留在原地（原插件白名单 EXEMPT 模式语义）
```

- 私人 = **按玩家 UUID 分桶**；公共 = **全局单一列表**。存储物理分离，永不混淆。
- 每位玩家一个**统一回收开关**（同时管丢弃+死亡，见 §5.1）；开关关闭时其物品被扫到一律进公共箱。
- 丢弃与死亡掉落都保留**原版落地体验**：Q 丢物品由原版自动携带 `thrower`；死亡掉落开关开时逐件打死者标记。
- 扫地逻辑与原插件完全一致（逐世界逐物品遍历），唯一改变是"删除"变"分流"。
- **双清单**（旧白名单拆分）：`sweep.ignore.items` = **豁免清单**（`ignore.enabled` 控制，开启则清单物品不被收集）；`sweep.valuable-items` = **贵重清单**（不影响收集，只过滤到期提醒的"只提醒贵重"档）。旧键 `whitelisted-items`/`whitelist-mode`/`whitelist-enabled`/`bins.move-notify-exempt` 首次启动自动迁移（mode=EXEMPT → ignore.enabled=true，否则 valuable-enabled=true），迁移后删除。

---

## 4. 数据模型与存储

### 条目
```
BinEntry {
  String  id;          // 短UUID
  byte[]  item;        // ItemStack Base64（BukkitObjectOutputStream，保留附魔/lore/NBT/数量）
  UUID    owner;       // 私人箱才有
  String  ownerName;   // 显示用，可为空（无主）
  long    depositAt;   // 入箱时间（真实 epoch 毫秒）
  long    expireAt;    // depositAt + TTL
}
```

### 文件
```
plugins/FmKit/
  bins/private/<uuid>.yml   # 每个玩家一个文件，离线也可清理/流转
  bins/public.yml           # 公共
```
- **写入顺序**：存储为每箱组（私人/公共）单线程写入器；同一玩家的多次写入按提交顺序串行落盘（后写不丢旧数据），不同玩家互不阻塞。

### 时间与"游戏日"
- 1 游戏日 = 20 分钟真实时间（24000 tick）。
- **存储一律用真实时间戳**；配置按游戏日填写并换算 —— 不受 `/time set`、停服不推进世界时间影响。
- 默认：私人 `3 游戏日 ≈ 1 小时`，公共 `7 游戏日 ≈ 2.3 小时`。

### 可堆叠物品（同物品合并）
- 同箱内**同物品（类型+元数据）且同主人**自动合并为一条条目；**到期时间按最新一次存入续期**，**存入时间保留该组最早一次存入**；主人规则：无主只并无主、A 只并 A，跨主人永不合并。
- 合并后不超过该物品堆叠上限；组满后剩余开新组（新时间戳）。
- 私人与公共同规则；私人到期转公共时按同一主人规则并入公共同类组；**转公共只重启到期时间（公共 TTL），存入时间保留原始值**——存入时间在任何流程（合并/转公共）中都不续费。
- 取回：整组归还；背包装不下则拒绝取回（见 §8）。

---

## 5. 扫地/收集子系统（原创重写，行为等同原插件）

配置键兼容原插件，旧 config 可平滑迁移：

| 键 | 默认 | 行为 |
|---|---|---|
| `enabled` | true | 总开关 |
| `clean-interval` | 300 | 收集周期（秒） |
| `countdown-start` | 60 | 进入倒计时的剩余秒数 |
| `countdown-times` | [60,30,10,5,4,3,2,1] | 在这些剩余秒数广播提醒 |
| `sweep.valuable-enabled` | true | 贵重清单总开关；关闭时贵重集合为空 |
| `sweep.valuable-items` | 内置 152 种（钻石/绿宝石/金/下界合金/附魔书/鞘翅等） | 贵重清单：到期提醒"只提醒贵重"档的过滤名单，**不影响收集** |
| `sweep.ignore.enabled` | false | 豁免清单总开关 |
| `sweep.ignore.items` | 与贵重同 152 种 | 扫地豁免清单：清单物品不被收集、留在原地 |
| `clean-experience-orbs` | false | 经验球仍直接删除（不进箱） |
| `threshold-cleaning.enabled` | false | 掉落物超阈值提前收集 |
| `threshold-cleaning.threshold` | 500 | 阈值 |
| `threshold-cleaning.check-interval` | 30 | 检测周期（秒） |

任务结构：
- **cleanTask**：每 `clean-interval` 秒 → 遍历世界收集（分流进箱，不再 remove）→ **全部实体任务完成后**广播 `cleaned`（{n}=掉落物实体数（与原插件口径一致，豁免物品不计入），{m}=合计物品件数）。
- **countdownTask**：1 秒 tick，剩余时间命中 `countdown-times` 时广播（**与原插件一致：固定时间点必播，不管地上有没有垃圾**——玩家依赖固定节奏）。
- **thresholdTask**（可选）：周期统计全服掉落物数，超阈值立即触发一次收集。

> 广播实现注意：不用 `Bukkit.broadcast`。Folia 的 `PaperPermissionManager.calculatePermissionDefault`
> 在 `dirty=true` 的首次命中后提前返回，`bukkit.broadcast.user`（默认 TRUE）只进 op 桶、不进非 op 桶，
> 非 op 玩家从不订阅该权限 → 广播到不了普通玩家（控制台有回显）。`TextUtil.broadcast` 改为
> 遍历在线玩家逐一 `sendMessage` + 控制台，行为等价且不依赖订阅机制。

### 5.1 死亡掉落 = 玩家丢出（DeathDropHandler）

不做任何半径/时间窗扫描。死亡时直接让掉落物**携带死者 thrower**：

1. 监听 `PlayerDeathEvent`（高优先级）：`keepInventory=true` 跳过；死者"回收开关"为关也跳过（掉落纯原版、无标记 → 扫到归公共）。
2. 取走 `event.getDrops()` 并清空，逐件用 `world.dropItemNaturally(死亡点, stack)` 重新生成（保留原版散落效果），随后 `item.setThrower(死者UUID)`。
3. 之后一切走既有规则：他人捡走 → 正常；被扫地扫到 → `getThrower()=死者` → **死者私人箱**；3 游戏日后照常转公共。

**统一回收开关（管丢弃+死亡）**：每位玩家一个开关，指令 `/fmkit toggle [on|off]`（不带参数=查看），或在私人箱 GUI 顶行槽1点击切换（§7.2）。默认值由配置 `collect.default` 决定，存进该玩家私人箱文件；死亡标记、扫地分流两处都按它判定。
- 开关开：丢弃落地等扫地 → `thrower=本人` → 私人箱；死亡掉落打标记，扫到进私人箱。
- 开关关：丢弃/死亡纯原版落地；被扫到时一律进**公共箱**。
优点：零扫描、零误判、零特殊路径——所有落地物品走同一条扫地分流。

---

## 6. 流转与清理

| 事件 | 触发 | 动作 |
|---|---|---|
| 私人到期 | 每 `bins.expiry-scan-interval` 秒（默认 60，下限 5） | 按本人到期去向：`expiry-destroy` 开 → 直接销毁；否则移入公共箱。按本人提醒档（OFF/VALUABLE/ALL）通知，渠道 ACTIONBAR/CHAT，最多列 `notify-max-shown` 种物品名 |
| 到期临近 | 同上扫描 | 到期前 `expiry-warn-seconds` 秒（默认 60，0 关）提醒在线主人；每条每服务器生命周期仅一次 |
| 公共到期 | 同上扫描 | 删除 |
| 启动清理 | 公共箱加载时 | 移除已过期公共条目（日志"启动清理"）；私人已过期条目在启动后首次到期扫描流转/销毁 |
| 公共超容 | 入箱时 > `public-max-entries`(512) | 淘汰最旧条目 |
| 手动转公共 | GUI 右键 | 立即移入公共（跳过等待） |
| 手动销毁 | GUI Shift+右键 | 两步确认：首次提示"{s} 秒内再点一次确认销毁"，超时作废 |

---

## 7. GUI

### 7.1 选择页 `/fmkit`（9 格）
```
[3]=ENDER_CHEST 私人回收站(条数)   [5]=CHEST 公共回收站(条数)
```

### 7.2 私人回收站页（54 格；第 1 页 36 条，第 2 页起 45 条）
```
行0    1=回收开关 | 2=到期提醒 | 4=说明书 | 6=到期去向 | 7=到期预览 | 0/3/5/8=深色玻璃板（第 1 页行0不放条目）
行1-4  36 个条目卡片（9 列全用）
第2页起 行0-4 全部 45 格放条目（无说明书/按钮）
行5    45上一页 | 47页码牌 | 48切换回收站 | 49刷新 | 51排序 | 52全部取回 | 53下一页
```
- **槽1 回收开关**（仅第 1 页）：`LIME_CONCRETE`「回收：开」/ `RED_CONCRETE`「回收：关」，点击切换本人开关（同时管丢弃+死亡），立即生效并刷新本页。操作他人箱需 `fmkit.admin`。
- **槽2 到期提醒**（仅第 1 页）：三档点击循环：`RED_CONCRETE` 关 / `YELLOW_CONCRETE` 只提醒贵重 / `LIME_CONCRETE` 全提醒；贵重清单为空（`valuable-enabled` 关或清单空）时跳过中间档。
- **槽6 到期去向**（仅第 1 页）：`CHEST`「到期去向：转公共回收站」/ `FIRE_CHARGE`「到期去向：自动销毁」，点击切换本人私人条目到期处置。
- **槽7 到期预览**（仅第 1 页）：`SPYGLASS`，点击以消息列出本人 `expiry-preview-minutes`（实际分钟，默认 10）内将到期的条目，最多 9 行，其余"还有 N 件"。
- **槽4 说明书**（仅第 1 页）：lore 写共 N 条、容量上限（无限 / N 件）、页数上限（无限 / N 页）。
- **槽52 全部取回**（所有页）：按存入顺序跨页装入背包，装满即停；公共页同样有。
- **槽51 排序**：最先到期 → 最新 → 最旧 三态循环（私人箱默认最先到期）；公共页同款。
- 条目卡片 = 物品本体+数量；lore：丢弃时间、**每条各自**的转公共倒计时（**绿>半/黄/红临近**）、操作提示。
- 操作：**左键取回** · **右键立即转公共** · **Shift+右键销毁（两步确认）**。

### 7.3 公共回收站页（同布局）
- 条目 lore：原主人名（无主显示「无主」）、删除倒计时、`左键拿走 · 先到先得`。
- **左键拿走**；槽48 = `ENDER_CHEST`「切到私人回收站」；槽51 = `HOPPER` 排序三态切换；槽52 = 全部取回（同款）。
- 说明书（槽4）仅第 1 页；第 2 页起顶行 9 格全部为条目。
- 不提供转/销毁（公共是终点）。

### 7.4 公共细节
- lore 倒计时**打开时动态渲染**，不预存。
- 点击时**二次校验**（条目仍存在、未过期），防并发拿同一件。
- 音效可关；边框/标题/槽位/文案全进 config。
- 回收站是普通箱子 GUI，可正常关闭（无需 FmTerm 那种强制重开）。

### 7.5 取回与重渲染规则（1.1）
- **全部取回统一最旧优先**：公共/私人按存入时间全局排序（跨页），不再"当前页优先"。
- **满包不阻断**：全部取回遇到无剩余空间的条目跳过继续；一件都没取到才发 `bag-full`。
- **部分取回**：左键取回可堆叠物品时背包空间不足，能拿多少拿多少，条目数量相应减少（旧行为是整体取消）。
- **管理员查看**：`/fmkitadmin bin <player>` 打开他人私人箱，标题加「（管理员查看）」，到期提醒槽位标注箱主名。
- **1 秒差量刷新**：打开期间按 `gui.auto-refresh-seconds` 周期重渲染；条目 id 未变只更新倒计时行，内容变化才整槽重绘。
- 销毁音效只在两步确认实际销毁时播放。
- **同时间戳位置稳定**：同一轮清扫入箱的条目时间戳完全相同，所有排序（展示/全部取回/到期预览/公共箱存储序）追加条目 id 作为次序键，满包点击等"取出又放回"不再引起同时间戳条目位置跳动。
- **标题件数跟随刷新**：`private-title` / `private-title-viewing` / `public-title` 里的 `{n}` 原先只在开窗时算一次，取回/转公共/销毁/扫地入账后一直停在旧数字。现在标题文本每次 `render` 重算，与上次发送的文本做字符串比对，变了才用 `InventoryView#setTitle` **原地改标题**（同一 container id 重发开窗包，客户端不关窗、光标上的物品不会被关窗冲掉）；不重建 Inventory + `openInventory()`，那会分配新窗口 id，隐式关窗把光标物品冲回背包而点击自己的光标写入还没落地，导致拖拽中的一摞被复制。文本没变则一个包都不发，空闲刷新不闪。

---

## 8. 指令与权限

| 指令 | 权限 | 说明 |
|---|---|---|
| `/fmkit` | `fmkit.use`（默认 true） | 选择页 |
| `/fmkit private` | 同上 | 直达私人箱 |
| `/fmkit public` | 同上 | 直达公共箱 |
| `/fmkit toggle [on\|off]` | 同上 | 切换本人回收开关（丢弃+死亡是否进私人箱）；不带参数=查看当前设置 |
| `/fmkit help` | 同上 | 用法帮助 |
| `/fmkitadmin clearpublic` | `fmkit.admin`（默认 OP） | 清空公共：先报条数，{s} 秒内再敲确认（控制台可用） |
| `/fmkitadmin clear <player>` | 同上 | 清空指定玩家私人箱（**离线可清**，按 UUID） |
| `/fmkitadmin bin <player>` | 同上 | 打开他人私人箱 GUI（浏览/逐条销毁） |
| `/fmkitadmin reload` | 同上 | 热重载配置（等同原 /fnsd reload） |
| `/fmkitadmin sweep on\|off\|now` | 同上 | 运行期开关扫地（等同原 /fnsd toggle）；`now` 立即触发一次收集（等同原 /fnsd clean） |
| `/fmkitadmin interval <秒>` | 同上 | 修改清理间隔并**写入 config**（下限 5 秒，立即重排任务） |
| `/fmkitadmin whitelist [valuable\|ignore] [add\|remove <物品>\|clear\|on\|off]` | 同上 | 双清单管理（valuable=贵重提醒名单 / ignore=扫地豁免）；**写入 config**；无参查看两清单，`whitelist <清单>` 查看单个 |
| `/fmkitadmin toggle <player> on\|off` | 同上 | 代改他人回收开关（**离线可改**） |
| `/fmkitadmin notify <玩家> <off\|valuable\|all>` | 同上 | 代设他人到期提醒档（**离线可改**，Folia 异步 UUID 查找） |
| `/fmkitadmin destroy <玩家> <on\|off>` | 同上 | 代设他人到期去向（自动销毁/转公共）（**离线可改**） |
| `/fmkitadmin status` | 同上 | 运营统计：双箱条数/容量、最旧条目、开关开/关人数 |
| `/fmkitadmin help` | 同上 | 用法帮助 |

不做"清理指定物品/类型"管理指令 —— 用 `/fmkitadmin bin <player>` 进 GUI 逐条处理即可覆盖，避免冗余。

---

## 9. Folia 线程安全

- **收集**：逐实体在其 region/entity scheduler 上执行，不用 `Bukkit.getScheduler`。
- **周期任务**（倒计时/到期扫描）：`getGlobalRegionScheduler` / async scheduler。
- **存储 IO**：内存为主，落盘走 async；箱数据修改统一汇聚到单点，避免并发写。
- **GUI 回调**：取回/转公共等动作回到玩家所在 region 线程执行。

---

## 10. config.yml（完整草案）

```yaml
# ============ 扫地/收集（键名兼容原扫地插件） ============
sweep:
  enabled: true
  clean-interval: 300
  countdown-start: 60
  countdown-times: [60, 30, 10, 5, 4, 3, 2, 1]
  valuable-enabled: true
  valuable-items: [DIAMOND, EMERALD, GOLD_INGOT, ...]   # 内置 152 种：贵重清单，到期提醒"只提醒贵重"过滤，不影响收集
  ignore:
    enabled: false
    items: [DIAMOND, EMERALD, GOLD_INGOT, ...]          # 与贵重同 152 种：扫地豁免清单，清单物品不被收集
  # 旧键 whitelisted-items/whitelist-mode/whitelist-enabled 首启迁移到以上双清单（mode=EXEMPT → ignore.enabled=true），迁移后删除
  clean-experience-orbs: false
  threshold-cleaning:
    enabled: false
    threshold: 500
    check-interval: 30

# ============ 个人回收开关 ============
collect:
  enabled: true          # 总开关：私人箱收集（死亡标记+扫地分流）
  default: true          # 每位玩家回收开关初始值（/fmkit toggle 或 GUI 槽1 可改）

# ============ 回收站 ============
bins:
  private-ttl-days: 3        # 游戏日（1游戏日=20分钟）
  public-ttl-days: 7
  private-max-entries: 0     # 私人箱容量，0=不限；满则最旧条目提前转公共
  public-max-entries: 512    # 公共箱容量，满则淘汰最旧；0=不限（同私人）
  expiry-scan-interval: 60   # 到期流转/清理扫描周期（秒，下限 5）
  move-notify: VALUABLE      # 新玩家到期提醒初始档：OFF | VALUABLE | ALL（存量玩家保留自选）
  move-notify-mode: ACTIONBAR  # 提醒渠道：ACTIONBAR | CHAT
  notify-max-shown: 3        # 提醒消息最多列几种物品名，0=只报数量
  expiry-warn-seconds: 60    # 到期前 N 秒提醒在线主人（每条每服务器周期一次），0=关
  expiry-preview-minutes: 10 # 槽7 到期预览窗口（实际分钟）
  expiry-destroy: false      # 新玩家到期去向初始值：true=销毁 / false=转公共
  destroy-confirm-seconds: 3 # 销毁两步确认窗口（秒）
  log:                         # 控制台箱日志（仅后台输出，不打扰玩家）
    overflow: episode          # off|episode|window|each  容量溢出（私人转公共/公共淘汰）；episode=每次溢出期只记一行
    public-expire: off         # off|window|each          公共箱到期清理；window=每 60 秒聚合成一行
    private-expire: off        # off|window|each          私人箱到期转公共（到期销毁不受此开关控制，总是记录）
    sweep-deposit: off         # off|window|each          扫地入库汇总（每轮一行）

# ============ GUI ============
gui:
  sounds: true
  auto-refresh-seconds: 1      # GUI 打开期间自动重绘周期（秒）；0=关闭
  frame-material: GRAY_STAINED_GLASS_PANE   # 边框
  dark-bar-material: BLACK_STAINED_GLASS_PANE   # 第 1 页顶行深色板
  icons:                                     # 各按钮/标记图标，均可换
    banner: WRITABLE_BOOK                    # 顶部标题书
    toggle-on: LIME_CONCRETE                 # 回收开关·开
    toggle-off: RED_CONCRETE                 # 回收开关·关
    notify-valuable: YELLOW_CONCRETE           # 到期提醒·只提醒贵重
    expiry-to-public: CHEST                    # 到期去向·转公共
    expiry-destroy: FIRE_CHARGE                # 到期去向·销毁
    preview: SPYGLASS                          # 到期预览
    switch-to-public: CHEST                  # 私人页→公共
    switch-to-private: ENDER_CHEST           # 公共页→私人
    prev-page: ARROW
    next-page: ARROW
    page-indicator: PAPER
    refresh: CLOCK
    take-all: LIME_SHULKER_BOX
    sort: HOPPER
    hub-private: ENDER_CHEST                 # 选择页·私人入口
    hub-public: CHEST                        # 选择页·公共入口

# ============ 管理 ============
admin:
  clearpublic-confirm-seconds: 10   # 清空公共的二步确认窗口（秒）

# ============ 文案（MiniMessage） ============
messages:
  prefix: "<gray>[<aqua>回收站</aqua>]</gray> "
  countdown: "<red><bold>警告：</bold></red><white>{s} 秒后将开始扫地，掉落物会收入回收站</white>"
  cleaned: "<green>✔ 扫地完成：</green><white>收集了 <aqua>{n}</aqua> 个掉落物（共 <aqua>{m}</aqua> 件物品）进回收站</white>"
  bag-full: "背包空间不足，取回已取消"
  no-permission: "没有权限"
  destroy-confirm: "<red>{s} 秒内再次 Shift+右键 确认销毁</red>"
  move-notify: "你回收站里的 {items} 已被移入公共回收站"
  toggle-on: "<green>回收已开启：丢弃/死亡掉落将进入你的私人回收站</green>"
  toggle-off: "<yellow>回收已关闭：丢弃/死亡掉落原版落地，扫到将进公共箱</yellow>"
  clearpublic-confirm: "<red>公共箱共 {n} 条，{s} 秒内再执行一次以确认清空</red>"
  clearpublic-done: "<green>已清空公共回收站，共 {n} 条</green>"
  clear-player-done: "<green>已清空 {player} 的私人回收站，共 {n} 条</green>"
  public-taken: "<green>已从公共回收站取走物品</green>"
  hub-title: "<aqua><bold>回收站</bold></aqua>"
  private-title: "<green><bold>你的回收箱</bold></green> <gray>·</gray> <white>{n}</white> 件"
  public-title: "<gold><bold>公共回收箱</bold></gold> <gray>·</gray> <white>{n}</white> 件"
  lore-drop-time: "<gray>丢弃时间：{time}</gray>"
  lore-expiry: "<gray>{t} 后转入公共</gray>"
  lore-delete: "<gray>{t} 后删除</gray>"
  lore-owner: "<gray>原主人：{name}</gray>"
  lore-no-owner: "<gray>原主人：无主</gray>"
  lore-hint-private: "<dark_gray>左键取回 · 右键立即转公共 · Shift右键销毁</dark_gray>"
  lore-hint-public: "<dark_gray>左键拿走 · 先到先得</dark_gray>"
  expiry-destroyed: "你回收站里的 {items} 已到期销毁"
  # 以下新增键在 Settings.FALLBACK_MESSAGES 有默认，config 缺省亦可：
  # sweep-now, interval-set, list-header/list-empty/list-add/list-add-dup/list-remove/list-remove-missing/
  # list-clear/list-bad-item/list-on/list-off, notify-set, destroy-set, notify-on/notify-off/notify-valuable,
  # expiry-mode-destroy/expiry-mode-public, taken-back, taken-all-public, taken-all-private, private-title-viewing, expiry-warn-public/expiry-warn-destroy,
  # preview-name/preview-lore/preview-title/preview-line/preview-more/preview-empty/preview-dest-public/preview-dest-destroy
```

---

## 11. 项目结构

```
FmKit/
  pom.xml                      # paper-api provided，无 shade（依赖声明用）
  build.ps1                    # javac --release 21 + jar uf 增量打包（实际构建，免 Maven）
  src/main/java/dev/fm/kit/
    FmKitPlugin.java           # 入口：加载存储、起任务、注册指令
    Settings.java              # 配置访问 + 文案兜底 + 旧白名单迁移
    cleaner/
      SweepScheduler.java      # 周期收集（按主人回收开关分流进箱）+ 倒计时/阈值任务
      DeathDropHandler.java    # 死亡掉落重新生成并打上死者 thrower（尊重本人开关）
    bin/
      BinEntry.java            # 条目记录（id/物品/主人/存入/到期…）
      PrivateBin.java          # 单人箱对象
      PrivateBinStore.java     # per-UUID 文件（含回收开关/提醒档/到期去向）
      PublicBinStore.java      # 全局 + 容量上限 + 启动过期清理
      BinMerge.java            # 同物品同主人可堆叠条目合并
      BinExpiryTask.java       # 到期流转/销毁 + 临近到期一次性预警
      NotifyMode.java          # OFF | VALUABLE | ALL
    gui/
      HubMenu.java             # 选择页
      PrivateGui.java          # 私人页（开关/提醒档/去向/预览/取回/转公共/销毁）
      PublicGui.java           # 公共页（拿走/全部取回/排序）
      GuiBase.java             # 槽位常量/边框/分页/渲染公共逻辑
      GuiSession.java          # 会话状态（target/page/sort）
      GuiListener.java         # 点击路由
    command/
      FmKitCommand.java
      FmKitAdminCommand.java
    util/
      ItemNames.java           # ItemStack → 中文显示名
      TimeUtil.java            # 游戏日↔毫秒换算、倒计时格式化
      TextUtil.java            # MiniMessage
  src/main/resources/plugin.yml, config.yml
```

---

## 12. 边界规则汇总

| 场景 | 处理 |
|---|---|
| 玩家丢弃 | 原版落地不拦截：开关开 → 扫到进私人箱（`thrower`=本人）；开关关 → 扫到进公共 |
| 死亡掉落 | 死者开关开：落地打标记，扫到进**死者私人箱**；开关关：纯原版→最终公共 |
| 机器/漏斗吐出 | 无 thrower → 公共 |
| 取回时背包满 | **拒绝取回**并提示，物品留箱，不落地 |
| 豁免清单物品（sweep.ignore） | 不收集、留在原地（原插件白名单 EXEMPT 语义）；贵重清单只过滤到期提醒，不影响收集 |
| 玩家离线 | 私人箱照常流转/过期/被管理清理（UUID 键） |
| 重载/卸载 | 箱数据在磁盘保留，重载后恢复 |
| 经验球 | 按开关直接删除，不进箱 |
| 方块破坏/农场掉落 | 无 thrower → 公共（农场产出进公共箱而非被删） |
| 存档文件损坏 | 告警 + 改名 .bak 隔离，不影响其余数据与启动 |

---

## 13. 验证（自动化套件，替代手工条目）

手工验证由 `bot/fmkit_test.js` 取代（mineflayer 机器人 + RCON 控制台驱动，一次性自检，输出 PASS/FAIL/FATAL）：

1. 起服务端（26.1）：`cd servers/folia261 && java -Xmx2G -jar folia-26.1.2-8.jar nogui`（游戏端口 25567，RCON 25575）；
2. 跑套件：`cd bot && node fmkit_test.js`；测 26.2 用 `PORT=25568 RCON_PORT=25576 MC_VERSION=26.2`（对应 `servers/folia262`）。

每轮运行前以基线步骤复位状态（op + `clearpublic` 两步 + `notify <bot> valuable` + `destroy <bot> off`），消除跨轮残留。覆盖约 50 组断言：FmTerm 接受与回收开关/死亡掉落分流（T0、I1-I7）；双清单 add/remove/clear/on/off 与持久化、`sweep now`（I12 族）；私人箱翻页/取回/两步销毁/状态（I27-I29、N6/N7）；到期提醒三档 + GUI 图标颜色 + 管理 `notify`/`destroy` 代设 + 新玩家默认档（V0-V3、V7、A1-A5）；公共箱 TTL 过期清除与无主物品清扫（8d、V2/V2b）；全部取回与堆叠合并规则（I39-I40、V4-V6）；倒计时与 `interval` 持久化（I38）；`clearpublic` 两步确认（I8 族）；`bin`/`clear`/`status`/`toggle` 离线操作与无权限拒绝（I9、I34-I35）；分页（I36-I37）；`reload` 后配置持久化（I40d）。

---

**以上为与实现对齐的方案基线（v1.1）。**
