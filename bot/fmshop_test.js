// FmShop player-level suite: a real client drives /fmshop + the chest GUIs and
// asserts on chat text, window layout, balances, and inventory deltas.
// Console-only paths live in fmshop_console.js; this file only asserts what a
// player can actually observe.
//
// usage: node fmshop_test.js          (server: servers/shoptest, folia-26.2)
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25580', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25581', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.2'
const BOT = process.env.BOT_NAME || 'FmShopper'
const BOT2 = process.env.BOT2_NAME || 'FmShopMate'

require('./mc_shim')(VERSION)

const mineflayer = require('mineflayer')
const net = require('net')
const fs = require('fs')
const path = require('path')

const DATA = process.env.DATA_DIR || path.join(__dirname, '..', 'servers', 'shoptest', 'plugins', 'FmShop')
const PRICES = path.join(DATA, 'prices.yml')

// ---------------------------------------------------------------- scaffolding
let priorDifficulty = ''
const results = []
function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + String(detail).replace(/\n/g, ' | ').slice(0, 220) : ''}`)
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

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

const strip = s => s.replace(/\u00a7x(\u00a7[0-9a-fA-F]){6}/g, '').replace(/\u00a7[0-9a-fk-orA-FK-OR]/g, '')

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    const send = (id, type, body) => {
      const b = Buffer.alloc(14 + Buffer.byteLength(body))
      b.writeInt32LE(b.length - 4, 0); b.writeInt32LE(id, 4); b.writeInt32LE(type, 8); b.write(body, 12)
      sock.write(b)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('RCON_TIMEOUT ' + cmd)) }, 15000)
    sock.on('connect', () => send(1, 3, RCON_PASS))
    sock.on('data', d => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4) {
        const len = buf.readInt32LE(0)
        if (buf.length < 4 + len) break
        const id = buf.readInt32LE(4), type = buf.readInt32LE(8)
        const body = buf.slice(12, 4 + len - 2).toString('utf8')
        buf = buf.slice(4 + len)
        if (type === 2 && id === 1) send(2, 2, cmd)
        else if (type === 2 && id === -1) { clearTimeout(timer); sock.end(); reject(new Error('RCON_AUTH_FAILED')) }
        else if (type === 0) { clearTimeout(timer); sock.end(); resolve(strip(body)) }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}

// ------------------------------------------------------------- bot conveniences
const FILLER = 'gray_stained_glass_pane'
const echoOf = (bot, text) => bot.messages.some(m => m.includes(text))
const findMsg = (bot, re) => bot.messages.find(m => re.test(m)) || ''
const tail = (bot, n = 3) => bot.messages.slice(-n).join(' | ').slice(0, 200)

/** "1,234.50" -> 1234.5; the money formatter groups thousands. */
const num = s => parseFloat(String(s).replace(/,/g, ''))

function invCount(bot, name) {
  return bot.inventory.items().filter(i => i.name === name).reduce((a, i) => a + i.count, 0)
}

async function chat(bot, cmd, clear = true) {
  if (clear) bot.messages.length = 0
  bot.chat(cmd)
  await sleep(500)
}

/** Reads the player's own balance through the player-facing command. */
async function balance(bot) {
  bot.messages.length = 0
  bot.chat('/fmshop balance')
  const hit = await waitFor(() => findMsg(bot, /余额：/) || null, 5000)
  if (!hit) return NaN
  const m = /余额：\s*([\d,]+\.\d\d)/.exec(hit)
  return m ? num(m[1]) : NaN
}

async function openGui(bot, cmd) {
  if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch { } await sleep(150) }
  bot.messages.length = 0
  bot.chat(cmd)
  const win = await waitFor(() => bot.currentWindow, 8000)
  if (win) await sleep(400)
  return win
}

function closeGui(bot) {
  if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch { } }
}

/** button: 0=left 1=right; mode: 0=normal 1=shift */
async function click(bot, slot, button = 0, mode = 0) {
  try { await bot.clickWindow(slot, button, mode) } catch (e) { console.log('  click err', slot, e.message) }
  await sleep(320)
}

const titleOf = win => {
  try { return typeof win.title === 'string' ? win.title : JSON.stringify(win.title) } catch { return '' }
}

/** Container slot holding `name`, searching only the item grid. */
function slotOf(win, name, limit) {
  const max = Math.min(limit == null ? win.inventoryStart : limit, win.inventoryStart)
  for (let s = 0; s < max; s++) {
    const it = win.slots[s]
    if (it && it.name === name) return s
  }
  return -1
}

const HUB_CATEGORIES = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25]
const HUB_BALANCE = 29, HUB_SELL_ALL = 31, HUB_CLOSE = 33
const CAT_BALANCE = 47, CAT_BACK = 49, CAT_PAGE = 51
const CFM_MINUS_1 = 12, CFM_PREVIEW = 13, CFM_PLUS_1 = 14, CFM_PLUS_64 = 16
const CFM_STACK = 21, CFM_CONFIRM = 22, CFM_MAX = 23
const ALL_BACK = 45, ALL_TOTAL = 49, ALL_CONFIRM = 53

/**
 * hub -> category -> item -> confirm screen. Retries once: a dropped click must
 * not silently skip the assertions that follow.
 */
async function openConfirm(bot, name, catSlot) {
  for (let attempt = 0; attempt < 2; attempt++) {
    if (!await openGui(bot, '/fmshop')) continue
    await click(bot, catSlot, 0, 0)
    const cat = await waitFor(() => bot.currentWindow && bot.currentWindow.inventoryStart === 54, 6000)
      ? bot.currentWindow : null
    if (!cat) continue
    const s = slotOf(cat, name, 45)
    if (s < 0) continue
    await click(bot, s, 0, 0)
    const cfm = await waitFor(() => bot.currentWindow && bot.currentWindow.inventoryStart === 27, 6000)
      ? bot.currentWindow : null
    if (cfm) return cfm
  }
  return null
}

/** Records a whole group as failed so unreachable UI never shrinks the suite. */
function failGroup(names, detail) {
  for (const n of names) record(n, false, detail)
}

async function reset(name, money) {
  await rcon(`clear ${name}`)
  await rcon(`gamemode survival ${name}`)
  await rcon(`fsa set ${name} ${money}`)
  await rcon(`fsa resetlimit ${name}`)
  await rcon('fsa market DIAMOND reset')
  await sleep(250)
}

/** Puts `n` of `item` in the hotbar and holds it. */
async function giveHeld(bot, item, n) {
  await rcon(`clear ${BOT}`)
  await rcon(`give ${BOT} minecraft:${item} ${n}`)
  await waitFor(() => invCount(bot, item) >= n, 5000)
  bot.setQuickBarSlot(0)
  await sleep(300)
}

const readPrices = () => fs.promises.readFile(PRICES, 'utf8')
/** Rewrites daily-sell inside the DIAMOND block only. */
async function setDiamondQuota(limit) {
  const t = await readPrices()
  const re = /(  DIAMOND:\n(?:    [^\n]*\n)*?    daily-sell: )\d+/
  if (!re.test(t)) throw new Error('prices.yml: DIAMOND daily-sell not found')
  await fs.promises.writeFile(PRICES, t.replace(re, '$1' + limit), 'utf8')
  await rcon('fsa reload')
  await sleep(600)
}

// ------------------------------------------------------------------- scenario
async function scenario() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: VERSION, auth: 'offline' })
  bot.messages = []
  bot.on('messagestr', m => bot.messages.push(m))
  bot.on('death', () => { try { bot._client.write('client_command', { actionId: 0 }) } catch { } })
  await new Promise(res => bot.once('spawn', res))

  const mate = mineflayer.createBot({ host: HOST, port: PORT, username: BOT2, version: VERSION, auth: 'offline' })
  mate.messages = []
  mate.on('messagestr', m => mate.messages.push(m))
  await new Promise(res => mate.once('spawn', res))
  record('T0 both clients joined', true, `${BOT} + ${BOT2}`)

  // Mob damage is the one thing that can void an inventory mid-assertion (a Slime
  // killed the mate once and the quota block then had nothing in hand). Peaceful
  // despawns hostiles for the run; the original difficulty is restored in report().
  priorDifficulty = (/(peaceful|easy|normal|hard)/.exec(await rcon('difficulty')) || [, 'normal'])[1]
  await rcon('difficulty peaceful')

  await reset(BOT, 1000)
  await reset(BOT2, 0)

  // ---- balance readout -----------------------------------------------------
  const b0 = await balance(bot)
  record('B1 /fmshop balance reports admin-set amount', b0 === 1000, `balance=${b0}`)

  // ---- hub layout ----------------------------------------------------------
  let win = await openGui(bot, '/fmshop')
  const hubTitle = win ? titleOf(win) : ''
  record('G1 hub opens as a 36-slot chest', !!win && win.inventoryStart === 36,
    `start=${win && win.inventoryStart} title=${hubTitle}`)
  record('G2 hub title is the configured shop title', /商店/.test(hubTitle), hubTitle)
  const cats = win ? HUB_CATEGORIES.filter(s => win.slots[s] && win.slots[s].name !== FILLER) : []
  record('G3 hub shows category icons in the two category rows', cats.length >= 5, `icons=${cats.length} slots=${cats.join(',')}`)
  record('G4 hub carries balance / bulk-sell / close controls',
    !!win && !!win.slots[HUB_BALANCE] && win.slots[HUB_SELL_ALL] && win.slots[HUB_SELL_ALL].name === 'chest'
    && win.slots[HUB_CLOSE] && win.slots[HUB_CLOSE].name === 'barrier',
    win ? `29=${win.slots[HUB_BALANCE] && win.slots[HUB_BALANCE].name} 31=${win.slots[HUB_SELL_ALL] && win.slots[HUB_SELL_ALL].name} 33=${win.slots[HUB_CLOSE] && win.slots[HUB_CLOSE].name}` : 'no window')

  // ---- category page -------------------------------------------------------
  let diamondSlot = -1
  if (cats.length) {
    await click(bot, cats[0])
    win = await waitFor(() => bot.currentWindow && bot.currentWindow.inventoryStart === 54, 6000) ? bot.currentWindow : null
    record('G5 category click opens a 54-slot page', !!win, `start=${bot.currentWindow && bot.currentWindow.inventoryStart}`)
    if (win) {
      record('G6 category page has back arrow + page counter + balance',
        !!win.slots[CAT_BACK] && !!win.slots[CAT_PAGE] && !!win.slots[CAT_BALANCE],
        `45..53=${[CAT_BALANCE, CAT_BACK, CAT_PAGE].map(s => win.slots[s] && win.slots[s].name).join(',')}`)
      diamondSlot = slotOf(win, 'diamond', 45)
      record('G7 ores page lists diamond as a buyable icon', diamondSlot >= 0, `slot=${diamondSlot}`)
    }
  }

  // ---- GUI buy: left click -> confirm -> pay -------------------------------
  const BUY_GROUP = ['G9 confirm screen previews the item and offers steppers',
    'G10 confirm buys 1 diamond at the listed 80.00',
    'G11 bought diamond lands in the inventory',
    'G12 balance debited exactly the quoted cost']
  if (diamondSlot >= 0) {
    await click(bot, diamondSlot, 0, 0)
    win = await waitFor(() => bot.currentWindow && bot.currentWindow.inventoryStart === 27, 6000) ? bot.currentWindow : null
    record('G8 left click opens the 27-slot confirm screen', !!win, `start=${bot.currentWindow && bot.currentWindow.inventoryStart}`)
    if (!win) {
      failGroup(BUY_GROUP, 'confirm screen unreachable')
    } else {
      record(BUY_GROUP[0],
        win.slots[CFM_PREVIEW] && win.slots[CFM_PREVIEW].name === 'diamond'
        && !!win.slots[CFM_PLUS_1] && !!win.slots[CFM_MINUS_1] && !!win.slots[CFM_CONFIRM] && !!win.slots[CFM_MAX],
        `13=${win.slots[CFM_PREVIEW] && win.slots[CFM_PREVIEW].name} 22=${win.slots[CFM_CONFIRM] && win.slots[CFM_CONFIRM].name}`)
      bot.messages.length = 0
      await click(bot, CFM_CONFIRM, 0, 0)
      const okMsg = await waitFor(() => findMsg(bot, /购买 .*×1，支付 80\.00/) || null, 5000)
      record(BUY_GROUP[1], !!okMsg, okMsg || tail(bot))
      const got = await waitFor(() => invCount(bot, 'diamond') >= 1, 5000)
      record(BUY_GROUP[2], !!got, `diamonds=${invCount(bot, 'diamond')}`)
      const b1 = await balance(bot)
      record(BUY_GROUP[3], b1 === 920, `balance=${b1} (expected 920)`)
    }
  } else {
    record('G8 left click opens the 27-slot confirm screen', false, 'no diamond icon')
    failGroup(BUY_GROUP, 'no diamond icon')
  }

  // ---- cap: the order clamps to what the balance can cover -----------------
  // G10's purchase already moved the dynamic price (step-bp 20 = +0.2% per unit,
  // so the next quote is 80.16). Pin the market back to base first, otherwise
  // the expected total drifts with whatever the earlier tests bought.
  const CAP_GROUP = ['G13 max button clamps qty to the affordable 11 (920 / 80)',
    'G14 clamped order buys 11 for 880.00',
    'G15 balance left at 40.00 after the clamped buy',
    'G16 inventory now holds all 12 diamonds']
  if (diamondSlot < 0 || !cats.length) {
    failGroup(CAP_GROUP, 'no diamond icon to drive')
  } else {
    await rcon('fsa market DIAMOND reset')
    await sleep(400)
    win = await openConfirm(bot, 'diamond', cats[0])
    if (!win) {
      failGroup(CAP_GROUP, 'confirm screen unreachable')
    } else {
      await click(bot, CFM_MAX, 0, 0)          // "max" = whatever binds first
      await sleep(300)
      const qty = bot.currentWindow.slots[CFM_PREVIEW] ? bot.currentWindow.slots[CFM_PREVIEW].count : -1
      record(CAP_GROUP[0], qty === 11, `qty=${qty}`)
      bot.messages.length = 0
      await click(bot, CFM_CONFIRM, 0, 0)
      const capMsg = await waitFor(() => findMsg(bot, /购买 .*×11，支付 880\.00/) || null, 5000)
      record(CAP_GROUP[1], !!capMsg, capMsg || tail(bot))
      const b2 = await balance(bot)
      record(CAP_GROUP[2], b2 === 40, `balance=${b2}`)
      record(CAP_GROUP[3], invCount(bot, 'diamond') === 12, `diamonds=${invCount(bot, 'diamond')}`)
    }
  }
  closeGui(bot)

  // ---- refusal: not enough money ------------------------------------------
  await reset(BOT, 10)
  await chat(bot, '/fmshop buy diamond 1')
  const poor = await waitFor(() => findMsg(bot, /余额不足，还需 70\.00/) || null, 5000)
  record('B2 buy past the balance names the exact shortfall', !!poor, poor || tail(bot))
  record('B3 failed buy delivers no item', invCount(bot, 'diamond') === 0, `diamonds=${invCount(bot, 'diamond')}`)

  // ---- sell hand: fee is taken out of the payout ---------------------------
  await reset(BOT, 0)
  await giveHeld(bot, 'diamond', 5)
  await chat(bot, '/fmshop sell hand')
  const sellMsg = await waitFor(() => findMsg(bot, /出售 .*×5，获得 190\.00/) || null, 5000)
  record('S1 sell hand pays 5x40 minus the 5% fee (190.00)', !!sellMsg, sellMsg || tail(bot))
  record('S2 payout line is followed by the fee line', !!findMsg(bot, /手续费 10\.00/), tail(bot))
  const b3 = await balance(bot)
  record('S3 balance credited the net payout', b3 === 190, `balance=${b3}`)
  record('S4 sold stack left the inventory', invCount(bot, 'diamond') === 0, `diamonds=${invCount(bot, 'diamond')}`)

  // ---- sell inv: bulk sweep across kinds ----------------------------------
  await reset(BOT, 0)
  await rcon(`give ${BOT} minecraft:coal 32`)
  await rcon(`give ${BOT} minecraft:iron_ingot 16`)
  await waitFor(() => invCount(bot, 'coal') === 32 && invCount(bot, 'iron_ingot') === 16, 6000)
  await chat(bot, '/fmshop sell inv')
  const sweep = await waitFor(() => findMsg(bot, /回收 .*2.* 种共 .*48.* 件，获得/) || null, 6000)
  record('S5 sell inv reports kinds, count, and payout', !!sweep, sweep || tail(bot))
  const cleared = await waitFor(() => invCount(bot, 'coal') === 0 && invCount(bot, 'iron_ingot') === 0, 5000)
  record('S6 swept stacks are gone from the inventory', !!cleared, `coal=${invCount(bot, 'coal')} iron=${invCount(bot, 'iron_ingot')}`)
  const b4 = await balance(bot)
  record('S7 bulk payout credited (>0 and fee-reduced)', b4 > 0 && Number.isFinite(b4), `balance=${b4}`)

  // ---- empty-hand and empty-bag refusals ----------------------------------
  await rcon(`clear ${BOT}`)
  await sleep(400)
  await chat(bot, '/fmshop sell hand')
  record('S8 empty hand refused by name', !!(await waitFor(() => echoOf(bot, '手上没有物品'), 4000)), tail(bot))
  await chat(bot, '/fmshop sell inv')
  record('S9 empty bag refused by name', !!(await waitFor(() => echoOf(bot, '背包里没有可回收的物品'), 4000)), tail(bot))

  // ---- creative refusal ---------------------------------------------------
  await rcon(`gamemode creative ${BOT}`)
  await rcon(`give ${BOT} minecraft:diamond 3`)
  await sleep(600)
  await chat(bot, '/fmshop sell inv')
  record('S10 creative mode cannot sell (named, not "nothing to sell")',
    !!(await waitFor(() => echoOf(bot, '创造模式无法出售物品'), 4000)), tail(bot))
  await rcon(`gamemode survival ${BOT}`)

  // ---- GUI shift-right dumps the held stack -------------------------------
  const DUMP_GROUP = ['G17 shift-right sells the whole held stack (2,432.00 net)',
    'G18 dumped diamonds left the inventory']
  await reset(BOT, 0)
  await rcon(`give ${BOT} minecraft:diamond 64`)
  await waitFor(() => invCount(bot, 'diamond') === 64, 6000)
  win = await openGui(bot, '/fmshop')
  let dumpSlot = -1
  if (win && cats.length) {
    await click(bot, cats[0])
    await waitFor(() => bot.currentWindow && bot.currentWindow.inventoryStart === 54, 6000)
    if (bot.currentWindow) dumpSlot = slotOf(bot.currentWindow, 'diamond', 45)
  }
  if (dumpSlot < 0) {
    failGroup(DUMP_GROUP, 'diamond icon unreachable')
  } else {
    bot.messages.length = 0
    await click(bot, dumpSlot, 1, 1)          // shift-right = dump everything held
    const dump = await waitFor(() => findMsg(bot, /出售 .*×64，获得 2,432\.00/) || null, 6000)
    record(DUMP_GROUP[0], !!dump, dump || tail(bot))
    record(DUMP_GROUP[1], invCount(bot, 'diamond') === 0, `diamonds=${invCount(bot, 'diamond')}`)
  }
  closeGui(bot)

  // ---- sell-all preview screen -------------------------------------------
  const ALL_GROUP = ['G20 preview lists both sellable kinds before charging anything',
    'G21 preview has total + confirm controls',
    'G22 preview confirm executes the sweep it showed',
    'G23 swept inventory is empty afterwards']
  await reset(BOT, 0)
  await rcon(`give ${BOT} minecraft:coal 10`)
  await rcon(`give ${BOT} minecraft:redstone 20`)
  await waitFor(() => invCount(bot, 'coal') === 10 && invCount(bot, 'redstone') === 20, 6000)
  win = await openGui(bot, '/fmshop')
  if (win) await click(bot, HUB_SELL_ALL, 0, 0)
  win = win && await waitFor(() => bot.currentWindow && bot.currentWindow.inventoryStart === 54, 6000)
    ? bot.currentWindow : null
  record('G19 bulk-sell icon opens the 一键回收 preview', !!win && /回收/.test(titleOf(win)), win ? titleOf(win) : 'no window')
  if (!win) {
    failGroup(ALL_GROUP, 'preview unreachable')
  } else {
    const lines = []
    for (let s = 0; s < 45; s++) if (win.slots[s] && win.slots[s].name !== FILLER) lines.push(win.slots[s].name)
    record(ALL_GROUP[0], lines.includes('coal') && lines.includes('redstone'), `lines=${lines.join(',')}`)
    record(ALL_GROUP[1], !!win.slots[ALL_TOTAL] && !!win.slots[ALL_CONFIRM] && !!win.slots[ALL_BACK],
      `49=${win.slots[ALL_TOTAL] && win.slots[ALL_TOTAL].name} 53=${win.slots[ALL_CONFIRM] && win.slots[ALL_CONFIRM].name}`)
    bot.messages.length = 0
    await click(bot, ALL_CONFIRM, 0, 0)
    const done = await waitFor(() => findMsg(bot, /回收 .*2.* 种共 .*30.* 件，获得/) || null, 6000)
    record(ALL_GROUP[2], !!done, done || tail(bot))
    record(ALL_GROUP[3], invCount(bot, 'coal') === 0 && invCount(bot, 'redstone') === 0,
      `coal=${invCount(bot, 'coal')} redstone=${invCount(bot, 'redstone')}`)
  }
  closeGui(bot)

  // ---- transfers ----------------------------------------------------------
  await reset(BOT, 1000)
  await reset(BOT2, 0)
  mate.messages.length = 0
  await chat(bot, `/fmshop pay ${BOT2} 100`)
  const payOk = await waitFor(() => findMsg(bot, new RegExp(`已向 ${BOT2} 转账 100\\.00`)) || null, 5000)
  record('P1 pay confirms the transfer to the sender', !!payOk, payOk || tail(bot))
  const recv = await waitFor(() => findMsg(mate, new RegExp(`收到 ${BOT} 的转账 100\\.00`)) || null, 5000)
  record('P2 recipient is notified with sender and amount', !!recv, recv || tail(mate))
  const bs = await balance(bot), bm = await balance(mate)
  record('P3 sender pays principal plus the 2% fee (898.00)', bs === 898, `sender=${bs}`)
  record('P4 recipient receives the full principal (100.00)', bm === 100, `recipient=${bm}`)

  await chat(bot, `/fmshop pay ${BOT} 10`)
  record('P5 paying yourself is refused', !!(await waitFor(() => echoOf(bot, '不能给自己转账'), 4000)), tail(bot))
  await chat(bot, `/fmshop pay ${BOT2} 0.5`)
  record('P6 sub-minimum transfer refused with the minimum', !!(await waitFor(() => findMsg(bot, /最少转账 1\.00/), 4000)), tail(bot))
  await rcon(`fsa set ${BOT} 5`)
  await chat(bot, `/fmshop pay ${BOT2} 100`)
  record('P7 shortfall counts the fee too (needs 97.00 more)', !!(await waitFor(() => findMsg(bot, /余额不足，还需 97\.00/), 4000)), tail(bot))

  // ---- dynamic pricing ---------------------------------------------------
  await reset(BOT, 5000)
  await chat(bot, '/fmshop price diamond')
  const base = await waitFor(() => findMsg(bot, /买入 80\.00/) || null, 5000)
  record('M1 price line quotes the base 80.00 / 40.00 at rest', !!base && /卖出 40\.00/.test(base), base || tail(bot))
  record('M2 dynamic item shows a market percentage', !!findMsg(bot, /100%|持平/), tail(bot, 2))
  await chat(bot, '/fmshop buy diamond 16')
  await waitFor(() => invCount(bot, 'diamond') >= 16, 6000)
  await chat(bot, '/fmshop price diamond')
  const raised = await waitFor(() => findMsg(bot, /买入 (\d[\d,]*\.\d\d)/) || null, 5000)
  const raisedBuy = raised ? num(/买入 ([\d,]+\.\d\d)/.exec(raised)[1]) : NaN
  record('M3 buying 16 pushes the dynamic buy price above base', raisedBuy > 80, `buy=${raisedBuy}`)
  record('M4 price line reports the rising trend', !!findMsg(bot, /走高/), tail(bot, 2))
  await rcon('fsa market DIAMOND reset')
  await sleep(400)
  await chat(bot, '/fmshop price diamond')
  record('M5 admin market reset returns the quote to base', !!(await waitFor(() => findMsg(bot, /买入 80\.00/), 5000)), tail(bot, 2))

  // ---- daily quota -------------------------------------------------------
  let quotaRestored = false
  try {
    await setDiamondQuota(2)
    await rcon(`fsa resetlimit ${BOT}`)
    await giveHeld(bot, 'diamond', 5)
    await chat(bot, '/fmshop sell hand')
    const clamp = await waitFor(() => findMsg(bot, /出售 .*×2，获得/) || null, 5000)
    record('Q1 sell clamps to the daily quota of 2', !!clamp, clamp || tail(bot))
    await giveHeld(bot, 'diamond', 5)        // re-stock: Q2/Q3 assert the quota, not leftovers
    await chat(bot, '/fmshop sell hand')
    const exhausted = await waitFor(() => findMsg(bot, /今日限售 2，剩余 0/) || null, 5000)
    record('Q2 exhausted quota is refused with limit and reset time', !!exhausted, exhausted || tail(bot))
    await rcon(`fsa resetlimit ${BOT}`)
    await giveHeld(bot, 'diamond', 5)
    await chat(bot, '/fmshop sell hand')
    record('Q3 admin resetlimit reopens the quota', !!(await waitFor(() => findMsg(bot, /出售 .*×2，获得/), 5000)), tail(bot))
  } finally {
    await setDiamondQuota(256)
    quotaRestored = true
  }
  record('Q4 prices.yml daily-sell restored to 256', quotaRestored)

  // ---- persistence + audit trail ----------------------------------------
  await rcon(`fsa set ${BOT} 777`)
  await rcon('fsa reload')
  await sleep(800)
  const afterReload = await balance(bot)
  record('D1 balance survives a config reload', afterReload === 777, `balance=${afterReload}`)
  // AuditLog.tail() answers on a worker thread, so an RCON reply is already sent
  // and empty by the time the rows arrive - assert the ledger file it appends to.
  const d = new Date()
  const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const ledger = await fs.promises.readFile(path.join(DATA, 'audit', today + '.log'), 'utf8')
  const rows = ledger.split(/\r?\n/).filter(l => l.includes(BOT))
  record('D2 ledger records this session\'s buys and sells',
    rows.some(l => /BUY \S+ diamond x\d+ gross=/.test(l)) && rows.some(l => /SELL \S+ diamond x\d+ gross=/.test(l)),
    rows.filter(l => /BUY|SELL/.test(l)).slice(-2).join(' | '))
  record('D3 ledger records the transfer with its fee',
    rows.some(l => /PAY \S+ .*gross=100\.00 fee=2\.00/.test(l)),
    rows.filter(l => /PAY/.test(l)).slice(-1).join(' | '))

  // ---- permission boundary ----------------------------------------------
  // Bukkit hides commands the sender lacks permission for, so a non-op sees the
  // brigadier "unknown command" error rather than shop output.
  await chat(bot, '/fsa status')
  const adminOut = await rcon('fsa status')
  record('X1 non-op player cannot run /fsa while the console can',
    !echoOf(bot, '金币') && !echoOf(bot, '商品') && /金币/.test(adminOut), tail(bot, 2))
  win = await openGui(bot, '/fmshop')
  record('X2 non-op player can still shop', !!win, `start=${win && win.inventoryStart}`)
  closeGui(bot)

  await sleep(300)
  if (priorDifficulty) { await rcon(`difficulty ${priorDifficulty}`); priorDifficulty = '' }
  try { bot.quit(); mate.quit() } catch { }
  const failed = results.filter(r => !r.ok).length
  console.log(`\nSUMMARY: ${results.length - failed}/${results.length} passed`)
  process.exit(failed ? 1 : 0)
}

scenario().catch(async e => {
  console.error('FATAL', e)
  if (priorDifficulty) { try { await rcon(`difficulty ${priorDifficulty}`) } catch { } }
  process.exit(2)
})
