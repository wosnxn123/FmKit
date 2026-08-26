// Stale-slot probe: does the client see the cloud slot change on the SAME tick
// as the take, or only after a later click / reopen?
//
// Scenario 1: left-click an occupied cloud slot (take a stack to the cursor).
// Scenario 2: put that stack down in the player's own inventory.
// For each step: every server slot packet touching the watched slots, plus the
// client-visible content of the cloud slot afterwards.
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25576', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.2'
const BOT_NAME = process.env.BOT_NAME || 'FmStale'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')
const sleep = ms => new Promise(r => setTimeout(r, ms))
let T0 = Date.now()
const ms = () => String(Date.now() - T0).padStart(5, ' ')
const log = (...a) => console.log(`[${ms()}ms]`, ...a)

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0), out = ''
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
        const len = buf.readInt32LE(0), id = buf.readInt32LE(4)
        const body = buf.slice(12, 4 + len - 2).toString('utf8')
        buf = buf.slice(4 + len)
        if (id === 1) sock.write(pkt(2, 2, cmd))
        else { out += body; setTimeout(() => { sock.end(); resolve(out) }, 120) }
      }
    })
  })
}
async function cloudStatus() {
  const out = (await rcon(`fmyunbeiadmin status ${BOT_NAME}`)).replace(/\u00a7./g, '')
  const m = out.match(/(\d+)\s*格非空.*?(\d+)\s*件/)
  return m ? `${m[1]}格/${m[2]}件` : '?'
}
function textOf(node) {
  const out = []
  const walk = (n, key) => {
    if (n == null) return
    if (typeof n === 'string') { if (key === 'text' || key === '') out.push(n); return }
    if (typeof n !== 'object') return
    if (Array.isArray(n)) { for (const c of n) walk(c, key); return }
    if (n.type === 'string' && typeof n.value === 'string') { if (key === 'text' || key === '') out.push(n.value); return }
    if (n.value !== undefined) { walk(n.value, key); return }
    for (const [k, v] of Object.entries(n)) if (k !== 'type') walk(v, k)
  }
  walk(node, '')
  return out.join('').replace(/\u00a7./g, '')
}

async function main() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.on('error', e => console.log('bot error:', e.message))
  let ItemCls = null
  const nm = it => it ? `${it.name}x${it.count}` : 'empty'
  const toItem = s => {
    try {
      ItemCls = ItemCls || require('prismarine-item')(bot.registry)
      const it = ItemCls.fromNotch(s)
      return it && it.name !== 'air' && it.count > 0 ? it : null
    } catch { return null }
  }
  let stateId = 0
  let trace = false
  const WATCH = new Set([9, 10, 60, 61])
  bot._client.on('set_slot', p => {
    stateId = p.stateId
    if (trace && WATCH.has(p.slot)) log(`<- set_slot   win=${p.windowId} state=${p.stateId} slot=${p.slot} = ${nm(toItem(p.item ?? p.contents))}`)
  })
  bot._client.on('window_items', p => {
    stateId = p.stateId
    if (!trace) return
    const items = p.items || []
    log(`<- window_items win=${p.windowId} state=${p.stateId} n=${items.length} 9:${nm(toItem(items[9]))} carried=${nm(toItem(p.carriedItem))}`)
  })
  bot._client.on('set_cursor_item', p => { if (trace) log(`<- set_cursor_item ${nm(toItem(p.contents))}`) })
  const onOpenPkt = p => { if (trace) log(`<- open_screen win=${p.windowId} title="${textOf(p.windowTitle ?? p.title)}"`) }
  bot._client.on('open_screen', onOpenPkt)
  bot._client.on('open_window', onOpenPkt)

  await new Promise(r => bot.once('spawn', r))
  await rcon(`gamemode survival ${BOT_NAME}`)
  await rcon(`effect give ${BOT_NAME} minecraft:resistance 600 4 true`)
  await rcon(`clear ${BOT_NAME}`)
  await rcon(`give ${BOT_NAME} minecraft:diamond 64`)
  await sleep(700)

  const open = async () => {
    let w = null
    const h = x => { w = x }
    bot.on('windowOpen', h)
    bot.chat('/fmyunbei')
    for (let i = 0; i < 50 && !w; i++) await sleep(100)
    bot.removeListener('windowOpen', h)
    await sleep(600)
    return bot.currentWindow
  }
  let win = null
  for (let i = 0; i < 4; i++) {
    win = await open()
    if (!win) break
    if (textOf(win.title).includes('云背包')) break
    const li = win.slots.findIndex((s, k) => k < 54 && s && s.name === 'lime_concrete')
    if (li < 0) break
    await bot.clickWindow(li, 0, 0).catch(() => { })
    await sleep(900)
  }
  if (!win) { console.log('no GUI'); process.exit(2) }

  const rawClick = (slot, button, mode) => {
    const w = bot.currentWindow
    bot._client.write('window_click', {
      windowId: w.id, stateId, slot, mouseButton: button, mode, changedSlots: [], cursorItem: null
    })
  }
  const cur = () => bot.currentWindow || win
  const shown = i => {
    const it = cur().slots[i]
    if (!it) return 'empty'
    let extra = ''
    try {
      const lore = textOf((it.components || []).find(c => /lore/i.test(String(c.type)))?.data)
      const m = lore.match(/数量[：:]\s*([0-9]+)/)
      if (m) extra = `(数量 ${m[1]})`
    } catch { }
    return `${it.name}x${it.count}${extra}`
  }

  // drain whatever the cloud already holds, then seed a single sub-cap stack so
  // that taking it MUST empty the slot -- the transition the user reported
  rawClick(50, 0, 0)                                  // 一键取回
  await sleep(1200)
  await rcon(`clear ${BOT_NAME}`)
  await sleep(400)
  console.log(`\ndrained cloud: ${await cloudStatus()}`)
  await rcon(`give ${BOT_NAME} minecraft:diamond 32`)
  await sleep(600)
  rawClick(47, 0, 0)                                  // 一键存入
  await sleep(1500)
  console.log(`seeded cloud:  ${await cloudStatus()}   client slot9=${shown(9)}`)

  console.log('\n===== 1: left-click the slot, taking ALL 32 (slot must empty) =====')
  T0 = Date.now(); trace = true
  rawClick(9, 0, 0)
  await sleep(250)
  log(`client slot9 = ${shown(9)}   (250ms)`)
  await sleep(1500)
  log(`client slot9 = ${shown(9)}   (1.75s)`)
  console.log(`  server: ${await cloudStatus()}`)
  trace = false

  console.log('\n===== 2: put the cursor stack down in the player inventory =====')
  T0 = Date.now(); trace = true
  rawClick(60, 0, 0)
  await sleep(250)
  log(`client slot9 = ${shown(9)}  slot60 = ${shown(60)}   (250ms)`)
  await sleep(1500)
  log(`client slot9 = ${shown(9)}  slot60 = ${shown(60)}   (1.75s)`)
  console.log(`  server: ${await cloudStatus()}`)
  trace = false

  console.log('\n===== 3: re-deposit, then SHIFT-click the cloud slot (take to inv) =====')
  rawClick(47, 0, 0)
  await sleep(1400)
  console.log(`  seeded: ${await cloudStatus()}   client slot9=${shown(9)}`)
  T0 = Date.now(); trace = true
  rawClick(9, 0, 1)                                   // shift-left = take into inventory
  await sleep(250)
  log(`client slot9 = ${shown(9)}   (250ms)`)
  await sleep(1500)
  log(`client slot9 = ${shown(9)}   (1.75s)`)
  trace = false
  console.log(`  server: ${await cloudStatus()}`)

  await sleep(300)
  try { bot.closeWindow(cur()) } catch { }
  await sleep(400)
  bot.end()
  process.exit(0)
}
main().catch(e => { console.error('FATAL', e); process.exit(2) })
