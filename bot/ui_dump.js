// Textual screenshot of the live FmYunbei GUI: every slot's material, display
// name and lore, decoded from the server's own window packets.
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25576', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.2'
const BOT_NAME = process.env.BOT_NAME || 'FmUi'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')
const sleep = ms => new Promise(r => setTimeout(r, ms))

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

/** Flatten any chat component (JSON or NBT-ish) to plain text. */
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
  let openTitle = ''
  const onOpen = p => { openTitle = textOf(p.windowTitle !== undefined ? p.windowTitle : p.title) }
  bot._client.on('open_screen', onOpen)
  bot._client.on('open_window', onOpen)

  await new Promise(r => bot.once('spawn', r))
  await rcon(`gamemode survival ${BOT_NAME}`)
  await rcon(`effect give ${BOT_NAME} minecraft:resistance 600 4 true`)
  await rcon(`clear ${BOT_NAME}`)
  await rcon(`give ${BOT_NAME} minecraft:diamond 128`)
  await sleep(600)

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
  for (let attempt = 0; attempt < 4; attempt++) {
    win = await open()
    if (!win) break
    const t = textOf(win.title) || openTitle
    if (t.includes('云背包')) { openTitle = t; break }
    const li = win.slots.findIndex((s, k) => k < 54 && s && s.name === 'lime_concrete')
    if (li < 0) { console.log(`unexpected menu "${t}"`); break }
    console.log(`terms menu "${t}" -> accepting slot ${li}`)
    await bot.clickWindow(li, 0, 0).catch(() => { })
    await sleep(1000)
  }
  if (!win || !(textOf(win.title) || '').includes('云背包')) {
    console.log('GUI did not open; title=', textOf(win && win.title))
    process.exit(2)
  }
  await bot.clickWindow(51, 0, 0).catch(() => { })   // one-click deposit
  await sleep(1500)
  // an in-place retitle makes mineflayer rebuild its window model, so always
  // read the CURRENT window rather than the handle captured at open time
  win = bot.currentWindow || win

  const nameOf = it => {
    if (!it) return null
    const raw = it.nbt ? textOf(it.nbt.value?.display?.value?.Name) : ''
    const custom = it.customName ? textOf(it.customName) : ''
    const comp = it.components && it.components.find
      ? it.components.find(c => c.type === 'custom_name' || c.type === 8)
      : null
    return (custom || raw || (comp ? textOf(comp.data) : '') || '').trim()
  }
  const loreOf = it => {
    if (!it) return []
    const comp = it.components && it.components.find
      ? it.components.find(c => c.type === 'lore' || c.type === 9)
      : null
    let node = comp ? comp.data : (it.nbt ? it.nbt.value?.display?.value?.Lore : null)
    if (!node) return []
    const arr = node.value?.value ?? node.value ?? node
    return (Array.isArray(arr) ? arr : [arr]).map(textOf).filter(Boolean)
  }

  console.log(`\nTITLE: ${openTitle}\n`)
  console.log('=== 槽位网格 (0-53) ===')
  for (let row = 0; row < 6; row++) {
    const cells = []
    for (let c = 0; c < 9; c++) {
      const it = win.slots[row * 9 + c]
      cells.push((it ? it.name : '·').padEnd(24).slice(0, 24))
    }
    console.log(`r${row} ` + cells.join(' '))
  }
  console.log('\n=== 每格文案 ===')
  for (let i = 0; i < 54; i++) {
    const it = win.slots[i]
    if (!it) continue
    const n = nameOf(it)
    const lore = loreOf(it)
    if (!n && !lore.length) continue      // filler pane
    console.log(`\n[${i}] ${it.name}${it.count > 1 ? ' x' + it.count : ''}  ${n}`)
    for (const l of lore) console.log(`      ${l}`)
  }
  await sleep(300)
  try { bot.closeWindow(win) } catch { }
  await sleep(400)
  bot.end()
  process.exit(0)
}
main().catch(e => { console.error('FATAL', e); process.exit(2) })
