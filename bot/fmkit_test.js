const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25567', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.1'
const RCON_PORT = parseInt(process.env.RCON_PORT || '25575', 10)
const BOT_NAME = process.env.BOT_NAME || 'FmTester'
const FILLER = 'gray_stained_glass_pane'

// Protocol/version shims for MC versions newer than minecraft-data/mineflayer data.
require('./mc_shim')(VERSION)

const mineflayer = require('mineflayer')
const net = require('net')

const results = []
function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`)
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }

function waitFor(cond, timeoutMs = 8000, poll = 100) {
  const start = Date.now()
  return new Promise(resolve => {
    const iv = setInterval(() => {
      let v = false
      try { v = cond() } catch { v = false }
      if (v) { clearInterval(iv); resolve(v) }
      else if (Date.now() - start > timeoutMs) { clearInterval(iv); resolve(null) }
    }, poll)
  })
}

// ---- Source RCON client (console commands) ----
function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    const send = (id, type, body) => {
      const blen = Buffer.byteLength(body)
      const b = Buffer.alloc(14 + blen)
      b.writeInt32LE(b.length - 4, 0)
      b.writeInt32LE(id, 4)
      b.writeInt32LE(type, 8)
      b.write(body, 12)
      sock.write(b)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout: ' + cmd)) }, 6000)
    sock.on('connect', () => send(1, 3, RCON_PASS))
    sock.on('data', d => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4) {
        const len = buf.readInt32LE(0)
        if (buf.length < 4 + len) break
        const id = buf.readInt32LE(4)
        const type = buf.readInt32LE(8)
        const body = buf.slice(10, 4 + len - 2).toString()
        buf = buf.slice(4 + len)
        if (type === 2 && id === 1) {
          send(2, 2, cmd)
        } else if (type === 0 && id === 2) {
          clearTimeout(timer); sock.end(); resolve(body)
        } else if (type === 2 && id === -1) {
          clearTimeout(timer); sock.end(); reject(new Error('rcon auth failed'))
        }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}

// ---- GUI helpers ----
// v3 layout: page 1 item area = rows 1-4 all 9 columns (36); page 2+ = rows 0-4 (45, top row all items).
const PAGE0_SLOTS = []
for (const row of [1, 2, 3, 4]) for (let col = 0; col < 9; col++) PAGE0_SLOTS.push(row * 9 + col)
const PAGE_N_SLOTS = []
for (const row of [0, 1, 2, 3, 4]) for (let col = 0; col < 9; col++) PAGE_N_SLOTS.push(row * 9 + col)

function entrySlots(win, page = 0) {
  const slots = page === 0 ? PAGE0_SLOTS : PAGE_N_SLOTS
  return slots.filter(s => win.slots[s] != null && win.slots[s].name !== FILLER)
}

async function openGui(bot, cmd) {
  if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch { } await sleep(150) }
  bot.messages.length = 0
  bot.chat(cmd)
  const win = await waitFor(() => bot.currentWindow, 8000)
  if (win) await sleep(350)
  return win
}

function closeGui(bot) {
  if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch { } }
}

/** Reopen private GUI until predicate on entry count holds. */
async function privateEntriesWait(bot, pred, timeoutMs = 30000) {
  const start = Date.now()
  let last = -1
  while (Date.now() - start < timeoutMs) {
    const win = await openGui(bot, '/fmkit private')
    last = win ? entrySlots(win).length : -1
    closeGui(bot)
    await sleep(150)
    if (win && pred(last)) return last
    await sleep(1200)
  }
  return last
}

async function publicEntriesWait(bot, pred, timeoutMs = 30000) {
  const start = Date.now()
  let last = -1
  while (Date.now() - start < timeoutMs) {
    const win = await openGui(bot, '/fmkit public')
    last = win ? entrySlots(win).length : -1
    closeGui(bot)
    await sleep(150)
    if (win && pred(last)) return last
    await sleep(1200)
  }
  return last
}

/** Sum entries across ALL private pages; poll until predicate on total holds. */
async function privateTotalWait(bot, pred, timeoutMs = 40000) {
  const start = Date.now()
  let last = -1
  while (Date.now() - start < timeoutMs) {
    const win = await openGui(bot, '/fmkit private')
    if (win) {
      let total = entrySlots(win).length
      let cap = 36
      let guard = 0
      let page = 1
      while (bot.currentWindow && entrySlots(bot.currentWindow, page - 1).length === cap && guard++ < 20) {
        await click(bot, 53, 0, 0)
        if (!bot.currentWindow) break
        page++
        cap = 45
        total += entrySlots(bot.currentWindow, page - 1).length
      }
      last = total
      closeGui(bot)
      if (pred(total)) return total
    }
    await sleep(1200)
  }
  return last
}

function echoOf(bot, text) {
  return bot.messages.some(m => m.includes(text))
}

function groundItemCount(bot) {
  return Object.values(bot.entities).filter(e => e.name === 'item').length
}

function invCount(bot, name) {
  return bot.inventory.items().filter(i => i.name === name).reduce((a, i) => a + i.count, 0)
}

async function giveDirt(bot, n) {
  await rcon(`give ${BOT_NAME} minecraft:dirt ${n}`)
  await waitFor(() => invCount(bot, 'dirt') >= n, 6000)
}

async function toss(bot, name) {
  const item = bot.inventory.items().find(i => i.name === name)
  if (!item) throw new Error('no item to toss: ' + name)
  await bot.toss(item.type, null, 1)
}

async function click(bot, slot, mode, button) {
  try { await bot.clickWindow(slot, button, mode) } catch (e) { console.log('  click err', slot, e.message) }
  await sleep(180)
}
// 窗口内物品展示载荷序列化：新版组件存 components，旧版存 nbt
function itemBlob(win, slot) {
  const it = win && win.slots ? win.slots[slot] : null
  if (!it) return ''
  const payload = it.components != null ? it.components : it.nbt
  return JSON.stringify(payload == null ? '' : payload)
}

// ---- Server config helpers（双清单开关 + TTL 补丁 + 持久化检查）----
const fs = require('fs'), path = require('path')
const SRV_DIR = PORT === 25567 ? 'folia261' : 'folia'
const cfgPath = path.join(__dirname, '..', 'servers', SRV_DIR, 'plugins', 'FmKit', 'config.yml')
const cfgText = () => fs.promises.readFile(cfgPath, 'utf8')
async function patchCfg(re, rep) {
  const t = await cfgText()
  if (!re.test(t)) throw new Error('cfg pattern miss: ' + re)
  await fs.promises.writeFile(cfgPath, t.replace(re, rep), 'utf8')
  await rcon('fmkitadmin reload')
  await sleep(600)
}
const setIgnoreEnabled = on => patchCfg(/(ignore:[\s\S]*?enabled:\s*)\w+/, '$1' + (on ? 'true' : 'false'))
const setValuableEnabled = on => patchCfg(/(valuable-enabled:\s*)\w+/, '$1' + (on ? 'true' : 'false'))
const setPublicTtl = days => patchCfg(/(public-ttl-days:\s*)[\d.]+/, '$1' + days)
// 键值补丁：键已存在则整行替换（保留原缩进）；不存在（旧配置无 gui 段）则挂到 gui: 下，连段都没有就整段追加
async function patchCfgKV(kvs) {
  let t = await cfgText()
  for (const [k, v] of Object.entries(kvs)) {
    const re = new RegExp('^(\\s*)' + k + ':\\s*[^\\n]*', 'm')
    if (re.test(t)) { t = t.replace(re, (_m, ind) => `${ind}${k}: ${v}`); continue }
    if (/^gui:/m.test(t)) t = t.replace(/^(gui:[^\n]*)/m, `$1\n  ${k}: ${v}`)
    else t += `\ngui:\n  ${k}: ${v}\n`
  }
  await fs.promises.writeFile(cfgPath, t, 'utf8')
  await rcon('fmkitadmin reload')
  await sleep(600)
}
// 清单段：从 header（如 'valuable-items:' / 'ignore:'）到下一个非清单内容；持久化验证用
const listSection = (t, header) => {
  const i = t.indexOf(header)
  if (i < 0) return ''
  const lines = t.slice(i).split('\n')
  let out = lines[0]
  for (let k = 1; k < lines.length; k++) {
    if (/^\s*-/.test(lines[k]) || /^\s*$/.test(lines[k]) || /^\s*#/.test(lines[k]) || /^\s*(enabled|items):/.test(lines[k])) { out += '\n' + lines[k]; continue }
    break
  }
  return out
}

async function scenario() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.messages = []
  bot.on('messagestr', msg => { bot.messages.push(msg); if (msg.includes('开始扫地')) bot.sawCountdown = true })
  bot.on('death', () => { try { bot._client.write('client_command', { actionId: 0 }) } catch { } })

  await new Promise(res => bot.once('spawn', res))
  record('T0 join', true, 'spawned')

  // ---- FmTerm compat: if the server runs FmTerm and KitBot hasn't accepted yet,
  // commands get intercepted with the terms menu. Detect & accept (lime_concrete). ----
  for (let attempt = 0; attempt < 3; attempt++) {
    const w = await openGui(bot, '/fmkit private')
    if (!w) break
    const s4 = w.slots[4]
    if (s4 && s4.name === 'writable_book') { closeGui(bot); break } // FmKit GUI -> already accepted
    let li = w.slots.findIndex(s => s && s.name === 'lime_concrete')
    if (li < 0 || li >= 54) li = 51 // FmTerm accept slot; names may not resolve pre-remap
    if (!w.slots[li]) { closeGui(bot); break } // unknown menu; don't loop
    await click(bot, li, 0, 0) // accept terms
    await sleep(800)
  }

  // ---- helpers for no-interception flow: drops land, sweep routes them ----
  const S = { x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z }
  const uuidInts = (() => {
    const h = require('crypto').createHash('md5').update('OfflinePlayer:' + BOT_NAME).digest()
    h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
    const hex = h.toString('hex')
    const f = s => parseInt(s, 16) | 0
    return [f(hex.slice(0, 8)), f(hex.slice(8, 16)), f(hex.slice(16, 24)), f(hex.slice(24, 32))].join(',')
  })()
  async function away() { await rcon(`tp ${BOT_NAME} ${Math.floor(S.x) + 40} ${Math.floor(S.y) + 3} ${Math.floor(S.z) + 40}`); await sleep(300) }
  async function backHome() { await rcon(`tp ${BOT_NAME} ${S.x} ${S.y} ${S.z}`); await sleep(300) }
  async function sweepWait() { bot.messages.length = 0; return waitFor(() => echoOf(bot, '收集了'), 30000) }
  // distinct materials so entries don't merge (merge semantics tested separately)
  const PALETTE = ['dirt', 'stone', 'cobblestone', 'granite', 'diorite', 'andesite', 'sand', 'gravel', 'oak_log', 'spruce_log', 'birch_log', 'jungle_log', 'acacia_log', 'dark_oak_log', 'cherry_log', 'mangrove_log', 'oak_planks', 'spruce_planks', 'birch_planks', 'jungle_planks', 'acacia_planks', 'dark_oak_planks', 'sandstone', 'red_sandstone', 'stone_bricks', 'mossy_stone_bricks', 'cracked_stone_bricks', 'bricks', 'mud_bricks', 'deepslate', 'cobbled_deepslate', 'polished_deepslate', 'deepslate_bricks', 'deepslate_tiles', 'tuff', 'calcite', 'dripstone_block', 'basalt', 'blackstone', 'netherrack']
  async function summonOwned(cnt) {
    for (let i = 0; i < cnt; i++) {
      const resp = await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~${5 + i} {Item:{id:"minecraft:${PALETTE[i % PALETTE.length]}",count:1},Thrower:[I;${uuidInts}]}`)
      if (i === 0) console.log('  summon sample:', String(resp).slice(0, 100))
    }
  }
  async function summonSame(cnt, mat = 'dirt') {
    for (let i = 0; i < cnt; i++) {
      // dz >= 5: items summoned closer land inside the bot's pickup range and vanish instantly
      await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~${(i % 10) - 5} ~1 ~${Math.floor(i / 10) + 5} {Item:{id:"minecraft:${mat}",count:1},Thrower:[I;${uuidInts}]}`)
    }
  }

  // Test environment: no mobs, no leftover ground items from prior runs
  await rcon('difficulty peaceful')
  await rcon('kill @e[type=item]')
  await rcon('gamerule doMobSpawning false')
  await rcon('kill @e[type=minecraft:chicken]')
  await rcon(`clear ${BOT_NAME}`)
  await sleep(500)

  await giveDirt(bot, 64)
  await rcon(`give ${BOT_NAME} minecraft:diamond 1`)
  await sleep(500)

  // ---- Item 1: Q 丢弃 → 落地 → 扫地收进私人箱 ----
  await toss(bot, 'dirt')
  await away()
  const t1 = await privateTotalWait(bot, t => t === 1)
  await backHome()
  let win = await openGui(bot, '/fmkit private')
  let n = win ? entrySlots(win).length : -1
  record('I1 drop -> swept -> private bin (1 entry)', win && n === 1, `entries=${n} waited=${t1}`)

  // ---- take-back via left click ----
  const dirtBefore = invCount(bot, 'dirt')
  const firstSlot = win ? entrySlots(win)[0] : -1
  if (win && firstSlot >= 0) {
    await click(bot, firstSlot, 0, 0)      // item -> cursor
    await click(bot, 81, 0, 0)             // cursor -> hotbar slot
  }
  await sleep(300)
  record('I2 left-click take back', invCount(bot, 'dirt') === dirtBefore + 1, `dirt=${invCount(bot, 'dirt')} (was ${dirtBefore})`)
  win = await openGui(bot, '/fmkit private')
  record('I3 private empty after take', win && entrySlots(win).length === 0, `entries=${win ? entrySlots(win).length : -1}`)
  closeGui(bot)

  // ---- 基线：管理员强制恢复提醒档/到期去向 + 清空公共箱，消除跨运行残留 ----
  await rcon(`op ${BOT_NAME}`)
  await rcon('fmkitadmin clearpublic')
  await rcon('fmkitadmin clearpublic')   // 二次确认式清空（第一次只提示确认）
  await rcon(`fmkitadmin notify ${BOT_NAME} valuable`)
  await rcon(`fmkitadmin destroy ${BOT_NAME} off`)
  await sleep(500)
  // ---- 基线档=VALUABLE（admin 强制）；TTL 测试前切到 ALL 保证泥土提醒可观察 ----
  win = await openGui(bot, '/fmkit private')
  record('V0 baseline expiry-notify VALUABLE (yellow icon)', win && win.slots[2] && win.slots[2].name === 'yellow_concrete',
    `slot2=${win && win.slots[2] ? win.slots[2].name : 'null'}`)
  if (win) {
    bot.messages.length = 0
    await click(bot, 2, 0, 0)   // VALUABLE -> ALL
    await waitFor(() => echoOf(bot, '到期提醒已开启'), 3000)
  }
  closeGui(bot)

  // ---- Item 5: TTL 到期 → 自动转公共 + 通知 ----
  bot.messages.length = 0
  await toss(bot, 'dirt')
  await away()
  const t4 = await privateTotalWait(bot, t => t === 1)
  await backHome()
  win = await openGui(bot, '/fmkit private')
  record('I4 TTL: entry deposited (after sweep)', win && entrySlots(win).length === 1, `entries=${win ? entrySlots(win).length : -1} waited=${t4}`)
  closeGui(bot)
  const moved = await waitFor(() => echoOf(bot, '移入公共回收站'), 45000)
  record('I5 TTL: expiry moved to public + notify', !!moved && bot.messages.some(m => /你回收站里的 \S+.*已被移入公共回收站/.test(m)), `msgs=${bot.messages.slice(-4).join(' | ').slice(0, 160)}`)
  const privAfter = await privateEntriesWait(bot, x => x === 0, 10000)
  record('I6 TTL: private now empty', privAfter === 0, `entries=${privAfter}`)
  const pubAfter = await publicEntriesWait(bot, x => x >= 1, 10000)
  record('I7 TTL: public has the entry', pubAfter >= 1, `entries=${pubAfter}`)

  // ---- Item 4: 扫地忽略清单开启（命中的地面物品不扫）：召唤的无主钻石经历扫地后留在地面 ----
  await setIgnoreEnabled(true)
  bot.messages.length = 0
  const sawCountdown = await waitFor(() => echoOf(bot, '将开始扫地'), 30000)
  await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~5 {Item:{id:"minecraft:diamond",count:1}}`)
  await sleep(500)
  const groundBefore = groundItemCount(bot)
  const sawClean = await waitFor(() => echoOf(bot, '收集了'), 30000)
  record('I8 sweep broadcast (countdown+cleaned)', !!sawCountdown && !!sawClean, `msgs=${bot.messages.filter(m => m.includes('扫地') || m.includes('收集了')).slice(-3).join(' | ').slice(0, 160)}`)
  await sleep(1500)
  const diamondLeft = Object.values(bot.entities).some(e => e.name === 'item')
  record('I9 ignore ON: exempt diamond stays on ground', diamondLeft && groundBefore >= 1, `groundEntities=${groundItemCount(bot)} (was ${groundBefore})`)
  await setIgnoreEnabled(false)
  // 开关联动：已存提醒档不随开关变化；贵重总开关关闭 → 提醒循环跳过 VALUABLE 档
  await setValuableEnabled(false)
  win = await openGui(bot, '/fmkit private')
  record('V2 stored notify (ALL) untouched by switches', win && win.slots[2] != null && win.slots[2].name === 'lime_concrete',
    `slot2=${win && win.slots[2] ? win.slots[2].name : 'null'}`)
  if (win) {
    bot.messages.length = 0
    await click(bot, 2, 0, 0)   // ALL -> OFF
    await waitFor(() => echoOf(bot, '到期提醒已关闭'), 3000)
    bot.messages.length = 0
    await click(bot, 2, 0, 0)   // OFF -> 贵重清单已关，应直接跳到 ALL
    const skip = await waitFor(() => echoOf(bot, '到期提醒已开启'), 3000)
    record('V1 valuable OFF: notify cycle skips VALUABLE (OFF->ALL)', !!skip && !echoOf(bot, '只提醒贵重'),
      `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 120)}`)
  }
  closeGui(bot)
  await setValuableEnabled(true)
  // ---- V3: 新玩家默认提醒档=VALUABLE（黄色图标）。主机器人 bin 早已建立，用临时探针机器人（无存档）验证默认值 ----
  const probe = mineflayer.createBot({ host: HOST, port: PORT, username: 'FmProbe', version: VERSION, auth: 'offline' })
  probe.messages = []
  await new Promise(res => probe.once('spawn', res))
  for (let a = 0; a < 3; a++) { // FmTerm terms accept if intercepted (same loop as main bot)
    const w = await openGui(probe, '/fmkit private')
    if (!w) break
    if (w.slots[4] && w.slots[4].name === 'writable_book') { closeGui(probe); break } // FmKit GUI -> accepted
    let li = w.slots.findIndex(s => s && s.name === 'lime_concrete')
    if (li < 0 || li >= 54) li = 51
    if (!w.slots[li]) { closeGui(probe); break }
    await click(probe, li, 0, 0)
    await sleep(800)
  }
  const pw = await openGui(probe, '/fmkit private')
  record('V3 fresh player defaults to VALUABLE (yellow icon)', pw && pw.slots[2] != null && pw.slots[2].name === 'yellow_concrete',
    `slot2=${pw && pw.slots[2] ? pw.slots[2].name : 'null'}`)
  closeGui(probe)
  probe.quit()

  // ---- Item 2: 死亡掉落 → 死者私人箱（远端死亡，避免重生捡回） ----
  await away()
  const stacksAtDeath = bot.inventory.items().length
  await rcon(`kill ${BOT_NAME}`)
  await waitFor(() => bot.health > 0, 15000)
  await sleep(500)
  const deathEntries = await privateEntriesWait(bot, x => x >= 1, 30000)
  record('I10 death drops -> deceased private bin', deathEntries >= 1, `entries=${deathEntries} stacksAtDeath=${stacksAtDeath}`)
  await backHome()

  // ---- Item 3: toggle off → 落地 → 扫进公共 ----
  await giveDirt(bot, 8)
  bot.messages.length = 0
  bot.chat('/fmkit toggle off')
  await waitFor(() => echoOf(bot, '回收已关闭'), 5000)
  await toss(bot, 'dirt')
  await sleep(1500)
  record('I11 toggle off: drop hits ground', groundItemCount(bot) >= 1, `ground=${groundItemCount(bot)}`)
  await away()
  const swept = await waitFor(() => echoOf(bot, '收集了'), 30000)
  record('I12 toggle off: swept', !!swept)
  const pubAfterOff = await publicEntriesWait(bot, x => x >= 1, 10000)
  record('I13 toggle off: landed in PUBLIC', pubAfterOff >= 1, `publicEntries=${pubAfterOff}`)
  await backHome()
  bot.chat('/fmkit toggle on')
  await waitFor(() => echoOf(bot, '回收已开启'), 5000)
  record('I14 toggle back on', echoOf(bot, '回收已开启'))

  // ---- Item 6: 公共箱容量淘汰（上限 3；用 4 种不同无主物品触发，同款会合并不触发淘汰）----
  bot.messages.length = 0
  const evictMats = ['cobblestone', 'granite', 'diorite', 'andesite']
  for (let i = 0; i < evictMats.length; i++) {
    await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~${5 + i} {Item:{id:"minecraft:${evictMats[i]}",count:1}}`)
  }
  const sweep2 = await waitFor(() => echoOf(bot, '收集了'), 30000)
  record('I15 eviction: overflow swept', !!sweep2)
  // 此前公共箱=1 条（I5+I13 合并的 KitBot 泥土）；+4 条不同无主 = 5 → 淘汰最旧 2 条（泥土组+圆石）
  // 扫地可能在召唤途中触发：残余物品下一轮才入箱，故轮询 GUI 直到稳定为期望集合
  let capNames = []
  {
    const cStart = Date.now()
    while (Date.now() - cStart < 45000) {
      const wc = await openGui(bot, '/fmkit public')
      capNames = wc ? entrySlots(wc).map(s => wc.slots[s].name).sort() : []
      closeGui(bot)
      if (capNames.join(',') === 'andesite,diorite,granite') break
      await sleep(1500)
    }
  }
  record('I16 public capped at max-entries=3', capNames.join(',') === 'andesite,diorite,granite',
    `items=${capNames.join(',') || 'none'}`)
  // take one from public
  win = await openGui(bot, '/fmkit public')
  if (win && entrySlots(win).length > 0) {
    bot.messages.length = 0
    const s = entrySlots(win)[0]
    const mat = win.slots[s].name
    await click(bot, s, 0, 0)
    await click(bot, 81, 0, 0)
    await sleep(300)
    record('I17 public take', (echoOf(bot, '已从公共回收站取走') && invCount(bot, mat) >= 1), `took=${mat} inv=${invCount(bot, mat)}`)
  } else {
    record('I17 public take', false, 'no public entries')
  }
  closeGui(bot)
  bot.chat('/fmkit toggle on')
  await waitFor(() => echoOf(bot, '回收已开启'), 5000)

  // ---- Item 7: 右键转公共 / Shift右键销毁 / 切换按钮 / 全部取回 ----
  // drain leftover entries (e.g. death-test residue) so exactly 3 fresh entries follow
  win = await openGui(bot, '/fmkit private')
  if (win && entrySlots(win).length > 0) { await click(bot, 52, 0, 0); await sleep(400) }
  closeGui(bot)
  await summonOwned(3)
  const t18 = await privateTotalWait(bot, t => t === 3)
  win = await openGui(bot, '/fmkit private')
  const slots3 = win ? entrySlots(win) : []
  record('I18 three deposits in private', slots3.length === 3, `entries=${slots3.length} waited=${t18}`)
  if (win && slots3.length === 3) {
    bot.messages.length = 0
    await click(bot, slots3[0], 0, 1)   // right-click -> move to public now
    const movedMsg = await waitFor(() => echoOf(bot, '已移入公共回收站'), 3000)
    record('I19 right-click move-to-public', !!movedMsg && bot.messages.some(m => /已移入公共回收站：\S/.test(m)), `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 120)}`)
    win = await openGui(bot, '/fmkit private')
    record('I20 private now 2', win && entrySlots(win).length === 2, `entries=${win ? entrySlots(win).length : -1}`)
    if (win) {
      bot.messages.length = 0
      const s = entrySlots(win)[0]
      await click(bot, s, 1, 1)
      const confirm = await waitFor(() => echoOf(bot, '确认销毁'), 3000)
      record('I21 shift-right asks destroy confirm', !!confirm, `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 120)}`)
      win = await openGui(bot, '/fmkit private')
      const s2 = win ? entrySlots(win)[0] : -1
      if (win && s2 >= 0) {
        await click(bot, s2, 1, 1)
        const destroyedMsg = await waitFor(() => echoOf(bot, '已销毁：'), 3000)
        record('I22 second shift-right destroys', !!destroyedMsg)
      } else {
        record('I22 second shift-right destroys', false, 'entry gone before confirm')
      }
      win = await openGui(bot, '/fmkit private')
      record('I23 private now 1 after destroy', win && entrySlots(win).length === 1, `entries=${win ? entrySlots(win).length : -1}`)
      if (win) {
        const es24 = entrySlots(win)
        const mat24 = es24.length ? win.slots[es24[0]].name : null
        const cntBefore = mat24 ? invCount(bot, mat24) : -1
        await click(bot, 52, 0, 0)
        await sleep(400)
        // take-all may merge into an existing stack -> assert item count, not stack count
        record('I24 take-all returns entries', mat24 !== null && invCount(bot, mat24) === cntBefore + 1, `${mat24} ${cntBefore}->${mat24 ? invCount(bot, mat24) : '?'}`)
      }
    }
  } else {
    record('I19-I24 skipped', false, 'not enough entries')
  }
  // switch button
  win = await openGui(bot, '/fmkit private')
  if (win) {
    await click(bot, 48, 0, 0)
    await sleep(400)
    const w2 = bot.currentWindow
    record('I25 switch button -> public gui', w2 != null, `window=${w2 ? 'open' : 'null'}`)
    if (w2) {
      const pubDark = [0, 1, 2, 3, 5, 6, 7, 8].every(s => w2.slots[s] && w2.slots[s].name === 'black_stained_glass_pane')
      record('N0e public page1 dark bar + book at 4', pubDark && w2.slots[4] && w2.slots[4].name === 'writable_book',
        `book=${w2.slots[4] ? w2.slots[4].name : 'null'} darkOk=${pubDark}`)
      await click(bot, 48, 0, 0); await sleep(400)
    }
  }
  // toggle button in GUI (top row slot 1)
  win = await openGui(bot, '/fmkit private')
  if (win) {
    bot.messages.length = 0
    await click(bot, 1, 0, 0)
    const flip1 = await waitFor(() => echoOf(bot, '回收已关闭') || echoOf(bot, '回收已开启'), 3000)
    record('I26 gui toggle button works', !!flip1, `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 100)}`)
    bot.messages.length = 0
    await click(bot, 1, 0, 0)   // flip back
    await sleep(300)
  }
  closeGui(bot)
  // force-restore ON via command (idempotent) so later tests run with collection enabled
  bot.chat('/fmkit toggle on')
  await waitFor(() => echoOf(bot, '回收已开启'), 5000)

  // ---- Item 7b: 顶行布局 —— 槽1 扫地开关、槽2 到期提醒(三态 OFF/VALUABLE/ALL)、槽4 标题书、槽6 到期去向、槽7 预览、槽52 全部取回、暗条填充 ----
  win = await openGui(bot, '/fmkit private')
  if (win) {
    const darkOk = [0, 3, 5, 8].every(s => win.slots[s] && win.slots[s].name === 'black_stained_glass_pane')
    record('N0a page1 dark bar slots 0/3/5/8', darkOk, `slots=${[0, 3, 5, 8].map(s => win.slots[s] ? win.slots[s].name : 'null').join(',')}`)
    record('N0b banner book at slot 4', win.slots[4] && win.slots[4].name === 'writable_book', `slot4=${win.slots[4] ? win.slots[4].name : 'null'}`)
    record('N0c preview spyglass at slot 7', win.slots[7] && win.slots[7].name === 'spyglass', `slot7=${win.slots[7] ? win.slots[7].name : 'null'}`)
    record('N0d take-all shulker at slot 52', win.slots[52] && win.slots[52].name === 'lime_shulker_box', `slot52=${win.slots[52] ? win.slots[52].name : 'null'}`)
  }
  record('N1 expiry-reminder icon at slot 2', win && win.slots[2] != null && win.slots[2].name !== FILLER,
    `slot2=${win && win.slots[2] ? win.slots[2].name : 'null'}`)
  record('N2 expiry-dest icon at slot 6', win && win.slots[6] != null && win.slots[6].name !== FILLER,
    `slot6=${win && win.slots[6] ? win.slots[6].name : 'null'}`)
  if (win) {
    // normalize to ALL first (mode persists across runs)
    for (let i = 0; i < 3; i++) {
      bot.messages.length = 0
      await click(bot, 2, 0, 0)
      if (await waitFor(() => echoOf(bot, '到期提醒已开启'), 3000)) break
    }
    bot.messages.length = 0
    await click(bot, 2, 0, 0)   // ALL -> OFF
    const n1 = await waitFor(() => echoOf(bot, '到期提醒已关闭'), 3000)
    record('N3 slot 2 cycles ALL->OFF', !!n1, `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 100)}`)
    bot.messages.length = 0
    await click(bot, 2, 0, 0)   // OFF -> VALUABLE
    const n2 = await waitFor(() => echoOf(bot, '只提醒贵重'), 3000)
    record('N3b slot 2 cycles OFF->VALUABLE', !!n2, `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 100)}`)
    bot.messages.length = 0
    await click(bot, 2, 0, 0)   // VALUABLE -> ALL
    await waitFor(() => echoOf(bot, '到期提醒已开启'), 3000)
    bot.messages.length = 0
    await click(bot, 6, 0, 0)
    let d1 = await waitFor(() => echoOf(bot, '到期去向'), 3000)
    if (!echoOf(bot, '自动销毁')) {   // persisted state may have been destroy already -> toggle back on
      bot.messages.length = 0
      await click(bot, 6, 0, 0)
      d1 = await waitFor(() => echoOf(bot, '自动销毁'), 3000)
    }
    record('N4 slot 6 switches to destroy', !!d1, `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 100)}`)
    const w5 = bot.currentWindow
    record('N5 dest icon becomes fire_charge', w5 && w5.slots[6] && w5.slots[6].name === 'fire_charge',
      `icon=${w5 && w5.slots[6] ? w5.slots[6].name : 'null'}`)
  }
  closeGui(bot)
  // 开关写入玩家私人箱 yml（settings.expiry-*）
  {
    const fsN = require('fs'), pathN = require('path')
    const privDir = pathN.join(__dirname, '..', 'servers', PORT === 25567 ? 'folia261' : 'folia', 'plugins', 'FmKit', 'bins', 'private')
    let yml = ''
    const dl = Date.now()
    while (Date.now() - dl < 4000) {
      yml = fsN.readdirSync(privDir).filter(f => f.endsWith('.yml')).map(f => fsN.readFileSync(pathN.join(privDir, f), 'utf8')).join('\n')
      if (/expiry-destroy: true/.test(yml)) break
      await sleep(300)
    }
    record('N6 flags persist to player yml', /expiry-notify:/.test(yml) && /expiry-destroy: true/.test(yml),
      `notifyKey=${/expiry-notify:/.test(yml)} destroyTrue=${/expiry-destroy: true/.test(yml)}`)
  }
  // ---- Item 7c: 销毁模式 —— 到期直接销毁并提醒，不进公共 ----
  const pubBeforeN = await publicEntriesWait(bot, x => x >= 0, 10000)
  await giveDirt(bot, 1)
  bot.messages.length = 0
  await toss(bot, 'dirt')
  await away()
  await privateTotalWait(bot, t => t >= 1, 30000)
  await backHome()
  const destroyedN = await waitFor(() => echoOf(bot, '已到期销毁'), 60000)
  record('N7 destroy mode: expiry destroys + notifies', !!destroyedN && bot.messages.some(m => /你回收站里的 .+ 已到期销毁/.test(m)), `msgs=${bot.messages.slice(-4).join(' | ').slice(0, 160)}`)
  const privN = await privateEntriesWait(bot, x => x === 0, 10000)
  record('N8 destroy mode: private empty', privN === 0, `entries=${privN}`)
  const pubN = await publicEntriesWait(bot, x => x >= 0 && x <= pubBeforeN, 10000)
  record('N9 destroy mode: public unchanged', pubN >= 0 && pubN <= pubBeforeN, `public=${pubN} was=${pubBeforeN}`)
  // 恢复：到期去向切回转公共
  win = await openGui(bot, '/fmkit private')
  if (win) {
    bot.messages.length = 0
    await click(bot, 6, 0, 0)
    await waitFor(() => echoOf(bot, '到期去向'), 3000)
    if (!echoOf(bot, '转公共回收站')) {
      bot.messages.length = 0
      await click(bot, 6, 0, 0)
      await waitFor(() => echoOf(bot, '转公共回收站'), 3000)
    }
  }
  closeGui(bot)

  // ---- Item 8: 管理员指令 ----
  await rcon(`op ${BOT_NAME}`)
  await sleep(500)
  bot.messages.length = 0
  bot.chat('/fmkitadmin clearpublic')
  await waitFor(() => echoOf(bot, '再执行一次以确认'), 5000)
  record('I27 clearpublic first step (confirm)', echoOf(bot, '再执行一次以确认'))
  bot.chat('/fmkitadmin clearpublic')
  await waitFor(() => echoOf(bot, '已清空公共回收站'), 5000)
  record('I28 clearpublic second step (done)', echoOf(bot, '已清空公共回收站'))
  const pubEmpty = await publicEntriesWait(bot, x => x === 0, 10000)
  record('I29 public empty after clearpublic', pubEmpty === 0, `entries=${pubEmpty}`)
  // ---- Item 8d: 公共 TTL 清理（public-ttl 压到 ~36s，BinExpiryTask 移除过期公共条目）----
  {
    const origPubTtl = ((await cfgText()).match(/public-ttl-days:\s*([\d.]+)/) || [])[1] || '7'
    await setPublicTtl(0.03)
    await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~5 {Item:{id:"minecraft:tuff",count:1}}`)
    bot.messages.length = 0
    await waitFor(() => echoOf(bot, '收集了'), 45000)
    const pubOne = await publicEntriesWait(bot, x => x >= 1, 20000)
    const purged = await publicEntriesWait(bot, x => x === 0, 120000)
    record('P1 public TTL purge removes expired entries', pubOne >= 1 && purged === 0, `afterSweep=${pubOne} afterPurge=${purged}`)
    await setPublicTtl(origPubTtl)
  }
  // ---- Item 8a: 公共箱排序（默认最新优先；循环 最新 -> 最旧 -> 最先到期）----
  await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~5 {Item:{id:"minecraft:cobblestone",count:1}}`)
  await waitFor(() => echoOf(bot, '收集了'), 45000)
  await sleep(2000)
  await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~5 {Item:{id:"minecraft:basalt",count:1}}`)
  let pubOrder = []
  {
    const pStart = Date.now()
    while (Date.now() - pStart < 60000) {
      const wp = await openGui(bot, '/fmkit public')
      pubOrder = wp ? entrySlots(wp).map(s2 => wp.slots[s2].name) : []
      closeGui(bot)
      if (pubOrder.includes('basalt') && pubOrder.includes('cobblestone')) break
      await sleep(1500)
    }
  }
  const v8 = pubOrder.filter(n => n === 'basalt' || n === 'cobblestone')
  record('V8 public default sort = newest (basalt first)', v8.join(',') === 'basalt,cobblestone', `order=${pubOrder.join(',') || 'none'}`)
  win = await openGui(bot, '/fmkit public')
  if (win) {
    await click(bot, 51, 0, 0)   // -> oldest
    const po1 = bot.currentWindow ? entrySlots(bot.currentWindow).map(s2 => bot.currentWindow.slots[s2].name) : []
    const v9 = po1.filter(n => n === 'basalt' || n === 'cobblestone')
    record('V9 public sort click -> oldest (cobblestone first)', v9.join(',') === 'cobblestone,basalt', `order=${po1.join(',') || 'none'}`)
  }
  closeGui(bot)
  // deposit something, then admin clear player
  await giveDirt(bot, 4)
  await toss(bot, 'dirt')
  await away()
  const t30 = await privateTotalWait(bot, t => t >= 1)
  await backHome()
  bot.messages.length = 0
  bot.chat(`/fmkitadmin clear ${BOT_NAME}`)
  await waitFor(() => echoOf(bot, '已清空'), 6000)
  record('I30 admin clear <player>', echoOf(bot, '已清空'), `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 120)}`)
  const privCleared = await privateEntriesWait(bot, x => x === 0, 10000)
  record('I31 private empty after admin clear', privCleared === 0, `entries=${privCleared}`)
  // admin bin <player> opens GUI
  win = await openGui(bot, `/fmkitadmin bin ${BOT_NAME}`)
  record('I32 admin bin <player> opens GUI', win != null)
  closeGui(bot)
  // status
  bot.messages.length = 0
  bot.chat('/fmkitadmin status')
  await waitFor(() => echoOf(bot, 'FmKit 状态'), 5000)
  record('I33 admin status', echoOf(bot, 'FmKit 状态'))
  // ---- Item 8c: admin 强制设定玩家开关（notify/destroy）+ 贵重总开关关闭时 VALUABLE 档静默 ----
  await rcon(`fmkitadmin notify ${BOT_NAME} valuable`)
  await sleep(400)
  win = await openGui(bot, '/fmkit private')
  record('A1 admin notify -> VALUABLE (yellow icon)', win && win.slots[2] && win.slots[2].name === 'yellow_concrete',
    `slot2=${win && win.slots[2] ? win.slots[2].name : 'null'}`)
  closeGui(bot)
  await rcon(`fmkitadmin destroy ${BOT_NAME} on`)
  await sleep(400)
  win = await openGui(bot, '/fmkit private')
  record('A2 admin destroy on -> fire_charge icon', win && win.slots[6] && win.slots[6].name === 'fire_charge',
    `slot6=${win && win.slots[6] ? win.slots[6].name : 'null'}`)
  closeGui(bot)
  await rcon(`fmkitadmin destroy ${BOT_NAME} off`)
  await sleep(400)
  win = await openGui(bot, '/fmkit private')
  record('A3 admin destroy off -> chest icon', win && win.slots[6] && win.slots[6].name === 'chest',
    `slot6=${win && win.slots[6] ? win.slots[6].name : 'null'}`)
  closeGui(bot)
  // 贵重总开关关闭：VALUABLE 档到期流转静默（流转本身照常发生）
  await setValuableEnabled(false)
  await giveDirt(bot, 1)
  bot.messages.length = 0
  await toss(bot, 'dirt')
  await away()
  await privateTotalWait(bot, t => t >= 1, 30000)
  await backHome()
  await sleep(30000)   // 等 TTL 到期流转（24s），期间 VALUABLE 档应全程无声
  const privSilent = await privateEntriesWait(bot, x => x === 0, 20000)
  record('V7 valuable OFF: expiry move silent for VALUABLE tier', privSilent === 0 && !echoOf(bot, '移入公共回收站') && !echoOf(bot, '已到期销毁'),
    `privAfter=${privSilent} msgs=${bot.messages.slice(-4).join(' | ').slice(0, 160)}`)
  await rcon(`fmkitadmin notify ${BOT_NAME} all`)
  await sleep(400)
  win = await openGui(bot, '/fmkit private')
  record('A4 admin notify all -> lime icon', win && win.slots[2] && win.slots[2].name === 'lime_concrete',
    `slot2=${win && win.slots[2] ? win.slots[2].name : 'null'}`)
  closeGui(bot)
  await setValuableEnabled(true)
  await rcon(`fmkitadmin notify ${BOT_NAME} valuable`)
  await sleep(400)
  win = await openGui(bot, '/fmkit private')
  record('A5 restore notify valuable -> yellow icon', win && win.slots[2] && win.slots[2].name === 'yellow_concrete',
    `slot2=${win && win.slots[2] ? win.slots[2].name : 'null'}`)
  closeGui(bot)
  // ---- Item 8b: VALUABLE 模式（清单=仅提醒过滤）：无主钻石照常扫走、进公共箱 ----
  bot.messages.length = 0
  await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~5 {Item:{id:"minecraft:diamond",count:1}}`)
  const sweptVal = await waitFor(() => echoOf(bot, '收集了'), 45000)
  let vEntries = []
  {
    const vStart = Date.now()
    while (Date.now() - vStart < 15000) {
      const wv = await openGui(bot, '/fmkit public')
      vEntries = wv ? entrySlots(wv).map(s2 => wv.slots[s2].name) : []
      closeGui(bot)
      if (vEntries.includes('diamond')) break
      await sleep(1500)
    }
  }
  record('V2b VALUABLE: unowned diamond still swept (into public)', !!sweptVal && vEntries.includes('diamond'),
    `public=${vEntries.join(',') || 'none'}`)

  // ---- Item 9: 权限 ----
  await rcon(`deop ${BOT_NAME}`)
  await sleep(500)
  bot.messages.length = 0
  bot.chat('/fmkitadmin status')
  await sleep(1200)
  const denied = !echoOf(bot, 'FmKit 状态')
  record('I34 non-op denied /fmkitadmin', denied, `msgs=${bot.messages.slice(-2).join(' | ').slice(0, 120)}`)
  bot.chat('/fmkit')
  const hub = await waitFor(() => bot.currentWindow, 5000)
  record('I35 non-op can still use /fmkit', !!hub)
  closeGui(bot)
  await rcon(`op ${BOT_NAME}`)

  // ---- Item 10: pagination ----
  win = await openGui(bot, '/fmkit private')
  if (win && entrySlots(win).length > 0) { await click(bot, 52, 0, 0); await sleep(400) }
  closeGui(bot)
  await summonOwned(40)
  const total40 = await privateTotalWait(bot, t => t === 40, 60000)
  record('I36a all 40 summons deposited', total40 === 40, `total=${total40}`)
  const page1 = await privateEntriesWait(bot, x => x === 36, 10000)
  record('I36 pagination page1 full (36)', page1 === 36, `entries=${page1}`)
  win = await openGui(bot, '/fmkit private')
  if (win) {
    await click(bot, 53, 0, 0)   // next page
    await sleep(400)
    const w2 = bot.currentWindow
    const page2 = w2 ? entrySlots(w2, 1).length : -1
    record('I37 pagination page2 has rest (4)', page2 === 4, `entries=${page2}`)
  } else {
    record('I37 pagination page2 has rest (4)', false, 'no window')
  }
  closeGui(bot)


  // ---- Item 10a: 合并只续期到期时间，存入时间保留最早 ----
  // 确定性构造：三件物品分三轮扫地入库，expireAt 差距=整轮间隔（不受同轮 add() 毫秒时序影响）：
  //   R1 dirt#1（组，depositAt=T1）→ R2 stone（depositAt=T2）→ R3 dirt#2 并入组（expireAt 刷到 R3）
  //   EXPIRING: stone(R2) 先于 dirt组(R3)；OLDEST: dirt组(T1) 先于 stone(T2)；NEWEST: stone(T2) 先于 dirt组(T1)
  // 先把 TTL 拉长，避免 dirt#1 在 R3 合并前（R1+24s）到期被流转走
  bot.chat(`/fmkitadmin clear ${BOT_NAME}`)
  await waitFor(() => echoOf(bot, '已清空'), 6000)
  {
    const t = await cfgText()
    await fs.promises.writeFile(cfgPath, t.replace(/private-ttl-days:\s*[\d.]+/, 'private-ttl-days: 0.5'), 'utf8')
    await rcon('fmkitadmin reload')
    await sleep(600)
  }
  bot.messages.length = 0
  await summonSame(1)                                   // dirt#1 @R1
  await waitFor(() => echoOf(bot, '收集了'), 45000)
  await summonSame(1, 'stone')                          // stone @R2
  bot.messages.length = 0
  await waitFor(() => echoOf(bot, '收集了'), 45000)
  await summonSame(1)                                   // dirt#2 @R3 并入 T1 组
  let expOrder = [], mergedOrder = [], oldestOrder = [], reExpOrder = []
  let mergedDirt = -1
  {
    const mStart = Date.now()
    while (Date.now() - mStart < 60000) {
      const wm = await openGui(bot, '/fmkit private')
      if (wm) {
        const ms = entrySlots(wm)
        const dirtSlot = ms.find(s2 => wm.slots[s2].name === 'dirt')
        if (ms.length === 2 && dirtSlot !== undefined && wm.slots[dirtSlot].count === 2) {
          expOrder = ms.map(s2 => wm.slots[s2].name)          // 默认排序=最先到期
          await click(bot, 51, 0, 0)                          // -> 最新
          let cs = bot.currentWindow ? entrySlots(bot.currentWindow) : []
          mergedOrder = cs.map(s2 => bot.currentWindow.slots[s2].name)
          const dSlot = cs.find(s2 => bot.currentWindow.slots[s2].name === 'dirt')
          mergedDirt = dSlot !== undefined ? bot.currentWindow.slots[dSlot].count : -1
          await click(bot, 51, 0, 0)                          // -> 最旧
          cs = bot.currentWindow ? entrySlots(bot.currentWindow) : []
          oldestOrder = cs.map(s2 => bot.currentWindow.slots[s2].name)
          await click(bot, 51, 0, 0)                          // -> 最先到期
          cs = bot.currentWindow ? entrySlots(bot.currentWindow) : []
          reExpOrder = cs.map(s2 => bot.currentWindow.slots[s2].name)
          closeGui(bot)
          break
        }
      }
      closeGui(bot)
      await sleep(1500)
    }
  }
  record('V4 default sort = expiring (stone first)', expOrder.join(',') === 'stone,dirt', `order=${expOrder.join(',') || 'none'}`)
  record('I39a merge keeps earliest deposit time (NEWEST order)', mergedOrder.join(',') === 'stone,dirt' && mergedDirt === 2, `order=${mergedOrder.join(',') || 'none'} dirt=${mergedDirt}`)
  record('V5 sort click 2 -> oldest (dirt group first)', oldestOrder.join(',') === 'dirt,stone', `order=${oldestOrder.join(',') || 'none'}`)
  record('V6 sort click 3 -> back to expiring', reExpOrder.join(',') === 'stone,dirt', `order=${reExpOrder.join(',') || 'none'}`)
  {   // 恢复测试 TTL（0.02 游戏日=24s），后续合并/拆分测试沿用短 TTL
    const t = await cfgText()
    await fs.promises.writeFile(cfgPath, t.replace(/private-ttl-days:\s*[\d.]+/, 'private-ttl-days: 0.02'), 'utf8')
    await rcon('fmkitadmin reload')
    await sleep(600)
  }
  // ---- Item 10b: 同物品合并（相同物品并入一组续期，堆满开新组） ----
  bot.chat(`/fmkitadmin clear ${BOT_NAME}`)
  await waitFor(() => echoOf(bot, '已清空'), 6000)
  await summonSame(5)
  // 轮询直到合并后的单组达到 x5（扫地可能分两轮收完召唤物）
  let mCounts = []
  {
    const m5Start = Date.now()
    while (Date.now() - m5Start < 60000) {
      win = await openGui(bot, '/fmkit private')
      mCounts = win ? entrySlots(win).map(s => win.slots[s].count) : []
      closeGui(bot)
      if (mCounts.length === 1 && mCounts[0] === 5) break
      await sleep(1500)
    }
  }
  record('I39 5 same items merge into 1 entry', mCounts.length === 1, `entries=${mCounts.length} total=${mCounts.reduce((a, b) => a + b, 0)}`)
  record('I39b merged entry shows x5', mCounts.length === 1 && mCounts[0] === 5, `count=${mCounts.length === 1 ? mCounts[0] : -1}`)
  await summonSame(70)  // 5 + 70 = 75 -> one full group (64) + one group of 11
  let counts75 = []
  {
    const o75Start = Date.now()
    while (Date.now() - o75Start < 60000) {
      win = await openGui(bot, '/fmkit private')
      counts75 = win ? entrySlots(win).map(s => win.slots[s].count).sort((a, b) => b - a) : []
      closeGui(bot)
      if (counts75.join(',') === '64,11') break
      await sleep(1500)
    }
  }
  record('I40 overflow 75 splits into 64+11', counts75.join(',') === '64,11', `groups=${counts75.join(',') || 'none'}`)

  // ---- Item 11: sweep countdown broadcasts reach client chat ----
  record('I38 sweep countdown reached client chat', !!bot.sawCountdown, `sawCountdown=${!!bot.sawCountdown}`)

  // ---- Item 12: 双清单/间隔/立即扫地（游戏内命令 + config 持久化）----
  // 管理回复只发给命令发送者（控制台），不进 RCON 响应；验证落盘效果与全服广播。
  const origInterval = ((await cfgText()).match(/clean-interval:\s*(\d+)/) || [])[1] || '20'

  await rcon('fmkitadmin whitelist valuable add DIRT')
  await sleep(300)
  const cfgA = await cfgText()
  record('I39 whitelist valuable add persists to config', /\bDIRT\b/.test(listSection(cfgA, 'valuable-items:')), `section=${listSection(cfgA, 'valuable-items:').split('\n').slice(0, 2).join(' ').slice(0, 120)}`)

  await rcon('fmkitadmin whitelist valuable remove DIRT')
  await sleep(300)
  const cfgB = await cfgText()
  record('I40 whitelist valuable remove persists to config', !/\bDIRT\b/.test(listSection(cfgB, 'valuable-items:')), `section=${listSection(cfgB, 'valuable-items:').split('\n').slice(0, 2).join(' ').slice(0, 120)}`)

  await rcon('fmkitadmin whitelist ignore add DIRT')
  await sleep(300)
  const cfgC2 = await cfgText()
  record('I40c whitelist ignore add persists to config', /\bDIRT\b/.test(listSection(cfgC2, 'ignore:')), `section=${listSection(cfgC2, 'ignore:').replace(/\n/g, ' ').slice(0, 120)}`)
  await rcon('fmkitadmin whitelist ignore remove DIRT')
  await rcon('fmkitadmin whitelist ignore on')
  await sleep(300)
  const cfgD = await cfgText()
  record('I40d whitelist ignore on persists', /ignore:[\s\S]*?enabled:\s*true/.test(cfgD), `section=${listSection(cfgD, 'ignore:').split('\n').slice(0, 2).join(' ')}`)
  await rcon('fmkitadmin whitelist ignore off')
  await sleep(300)

  await rcon('fmkitadmin interval 15')
  await sleep(300)
  const cfgC = await cfgText()
  record('I41 interval persists to config', /clean-interval:\s*15\b/.test(cfgC), `cfg=${(cfgC.match(/clean-interval:\s*\d+/) || [])[0]}`)

  bot.messages.length = 0
  await rcon('fmkitadmin sweep now')
  const sawCleanNow = await waitFor(() => echoOf(bot, '收集了') && echoOf(bot, '个掉落物'), 15000)
  record('I42 sweep now broadcasts cleaned immediately', !!sawCleanNow, `msgs=${bot.messages.filter(m => m.includes('扫地') || m.includes('收集了')).slice(-2).join(' | ').slice(0, 160)}`)

  await rcon(`fmkitadmin interval ${origInterval}`)   // 恢复原间隔

  // ---- Item 13: 到期竞态回归 E1-E4（渲染过滤 / 操作二次校验 / 悬停预览 / 自动重渲染）----
  // 策略：暂停到期扫描（3600s）+ 关自动刷新，制造"已到期但未清扫"窗口验证三层兜底；
  // 1 游戏日=20 分钟：ttl 0.036 天≈43.2s，0.36 天≈7.2 分钟。结束后恢复基线配置并清空残留。
  const cfgBeforeEx = await cfgText()
  await bot.chat(`/fmkitadmin clear ${BOT_NAME}`)
  await waitFor(() => echoOf(bot, '已清空'), 6000)
  await patchCfgKV({ 'private-ttl-days': 0.036, 'expiry-scan-interval': 3600, 'auto-refresh-seconds': 0 })

  // E2：到期后重新开窗，渲染层直接过滤已到期条目
  await giveDirt(bot, 1)
  await toss(bot, 'dirt')
  await sleep(300)
  await rcon('fmkitadmin sweep now')
  await privateEntriesWait(bot, x => x === 1, 20000)
  await sleep(46000)                                   // TTL≈43.2s 已到期，扫描暂停未清扫
  {
    const e2Win = await openGui(bot, '/fmkit private')
    const e2Slots = e2Win ? entrySlots(e2Win) : []
    record('E2 expired entries hidden on open', e2Slots.length === 0, `slots=${e2Slots.length}`)
    closeGui(bot)
  }

  // E1：开窗后到期，陈旧卡片仍在（自动刷新已关）；左键被二次校验拒绝，物品拿不走
  await giveDirt(bot, 1)
  await toss(bot, 'dirt')
  await sleep(300)
  await rcon('fmkitadmin sweep now')
  await privateEntriesWait(bot, x => x === 1, 20000)   // E2 的到期条目已被渲染过滤，仅新条目可见
  {
    const e1Win = await openGui(bot, '/fmkit private')
    const e1Slots = e1Win ? entrySlots(e1Win) : []
    bot.messages.length = 0
    const dirtBeforeE1 = invCount(bot, 'dirt')
    await sleep(46000)                                 // 陈旧卡片窗口：扫描暂停 + 自动刷新关闭
    if (e1Slots.length === 1) await click(bot, e1Slots[0], 0, 0)
    const e1Msg = await waitFor(() => echoOf(bot, '该条目已到期'), 5000)
    record('E1 click expired entry rejected', !!e1Msg && invCount(bot, 'dirt') === dirtBeforeE1, `msg=${!!e1Msg} dirt=${invCount(bot, 'dirt')}/${dirtBeforeE1} slots=${e1Slots.length}`)
    closeGui(bot)
  }

  // E3：slot 7 悬停预览 lore 动态列出临期条目；E4：自动刷新使开窗倒计时逐秒变化
  await patchCfgKV({ 'private-ttl-days': 0.36, 'auto-refresh-seconds': 2 })
  await bot.chat(`/fmkitadmin clear ${BOT_NAME}`)      // 清掉 E1/E2 到期残留，预览列表仅含新条目
  await waitFor(() => echoOf(bot, '已清空'), 6000)
  await giveDirt(bot, 1)
  await toss(bot, 'dirt')
  await sleep(300)
  await rcon('fmkitadmin sweep now')
  await privateEntriesWait(bot, x => x === 1, 20000)
  {
    const e3Win = await openGui(bot, '/fmkit private')
    const hoverNbt = itemBlob(e3Win, 7)
    record('E3 hover preview lists expiring entry', /Dirt|泥土/.test(hoverNbt) && hoverNbt.includes('剩'), `lore≈${hoverNbt.slice(0, 140)}`)
    const e4Slots = e3Win ? entrySlots(e3Win) : []
    const loreAt0 = itemBlob(e3Win, e4Slots[0])
    await sleep(2600)                                  // 自动刷新周期 2s，倒计时 {t} 秒级变化
    const loreAt2 = bot.currentWindow === e3Win ? itemBlob(e3Win, e4Slots[0]) : ''
    record('E4 open window auto re-renders countdown', loreAt0 !== '' && loreAt2 !== '' && loreAt0 !== loreAt2, `same=${loreAt0 === loreAt2}`)
    closeGui(bot)
  }

  // 清理：清新条目、恢复基线配置（到期扫描 10s 恢复），双确认清空公共回收站兜底
  await bot.chat(`/fmkitadmin clear ${BOT_NAME}`)
  await waitFor(() => echoOf(bot, '已清空'), 6000)
  await fs.promises.writeFile(cfgPath, cfgBeforeEx, 'utf8')
  await rcon('fmkitadmin reload')
  await sleep(600)
  await bot.chat('/fmkitadmin clearpublic')
  await waitFor(() => echoOf(bot, '再执行一次以确认'), 5000)
  await bot.chat('/fmkitadmin clearpublic')
  await waitFor(() => echoOf(bot, '已清空公共回收站'), 5000)

  bot.quit()
  await sleep(500)

  const failed = results.filter(r => !r.ok).length
  console.log(`\nSUMMARY: ${results.length - failed}/${results.length} passed`)
  process.exit(failed ? 1 : 0)
}

scenario().catch(e => { console.error('FATAL', e); process.exit(2) })
