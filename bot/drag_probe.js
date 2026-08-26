// FmYunbei drag desync / conservation probe.
// Ground truth comes from the SERVER only:
//   inventory -> `data get entity <p> Inventory`   (cursor is NOT part of it)
//   cloud     -> `fmyunbeiadmin status <p>`
//   cursor    -> mirrored from set_cursor_item / window_items.carriedItem
// Total dirt must stay 5 at every quiescent point.
//
// Run: node drag_probe.js   (server: servers/folia, port 25568)
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25576', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.2'
const BOT_NAME = process.env.BOT_NAME || 'FmDrag'
const N = 5

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')

const sleep = ms => new Promise(r => setTimeout(r, ms))
let T0 = Date.now()
const ms = () => String(Date.now() - T0).padStart(5, ' ')
const log = (...a) => console.log(`[${ms()}ms]`, ...a)
const fails = []
const check = (name, ok, detail) => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -- ' + detail : ''}`)
  if (!ok) fails.push(name)
}

// ---- RCON ----
function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    let out = ''
    const pkt = (id, type, body) => {
      const b = Buffer.from(body, 'utf8')
      const p = Buffer.alloc(14 + b.length)
      p.writeInt32LE(10 + b.length, 0); p.writeInt32LE(id, 4); p.writeInt32LE(type, 8)
      b.copy(p, 12); return p
    }
    sock.on('error', reject)
    sock.on('connect', () => sock.write(pkt(1, 3, RCON_PASS)))
    sock.on('data', d => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4 && buf.length >= 4 + buf.readInt32LE(0)) {
        const len = buf.readInt32LE(0)
        const id = buf.readInt32LE(4)
        const body = buf.slice(12, 4 + len - 2).toString('utf8')
        buf = buf.slice(4 + len)
        if (id === 1) sock.write(pkt(2, 2, cmd))
        else { out += body; setTimeout(() => { sock.end(); resolve(out) }, 120) }
      }
    })
  })
}
async function cloudCounts() {
  const out = (await rcon(`fmyunbeiadmin status ${BOT_NAME}`)).replace(/\u00a7./g, '')
  const m = out.match(/(\d+)\s*格非空.*?(\d+)\s*件/)
  return m ? { used: +m[1], items: +m[2] } : { used: -1, items: -1 }
}
/** Dirt in the player's 36 inv slots, as last sent by the server (window_items/set_slot). */
function invDirtFrom(bot) {
  const w = bot.currentWindow
  let sum = 0
  if (w) {
    const start = w.inventoryStart || 54
    for (let i = start; i < w.slots.length; i++) {
      const s = w.slots[i]
      if (s && s.name === 'dirt') sum += s.count
    }
  } else {
    for (const it of bot.inventory.items()) if (it.name === 'dirt') sum += it.count
  }
  return sum
}

const nm = it => it ? `${it.name}x${it.count}` : 'empty'

async function main() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.on('error', e => console.log('bot error:', e.message))

  let stateId = 0
  bot._client.on('window_items', p => { stateId = p.stateId })
  bot._client.on('set_slot', p => { stateId = p.stateId })
  const rawClick = (slot, button, mode) => {
    const w = bot.currentWindow
    if (!w) return
    bot._client.write('window_click', {
      windowId: w.id, stateId, slot, mouseButton: button, mode, changedSlots: [], cursorItem: null
    })
  }
  let ItemCls = null
  const itemCls = () => ItemCls || (ItemCls = require('prismarine-item')(bot.registry))
  const toItem = s => {
    try {
      const it = itemCls().fromNotch(s)
      return it && it.name !== 'air' && it.count > 0 ? it : null
    } catch { return null }
  }
  let cursor = null
  const setCursor = (w, s) => { cursor = toItem(s); if (w) w.selectedItem = cursor }

  const titleText = w => {
    const out = []
    const walk = (n, key) => {
      if (n == null) return
      if (typeof n === 'string') { if (key === 'text') out.push(n); return }
      if (typeof n !== 'object') return
      if (Array.isArray(n)) { for (const c of n) walk(c, key); return }
      if (n.type === 'string' && typeof n.value === 'string') { if (key === 'text') out.push(n.value); return }
      if (n.value !== undefined) walk(n.value, key)
      for (const [k, v] of Object.entries(n)) if (k !== 'value' && k !== 'type') walk(v, k)
    }
    walk(w.title !== undefined ? w.title : w.windowTitle, '')
    return out.join('')
  }
  // ---- packet timeline ----
  let trace = false
  const tl = []
  const push = e => { tl.push(e); if (trace) log(e) }
  bot._client.on('set_cursor_item', p => {
    setCursor(bot.currentWindow || bot.inventory, p.contents)
    push(`<- set_cursor_item  ${nm(toItem(p.contents))}`)
  })
  bot._client.on('window_items', p => {
    if ('carriedItem' in p) setCursor(p.windowId === 0 ? bot.inventory : bot.currentWindow, p.carriedItem)
    push(`<- window_items win=${p.windowId} carried=${nm(toItem(p.carriedItem))}`)
  })
  const opens = []          // {id, title} for every open packet the server sends
  const onOpenPkt = p => {
    const t = titleText(p)
    opens.push({ id: p.windowId, title: t })
    push(`<- open_screen  win=${p.windowId}  title="${t}"`)
  }
  bot._client.on('open_screen', onOpenPkt)
  bot._client.on('open_window', onOpenPkt)
  bot._client.on('close_window', p => push(`<- close_window win=${p.windowId}`))

  await new Promise(res => bot.once('spawn', res))
  log('spawned:', bot.username)
  await rcon(`effect give ${BOT_NAME} minecraft:resistance 600 4 true`)
  await rcon(`gamemode survival ${BOT_NAME}`)

  const openGui = async () => {
    let w = null
    const onOpen = x => { w = x }
    bot.on('windowOpen', onOpen)
    bot.chat('/fmyunbei')
    for (let i = 0; i < 60 && !w; i++) await sleep(100)
    bot.removeListener('windowOpen', onOpen)
    await sleep(500)
    return bot.currentWindow
  }
  // FmTerm shows a terms menu first; accept it (lime_concrete) then retry.
  const openCloud = async () => {
    for (let attempt = 0; attempt < 4; attempt++) {
      const w = await openGui()
      if (!w) return null
      const t = titleText(w)
      if (t.includes('云背包')) { log(`cloud GUI open id=${w.id} title="${t}"`); return w }
      const li = w.slots.findIndex((s, i) => i < 54 && s && s.name === 'lime_concrete')
      if (li < 0) return null
      log('FmTerm terms menu, accepting slot', li)
      await bot.clickWindow(li, 0, 0).catch(() => { })
      await sleep(900)
    }
    return null
  }
  const closeGui = async () => {
    if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch { } }
    await sleep(700)
  }
  const dirtSlot = () => {
    const w = bot.currentWindow
    return w.slots.findIndex((s, i) => i >= (w.inventoryStart || 54) && s && s.name === 'dirt')
  }
  const total = async tag => {
    const [inv, cl] = [invDirtFrom(bot), await cloudCounts()]
    const cur = cursor && cursor.name === 'dirt' ? cursor.count : 0
    const t = inv + cl.items + cur
    console.log(`  TOTAL[${tag}] inv=${inv} cloud=${cl.items}(${cl.used} slots) cursor=${cur} => ${t}`)
    return { inv, cloud: cl, cur, t }
  }

  // ---------------------------------------------------------- phase 0: clean slate
  let win = await openCloud()
  if (!win) { console.log('FATAL: cloud GUI did not open'); return finish(bot) }
  for (let i = 0; i < 6 && (await cloudCounts()).items > 0; i++) {
    await bot.clickWindow(50, 0, 0).catch(() => { })   // takeAll
    await sleep(900)
  }
  await closeGui()
  await rcon(`clear ${BOT_NAME}`)
  await rcon(`give ${BOT_NAME} minecraft:dirt ${N}`)
  await sleep(700)
  const base = await total('clean slate')
  check('clean slate: 5 dirt in inv, cloud empty', base.inv === N && base.cloud.items === 0, `inv=${base.inv} cloud=${base.cloud.items}`)

  win = await openCloud()
  if (!win) { console.log('FATAL: reopen failed'); return finish(bot) }

  // ---------------------------------------------------------- A: left-drag over 5 slots
  console.log(`\n===== A: left-drag ${N} dirt over 5 empty cloud slots =====`)
  T0 = Date.now(); trace = true; tl.length = 0; opens.length = 0
  rawClick(dirtSlot(), 0, 0)
  await sleep(400)
  log('cursor =', nm(cursor))
  rawClick(-999, 0, 5)
  await sleep(60)
  for (const t of [9, 10, 11, 12, 13]) { rawClick(t, 1, 5); await sleep(40) }
  rawClick(-999, 2, 5)
  log('drag-end sent')
  await sleep(1500)
  trace = false
  const winId = win.id
  const newWindows = opens.filter(o => o.id !== winId).length
  const restored = tl.filter(l => l.includes(`set_cursor_item  dirtx${N}`)).length
  log('cursor after drag =', nm(cursor))
  const a = await total('after left-drag')
  check('A: nothing duplicated or lost', a.t === N, `total=${a.t}`)
  check('A: all 5 stored in cloud', a.cloud.items === N && a.cloud.used === N, `cloud=${a.cloud.items} slots=${a.cloud.used}`)
  check('A: cursor emptied', a.cur === 0, `cursor=${a.cur}`)
  check('A: window id never changed (retitle in place)', newWindows === 0,
    `other ids=${opens.filter(o => o.id !== winId).map(o => o.id).join(',')}`)
  check('A: stale cursor never resurrected', restored === 0, `restores=${restored}`)
  const lastTitle = (opens[opens.length - 1] || {}).title || ''
  check('A: title retitled to 5/27 with colour codes intact',
    lastTitle.includes('5/27') && !lastTitle.includes('<'), `title="${lastTitle}"`)

  // reset: takeAll
  for (let i = 0; i < 4 && (await cloudCounts()).items > 0; i++) {
    await bot.clickWindow(50, 0, 0).catch(() => { })
    await sleep(900)
  }
  const r = await total('after takeAll')
  check('reset: back to 5 in inventory', r.t === N && r.cloud.items === 0, `inv=${r.inv} cloud=${r.cloud.items} cursor=${r.cur}`)

  // ---------------------------------------------------------- B: right-drag + immediate second drag
  console.log('\n===== B: right-drag over 4 slots, then a second drag one tick later =====')
  win = bot.currentWindow || win
  if (dirtSlot() < 0) { console.log('  no dirt in inv, skipping B'); return report(bot) }
  T0 = Date.now(); trace = true; tl.length = 0; opens.length = 0
  rawClick(dirtSlot(), 0, 0)
  await sleep(400)
  log('cursor =', nm(cursor))
  rawClick(-999, 4, 5)
  await sleep(60)
  for (const t of [9, 10, 11, 12]) { rawClick(t, 5, 5); await sleep(40) }
  rawClick(-999, 6, 5)
  log('right-drag-end sent (4 stored, 1 should stay on cursor)')
  await sleep(30)
  rawClick(-999, 0, 5)
  await sleep(15)
  rawClick(13, 1, 5)
  await sleep(15)
  rawClick(-999, 2, 5)
  log('second drag sent inside the deferred-write window')
  await sleep(1800)
  trace = false
  log('cursor after both drags =', nm(cursor))
  const b = await total('after right-drag + second drag')
  check('B: nothing duplicated or lost', b.t === N, `total=${b.t}`)
  check('B: second drag not silently dropped', b.cloud.items === N, `cloud=${b.cloud.items} (expect all ${N} stored)`)

  // ---------------------------------------------------------- C: close while holding
  console.log('\n===== C: close the GUI while the cursor holds a stack =====')
  for (let i = 0; i < 4 && (await cloudCounts()).items > 0; i++) {
    await bot.clickWindow(50, 0, 0).catch(() => { })
    await sleep(900)
  }
  win = bot.currentWindow || win
  if (dirtSlot() >= 0) {
    rawClick(dirtSlot(), 0, 0)     // cursor = 5 dirt
    await sleep(400)
    rawClick(9, 0, 0)              // store all into slot 0 -> triggers retitle
    await sleep(120)
    await closeGui()               // close inside the deferred window
    await sleep(900)
    const c = await total('after store+immediate close')
    check('C: nothing duplicated or lost', c.t === N, `total=${c.t}`)
  }
  return report(bot)
}

async function report(bot) {
  console.log(`\n==== ${fails.length ? 'FAILED: ' + fails.join(' | ') : 'all checks passed'} ====`)
  return finish(bot)
}
async function finish(bot) {
  try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow) } catch { }
  await sleep(600)
  try { bot.end() } catch { }
  process.exit(fails.length ? 1 : 0)
}

main().catch(e => { console.error('FATAL', e); process.exit(2) })
