// Dump raw slot bytes for cloud slot 0 after storing 128 stone.
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', '10')
const RCON_PORT = parseInt(process.env.RCON_PORT || '25576', '10')
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.2'
const BOT_NAME = process.env.BOT_NAME || 'FmTester'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')

function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }
async function waitFor(cond, timeoutMs = 8000, poll = 150) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) { if (await cond()) return true; await sleep(poll) }
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
async function click(bot, slot, button = 0, mode = 0) {
  try { await bot.clickWindow(slot, button, mode) } catch (e) { console.log(`click err ${e.message}`) }
  await sleep(300)
}
async function openGui(bot, cmd) {
  let win = null
  const onOpen = w => { win = w }
  bot.on('windowOpen', onOpen)
  bot.chat(cmd)
  await waitFor(() => win !== null, 6000)
  bot.removeListener('windowOpen', onOpen)
  await sleep(400)
  return bot.currentWindow
}

async function scenario() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.on('error', e => console.log('bot error:', e.message))
  let ItemCls = null
  const itemCls = () => ItemCls || (ItemCls = require('prismarine-item')(bot.registry))
  const setCursor = (w, slot) => { if (w) w.selectedItem = itemCls().fromNotch(slot) }
  bot._client.on('set_cursor_item', p => setCursor(bot.currentWindow || bot.inventory, p.contents))
  bot._client.on('window_items', p => {
    if ('carriedItem' in p) setCursor(p.windowId === 0 ? bot.inventory : bot.currentWindow, p.carriedItem)
    if (p.windowId === (bot.currentWindow ? bot.currentWindow.id : -1) && p.items && p.items[0]) {
      const raw = p.items[0]
      console.log('window_items slot0 raw:', JSON.stringify(raw))
    }
  })
  bot._client.on('set_slot', p => {
    if (p.windowId === (bot.currentWindow ? bot.currentWindow.id : -1) && p.slot === 0) {
      console.log('set_slot slot0 raw:', JSON.stringify(p.item))
    }
  })
  await new Promise(res => bot.once('spawn', res))
  console.log('bot spawned')

  await rcon(`clear ${BOT_NAME}`)
  await rcon(`give ${BOT_NAME} minecraft:stone 64`)
  await rcon(`give ${BOT_NAME} minecraft:stone 64`)
  await sleep(500)

  let win = await openGui(bot, '/fmyunbei')
  if (!win) { console.log('FAIL open gui'); process.exit(1) }
  const invStart = win.inventoryStart || 54
  const findStack = () => win.slots.findIndex((s, i) => i >= invStart && s && s.name === 'stone' && s.count === 64)

  let s0 = findStack()
  await click(bot, s0); await click(bot, 0)      // store 64
  win = bot.currentWindow
  s0 = findStack()
  await click(bot, s0); await click(bot, 0)      // merge to 128
  await sleep(1500)
  console.log('parsed slot0:', win.slots[0] ? `${win.slots[0].name}x${win.slots[0].count} displayName=${win.slots[0].displayName}` : 'empty')
  const st = (await rcon(`fmyunbeiadmin status ${BOT_NAME}`)).replace(/§./g, '')
  console.log('admin:', st.match(/(\d+)\s*格非空.*?(\d+)\s*件/) ? st.match(/(\d+)\s*格非空.*?(\d+)\s*件/)[0] : st)

  await click(bot, 51)
  await rcon(`clear ${BOT_NAME}`)
  console.log('done')
  process.exit(0)
}
scenario().catch(e => { console.error('probe error', e); process.exit(1) })
