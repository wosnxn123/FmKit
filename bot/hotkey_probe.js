// FmYunbei number-key / off-hand / shift / double-click semantics probe.
// Drives protocol-level window_click modes 1 (quick move), 2 (swap, button 0-8
// = hotbar, 40 = off hand) and 6 (collect) against the cloud GUI.
// Run: node hotkey_probe.js   (server: servers/folia261, port 25567)
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25567', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25575', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.1'
const BOT_NAME = process.env.BOT_NAME || 'FmTester'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')

const results = []
function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  [' + detail + ']' : ''}`)
}
function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }
async function waitFor(cond, timeoutMs = 8000, poll = 150) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    if (await cond()) return true
    await sleep(poll)
  }
  return false
}

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    const send = (id, type, body) => {
      const blen = Buffer.byteLength(body)
      const b = Buffer.alloc(14 + blen)
      b.writeInt32LE(b.length - 4, 0); b.writeInt32LE(id, 4); b.writeInt32LE(type, 8); b.write(body, 12)
      sock.write(b)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout')) }, 15000)
    sock.on('connect', () => send(1, 3, RCON_PASS))
    sock.on('data', d => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4) {
        const len = buf.readInt32LE(0)
        if (buf.length < 4 + len) break
        const id = buf.readInt32LE(4), type = buf.readInt32LE(8)
        const body = buf.slice(10, 4 + len - 2).toString()
        buf = buf.slice(4 + len)
        if (type === 2 && id === 1) send(2, 2, cmd)
        else if (type === 0 && id === 2) { clearTimeout(timer); sock.end(); resolve(body) }
        else if (type === 2 && id === -1) { clearTimeout(timer); sock.end(); reject(new Error('rcon auth failed')) }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}
/** cloud totals from the admin status line: 存放：X 格非空 / 共 Y 件 */
async function cloud() {
  const out = (await rcon(`fmyunbeiadmin status ${BOT_NAME}`)).replace(/\u00a7./g, '')
  const m = out.match(/(\d+)\s*格非空.*?(\d+)\s*件/)
  return m ? { used: +m[1], items: +m[2] } : { used: -1, items: -1, raw: out }
}

const fs = require('fs')
const path = require('path')
const CFG = process.env.CFG
  || path.join(__dirname, '..', 'servers', 'folia_probe', 'plugins', 'FmYunbei', 'config.yml')
/** rewrite sort.key on disk, reload the plugin, report how many category files exist */
async function setSortKey(mode) {
  const txt = fs.readFileSync(CFG, 'utf8')
  const re = /(^sort:[ \t]*\r?\n(?:[ \t]+[^\n]*\r?\n)*?[ \t]+key:[ \t]*)\w+/m
  fs.writeFileSync(CFG, re.test(txt) ? txt.replace(re, `$1${mode}`) : `${txt}\nsort:\n  key: ${mode}\n`)
  const out = (await rcon('fmyunbeiadmin reload')).replace(/\u00a7./g, '')
  await sleep(800)
  const dir = path.join(path.dirname(CFG), 'categories')
  const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter(f => f.endsWith('.txt')) : []
  return `${files.length} files, reload=${out.includes('重载') ? 'ok' : out.trim().slice(0, 40)}`
}

scenario().catch(e => { console.error('FATAL', e); process.exit(2) })

async function scenario() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.on('error', e => console.log('bot error:', e.message))
  let protoStateId = 0
  bot._client.on('window_items', p => { protoStateId = p.stateId })
  bot._client.on('set_slot', p => { protoStateId = p.stateId })
  let ItemCls = null
  const itemCls = () => ItemCls || (ItemCls = require('prismarine-item')(bot.registry))
  const setCursor = (w, slot) => { if (w) w.selectedItem = itemCls().fromNotch(slot) }
  bot._client.on('set_cursor_item', p => setCursor(bot.currentWindow || bot.inventory, p.contents))
  bot._client.on('window_items', p => {
    if (!('carriedItem' in p)) return
    setCursor(p.windowId === 0 ? bot.inventory : bot.currentWindow, p.carriedItem)
  })
  const chat = []
  bot.on('messagestr', m => chat.push(m.replace(/\u00a7./g, '')))

  await new Promise(res => bot.once('spawn', res))
  console.log('bot spawned:', bot.username, 'proto version', VERSION)
  await rcon(`effect give ${BOT_NAME} minecraft:resistance 600 4 true`)
  await rcon(`gamemode survival ${BOT_NAME}`)

  /** protocol-level click: no mineflayer prediction, server state is the only truth */
  const click = async (slot, button, mode) => {
    const w = bot.currentWindow
    if (!w) throw new Error('no window open')
    bot._client.write('window_click', {
      windowId: w.id, stateId: protoStateId, slot, mouseButton: button, mode,
      changedSlots: [], cursorItem: null
    })
    await sleep(700) // render + retitle reopen + the 1-tick deferred cursor write
  }
  const openGui = async () => {
    let win = null
    const onOpen = w => { win = w }
    bot.on('windowOpen', onOpen)
    bot.chat('/fmyunbei')
    await waitFor(async () => win !== null, 6000)
    bot.removeListener('windowOpen', onOpen)
    await sleep(500)
    return bot.currentWindow
  }
  /** name of a window slot, 'air' when empty */
  const at = i => {
    const s = bot.currentWindow && bot.currentWindow.slots[i]
    return s ? `${s.name}x${s.count}` : 'air'
  }
  const offhand = async () => {
    // container windows carry no off-hand slot; the server syncs it on window 0
    const s = bot.inventory && bot.inventory.slots[45]
    if (s) return `${s.name}x${s.count}`
    for (const path of ['equipment.offhand', 'Inventory[{Slot:-106b}]']) {
      const out = await rcon(`data get entity ${BOT_NAME} ${path}`)
      if (out.includes('minecraft:')) return out.slice(out.indexOf('minecraft:')).replace(/[\r\n]/g, '').slice(0, 60)
    }
    return 'air'
  }

  /** cloud slot 0 renders at raw window slot 9 when unlocked >= 27 */
  const CLOUD0 = 9
  const HOT0 = 81, HOT1 = 82
  const reset = async (gives, wantCloud = 0) => {
    if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch { } ; await sleep(300) }
    await rcon(`clear ${BOT_NAME}`)
    await rcon(`fmyunbeiadmin clear ${BOT_NAME} confirm`)
    for (const g of gives) await rcon(g)
    await sleep(400)
    const c = await cloud()
    if (c.items !== wantCloud) console.log(`  !! reset drift: cloud=${c.items} want=${wantCloud}`)
  }

  // ---------------------------------------------------------------- case 1+2
  await reset([`give ${BOT_NAME} minecraft:stone 64`])
  let win = await openGui()
  if (!win) { record('open gui', false, 'no window'); return finish(bot) }
  record('open gui', true, `title slots=${win.slots.length} hot0=${at(HOT0)}`)
  record('cloud slot 0 empty at start', at(CLOUD0) === 'air', at(CLOUD0))

  await click(CLOUD0, 0, 2) // number key 1 on an empty cloud slot
  let c = await cloud()
  record('num-key: empty cloud slot <- whole hotbar stack',
    c.items === 64 && at(CLOUD0) === 'stonex64' && at(HOT0) === 'air',
    `cloud=${c.items} slot=${at(CLOUD0)} hot0=${at(HOT0)}`)

  await click(CLOUD0, 0, 2) // number key 1 again, hotbar slot now empty
  c = await cloud()
  record('num-key: empty hand <- one stack unit out of the cloud',
    c.items === 0 && at(CLOUD0) === 'air' && at(HOT0) === 'stonex64',
    `cloud=${c.items} slot=${at(CLOUD0)} hot0=${at(HOT0)}`)

  // ------------------------------------------------------------------ case 3
  await reset([`fmyunbeiadmin give ${BOT_NAME} STONE 64`, `give ${BOT_NAME} minecraft:dirt 32`], 64)
  win = await openGui()
  await click(CLOUD0, 0, 2) // dirt sits in hotbar 0 after the reset
  c = await cloud()
  record('num-key: single-stack slot exchanges with a different item',
    c.items === 32 && at(CLOUD0) === 'dirtx32' && at(HOT0) === 'stonex64',
    `cloud=${c.items} slot=${at(CLOUD0)} hot0=${at(HOT0)}`)

  // ------------------------------------------------------------------ case 4
  await reset([`fmyunbeiadmin give ${BOT_NAME} STONE 128`, `give ${BOT_NAME} minecraft:dirt 32`], 128)
  win = await openGui()
  chat.length = 0
  await click(CLOUD0, 0, 2)
  c = await cloud()
  record('num-key: bulk slot refuses a different item instead of dropping the surplus',
    c.items === 128 && at(HOT0) === 'dirtx32' && chat.some(m => m.includes('已存放物品')),
    `cloud=${c.items} hot0=${at(HOT0)} msg=${chat.filter(m => m.includes('存放') || m.includes('上限')).join('|')}`)

  // ------------------------------------------------------------------ case 5
  await reset([`fmyunbeiadmin give ${BOT_NAME} STONE 128`, `give ${BOT_NAME} minecraft:stone 32`], 128)
  win = await openGui()
  await click(CLOUD0, 0, 2)
  c = await cloud()
  record('num-key: same item, bulk slot hands over one unit and takes the hand stack',
    c.items === 96 && at(HOT0) === 'stonex64',
    `cloud=${c.items} (want 96) hot0=${at(HOT0)} (want stonex64)`)

  // ------------------------------------------------------------------ case 6
  await reset([`fmyunbeiadmin give ${BOT_NAME} STONE 64`], 64)
  win = await openGui()
  await click(CLOUD0, 40, 2) // off-hand key
  c = await cloud()
  const off = await offhand()
  record('off-hand key: cloud slot -> off hand',
    c.items === 0 && off.includes('stone'),
    `cloud=${c.items} offhand=${off}`)

  await click(CLOUD0, 40, 2) // and back into the empty cloud slot
  c = await cloud()
  record('off-hand key: off hand -> empty cloud slot',
    c.items === 64 && (await offhand()) === 'air',
    `cloud=${c.items} offhand=${await offhand()}`)

  // ------------------------------------------------------------------ case 7
  await reset([`give ${BOT_NAME} minecraft:stone 64`])
  win = await openGui()
  chat.length = 0
  await click(HOT0, 0, 1) // shift-click a player-inventory slot
  c = await cloud()
  record('shift-click in player inventory deposits into the cloud',
    c.items === 64 && at(HOT0) === 'air' && at(CLOUD0) === 'stonex64',
    `cloud=${c.items} hot0=${at(HOT0)} slot=${at(CLOUD0)}`)

  // ------------------------------------------------------------------ case 8
  await reset([`fmyunbeiadmin give ${BOT_NAME} STONE 64`, `give ${BOT_NAME} minecraft:stone 20`], 64)
  win = await openGui()
  await click(HOT0, 0, 0) // pick the 20 stack onto the cursor
  const carried = bot.currentWindow && bot.currentWindow.selectedItem
  await click(HOT0, 0, 6) // double-click collect
  c = await cloud()
  const cur = bot.currentWindow && bot.currentWindow.selectedItem
  record('double-click in player inventory collects from the cloud',
    c.items === 20 && cur && cur.count === 64,
    `cloud=${c.items} (want 20) cursor=${cur ? cur.name + 'x' + cur.count : 'air'} (want stonex64) picked=${carried ? carried.count : '-'}`)

  // ------------------------------------------------------------------ case 9
  // pure player-inventory number key must stay vanilla: hotbar 0 <-> main grid
  await reset([`give ${BOT_NAME} minecraft:dirt 5`])
  win = await openGui()
  await click(HOT0, 3, 2) // move dirt from hotbar 0 to hotbar 3
  record('num-key inside the player inventory still runs vanilla',
    at(HOT0) === 'air' && at(HOT0 + 3) === 'dirtx5',
    `hot0=${at(HOT0)} hot3=${at(HOT0 + 3)}`)

  // ----------------------------------------------------------------- case 10
  // toolbar slot 46 = one-key tidy: merge fragmented stacks, compact to front
  await reset([`give ${BOT_NAME} minecraft:stone 20`])
  win = await openGui()
  await click(CLOUD0, 0, 2)          // 20 stone -> cloud slot 0
  await rcon(`give ${BOT_NAME} minecraft:stone 20`)
  await sleep(400)
  await click(CLOUD0 + 5, 0, 2)      // 20 stone -> cloud slot 5
  let pre = await cloud()
  record('fragmented cloud before tidy', pre.used === 2 && pre.items === 40,
    `used=${pre.used} items=${pre.items} s0=${at(CLOUD0)} s5=${at(CLOUD0 + 5)}`)
  await click(46, 0, 0)              // tidy button
  c = await cloud()
  record('tidy button merges and compacts to the front',
    c.used === 1 && c.items === 40 && at(CLOUD0) === 'stonex40' && at(CLOUD0 + 5) === 'air',
    `used=${c.used} items=${c.items} s0=${at(CLOUD0)} s5=${at(CLOUD0 + 5)}`)


  // ----------------------------------------------------------------- case 11
  // sort.key=api: tidy groups by the vanilla creative tab before the name
  // building_blocks(100) < combat(600) < food(700)
  const fillMixed = async () => {
    await reset([`give ${BOT_NAME} minecraft:apple 5`])
    win = await openGui()
    await click(CLOUD0, 0, 2)
    await rcon(`give ${BOT_NAME} minecraft:iron_sword 1`)
    await sleep(400)
    await click(CLOUD0 + 1, 0, 2)
    await rcon(`give ${BOT_NAME} minecraft:stone 20`)
    await sleep(400)
    await click(CLOUD0 + 2, 0, 2)
  }
  const apiCfg = await setSortKey('api')
  await fillMixed()
  record('mixed cloud before tidy',
    at(CLOUD0) === 'applex5' && at(CLOUD0 + 1) === 'iron_swordx1' && at(CLOUD0 + 2) === 'stonex20',
    `s0=${at(CLOUD0)} s1=${at(CLOUD0 + 1)} s2=${at(CLOUD0 + 2)} cfg=${apiCfg}`)
  await click(46, 0, 0)
  record('sort.key=api groups by creative tab (blocks < combat < food)',
    at(CLOUD0) === 'stonex20' && at(CLOUD0 + 1) === 'iron_swordx1' && at(CLOUD0 + 2) === 'applex5',
    `s0=${at(CLOUD0)} s1=${at(CLOUD0 + 1)} s2=${at(CLOUD0 + 2)}`)

  // ----------------------------------------------------------------- case 12
  // sort.key=categories: files generated from the api grouping, same order
  const cfgOut = await setSortKey('categories')
  record('categories/ generated from the api buckets',
    parseInt(cfgOut, 10) >= 5 && cfgOut.includes('reload=ok'), cfgOut)
  await fillMixed()
  await click(46, 0, 0)
  record('sort.key=categories keeps the generated file order',
    at(CLOUD0) === 'stonex20' && at(CLOUD0 + 1) === 'iron_swordx1' && at(CLOUD0 + 2) === 'applex5',
    `s0=${at(CLOUD0)} s1=${at(CLOUD0 + 1)} s2=${at(CLOUD0 + 2)}`)
  await setSortKey('api') // leave the probe server as we found it

  return finish(bot)
}

function finish(bot) {
  const bad = results.filter(r => !r.ok)
  console.log(`\n${results.length - bad.length}/${results.length} passed`)
  if (bad.length) console.log('FAILED: ' + bad.map(r => r.name).join(' | '))
  try { bot.quit() } catch { }
  setTimeout(() => process.exit(bad.length ? 1 : 0), 500)
}
