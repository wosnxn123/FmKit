// TEMP verify (normal mode): every take must hand the client a legal stack
// (amount <= the item's own max) and every path must conserve items.
// Counts are read after closing the window, when the server resends the whole
// player inventory — the in-GUI mirror is not authoritative.
const HOST = '127.0.0.1'
const PORT = 25568
const RCON_PORT = 25576
const RCON_PASS = 'fmtest123'
const VERSION = '26.2'
const TAKE_ALL = 51   // 一键取回（漏斗）
const DEPOSIT = 52    // 一键存入（绿色潜影盒）
const BOT = 'FmProbe'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')
const sleep = ms => new Promise(r => setTimeout(r, ms))

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let id = 0
    const pkt = (type, body) => {
      const b = Buffer.from(body, 'utf8')
      const buf = Buffer.alloc(14 + b.length)
      buf.writeInt32LE(10 + b.length, 0); buf.writeInt32LE(++id, 4); buf.writeInt32LE(type, 8)
      b.copy(buf, 12); return buf
    }
    let out = ''
    sock.on('connect', () => { sock.write(pkt(3, RCON_PASS)); setTimeout(() => sock.write(pkt(2, cmd)), 120) })
    let idle = null
    sock.on('data', d => {
      let o = 0
      while (o + 4 <= d.length) {
        const len = d.readInt32LE(o); if (o + 4 + len > d.length) break
        out += d.toString('utf8', o + 12, o + 4 + len - 2); o += 4 + len
      }
      // 多行响应会被拆成多个 TCP 段：按"最后一次收数据后静默"判定结束，
      // 否则尾行会被截掉，status 解析出 -1
      clearTimeout(idle)
      idle = setTimeout(() => { sock.end(); resolve(out.replace(/\u00a7./g, '')) }, 350)
    })
    sock.on('error', reject)
  })
}
const cloud = async () => {
  for (let a = 0; a < 3; a++) {
    const m = (await rcon(`fmyunbeiadmin status ${BOT}`)).match(/共\s*([\d,]+)\s*件/)
    if (m) return parseInt(m[1].replace(/,/g, ''), 10)
    await sleep(400)
  }
  return -1
}

let fails = 0
const check = (label, got, want) => {
  const ok = got === want
  if (!ok) fails++
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}: got ${got}, want ${want}`)
}

async function main() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: VERSION, auth: 'offline' })
  bot.on('error', e => console.log('bot error:', e.message))
  const wire = []
  let ItemCls = null
  const itemCls = () => ItemCls || (ItemCls = require('prismarine-item')(bot.registry))
  bot._client.on('set_cursor_item', p => {
    wire.push(p.contents && p.contents.itemCount)
    const w = bot.currentWindow || bot.inventory
    if (w) w.selectedItem = itemCls().fromNotch(p.contents)
  })
  await new Promise(r => bot.once('spawn', r))
  await sleep(2500)
  await rcon(`effect give ${BOT} minecraft:resistance 900 4 true`)
  console.log('reload:', (await rcon('fmyunbeiadmin reload')).trim())

  let win = null
  const open = async () => {
    win = null
    for (let a = 1; a <= 3 && !win; a++) {
      const p = new Promise(r => { bot.once('windowOpen', w => r(w)); setTimeout(() => r(null), 6000) })
      await bot.chat('/fmyunbei')
      win = await p
    }
    if (!win) { console.log('FATAL no window'); process.exit(2) }
    await sleep(700)
  }
  const w = () => bot.currentWindow || win
  const click = async (slot, button = 0, mode = 0) => {
    await bot.clickWindow(slot, button, mode).catch(e => console.log('  click err:', e.message))
    await sleep(700)
  }
  // close -> server resends the player inventory -> mirror is trustworthy
  const invAfterClose = async name => {
    try { bot.closeWindow(bot.currentWindow) } catch { }
    await sleep(1100)
    return bot.inventory.items().filter(i => i.name === name).reduce((a, i) => a + i.count, 0)
  }
  const find = name => {
    for (let i = 9; i < 45; i++) {
      const it = w().slots[i]
      if (it && it.name === name) return i
    }
    return -1
  }

  // 云端清空：一键取回 + 清背包，直到 status 归零
  const drain = async () => {
    for (let i = 0; i < 8 && await cloud() > 0; i++) {
      if (!bot.currentWindow) await open()
      await click(TAKE_ALL)
      await sleep(900)
      await rcon(`clear ${BOT}`)
      await sleep(300)
    }
    await rcon(`clear ${BOT}`)
    await sleep(400)
  }

  await open()
  await drain()
  check('cloud emptied', await cloud(), 0)

  // ---------- unstackable: 5 enchanted books, vanilla max 1
  await rcon(`give ${BOT} minecraft:enchanted_book 5`)
  await sleep(800)
  await click(DEPOSIT)
  await sleep(1300)
  check('deposit 5 books', await cloud(), 5)

  const bs = find('enchanted_book')
  wire.length = 0
  await click(bs)
  check('book cursor wire count (max 1)', wire[wire.length - 1], 1)
  check('book cursor amount', w().selectedItem ? w().selectedItem.count : 0, 1)
  check('cloud after cursor take', await cloud(), 4)
  await click(bs)
  check('cloud after cursor returned', await cloud(), 5)
  check('books leaked to inventory', await invAfterClose('enchanted_book'), 0)

  // 云端格子的显示数量必须被压到原版上限（附魔书=1），真实持有量只在 lore /
  // 插件模型里。所以 Shift 取出的量不能拿显示值去断言：tier 0 → mult 2 →
  // 单格上限 1×2=2，5 本书被拆成 2+2+1 三格，Shift 取走第一格的 2 本。
  await open()
  const bs2 = find('enchanted_book')
  check('book slot display clamped to vanilla max', w().slots[bs2].count <= 1, true)
  await click(bs2, 0, 1)   // Shift 取出
  await sleep(1200)
  const cloudAfterShift = await cloud()
  const invAfterShift = await invAfterClose('enchanted_book')
  check('shift-take moved one full cloud slot', invAfterShift, 2)
  check('shift-take conserves books', cloudAfterShift + invAfterShift, 5)

  // ---------- stackable: 100 stone, vanilla max 64
  await open()
  await drain()
  check('cloud emptied before stone phase', await cloud(), 0)
  await rcon(`give ${BOT} minecraft:stone 100`)
  await sleep(800)
  await open()
  await click(DEPOSIT)
  await sleep(1300)
  check('deposit 100 stone', await cloud(), 100)

  const ss = find('stone')
  wire.length = 0
  await click(ss)
  check('stone cursor wire count (max 64)', wire[wire.length - 1], 64)
  check('stone cursor amount', w().selectedItem ? w().selectedItem.count : 0, 64)
  check('cloud after cursor take', await cloud(), 36)
  await click(ss)
  check('cloud after cursor returned', await cloud(), 100)
  check('stone leaked to inventory', await invAfterClose('stone'), 0)

  await open()
  await click(TAKE_ALL)                              // 一键取回
  await sleep(1500)
  check('cloud after take-all', await cloud(), 0)
  check('stone in inventory after take-all', await invAfterClose('stone'), 100)

  console.log(fails === 0 ? 'ALL PASS' : `${fails} FAILED`)
  bot.end()
  process.exit(fails === 0 ? 0 : 1)
}
main().catch(e => { console.error('FATAL', e); process.exit(2) })
