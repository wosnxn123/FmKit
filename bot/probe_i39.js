// probe_i39.js — reproduce missing-summoned-items with full observation:
// 1) map floor blocks under the summon area (summonSame offsets x=-5..+4,z=+2..+9; evict line z=+5..+8)
// 2) summon 5 dirt (summonSame) + 1 andesite at evict offset, track ground item entities over time
// 3) capture sweep broadcasts with collected counts
// 4) read private+public bins at the end
process.env.MC_VERSION = process.env.MC_VERSION || '26.2'
require('./mc_shim')(process.env.MC_VERSION)
const mineflayer = require('mineflayer')
const net = require('net')
const crypto = require('crypto')
const HOST = '127.0.0.1'
const PORT = +(process.env.PORT || 25568)
const RCON_PORT = +(process.env.RCON_PORT || 25576)
const BOT_NAME = 'FmTester'
const sleep = ms => new Promise(r => setTimeout(r, ms))

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout: ' + cmd)) }, 8000)
    const send = (id, type, body) => {
      const blen = Buffer.byteLength(body)
      const b = Buffer.alloc(14 + blen)
      b.writeInt32LE(b.length - 4, 0)
      b.writeInt32LE(id, 4)
      b.writeInt32LE(type, 8)
      b.write(body, 12)
      sock.write(b)
    }
    sock.on('connect', () => send(1, 3, 'fmtest123'))
    sock.on('data', d => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4) {
        const len = buf.readInt32LE(0)
        if (buf.length < 4 + len) break
        const id = buf.readInt32LE(4)
        const type = buf.readInt32LE(8)
        const body = buf.slice(10, 4 + len - 2).toString()
        buf = buf.slice(4 + len)
        if (type === 2 && id === 1) { send(2, 2, cmd) }
        else if (type === 0 && id === 2) { clearTimeout(timer); sock.end(); resolve(body) }
        else if (type === 2 && id === -1) { clearTimeout(timer); sock.end(); reject(new Error('rcon auth failed')) }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}

const uuidInts = (() => {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + BOT_NAME).digest()
  h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const hex = h.toString('hex')
  const f = s => parseInt(s, 16) | 0
  return [f(hex.slice(0, 8)), f(hex.slice(8, 16)), f(hex.slice(16, 24)), f(hex.slice(24, 32))].join(',')
})()

let t0 = Date.now()
const ts = () => ((Date.now() - t0) / 1000).toFixed(1) + 's'
const ground = bot => Object.values(bot.entities).filter(e => e.name === 'item')
  .map(e => `${e.objectData?.stack?.name || '?'}@(${e.position.x.toFixed(1)},${e.position.y.toFixed(1)},${e.position.z.toFixed(1)})`)

async function main() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: process.env.MC_VERSION, auth: 'offline' })
  bot.messages = []
  bot.on('messagestr', m => bot.messages.push(`[${ts()}] ${m}`))
  bot.on('end', r => console.log('bot end:', r))
  bot.on('error', e => console.log('bot error:', e.message))
  await new Promise(res => bot.once('spawn', res))
  t0 = Date.now()
  const S = bot.entity.position
  console.log(`bot pos: (${S.x.toFixed(1)}, ${S.y.toFixed(1)}, ${S.z.toFixed(1)}) dim=${bot.game.dimension}`)
  await sleep(1000)

  // 1) terrain map under summon area: dx=-6..+5, dz=+1..+10, search down for first solid, note fluids
  console.log('--- terrain (offset -> block@dy, dy relative to bot feet; "fluid" marks water/lava column) ---')
  for (let dx = -6; dx <= 5; dx++) {
    const row = []
    for (let dz = 1; dz <= 10; dz++) {
      let found = 'VOID'
      let fluid = []
      for (let dy = 2; dy >= -12; dy--) {
        const b = bot.blockAt(S.offset(dx, dy, dz))
        if (!b) { found = 'UNLOADED'; break }
        if (b.name === 'water' || b.name === 'lava') { fluid.push(b.name + '@' + dy); continue }
        if (b.name !== 'air' && b.name !== 'cave_air') { found = `${b.name}@${dy}`; break }
      }
      row.push(fluid.length ? `FLUID(${fluid.join(',')})` : found)
    }
    console.log(`dx=${dx >= 0 ? '+' : ''}${dx}: ${row.join(' | ')}`)
  }

  // 2) clear bins
  bot.messages.length = 0
  console.log('clear:', String(await rcon(`fmkitadmin clear ${BOT_NAME}`)).slice(0, 80) || '(via chat)')
  await bot.chat(`/fmkitadmin clear ${BOT_NAME}`), await sleep(800)

  // 3) summon 5 dirt (summonSame formula) + 1 andesite at evict offset z+8
  for (let i = 0; i < 5; i++) {
    await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~${(i % 10) - 5} ~1 ~${Math.floor(i / 10) + 2} {Item:{id:"minecraft:dirt",count:1},Thrower:[I;${uuidInts}]}`)
  }
  await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~8 {Item:{id:"minecraft:andesite",count:1},Thrower:[I;${uuidInts}]}`)
  console.log(`[${ts()}] summoned 5 dirt + 1 andesite`)

  // 4) track ground items + entity positions every 1.5s for 50s
  for (let t = 0; t < 50; t += 1.5) {
    await sleep(1500)
    const g = ground(bot)
    console.log(`[${ts()}] ground=${g.length}: ${g.join(' ').slice(0, 300)}`)
  }
  console.log('--- messages ---')
  for (const m of bot.messages) console.log(m)

  // 5) read bins via GUI
  const readBin = async cmd => {
    bot.chat(cmd)
    const win = await new Promise(res => {
      const st = Date.now()
      const iv = setInterval(() => { if (bot.currentWindow) { clearInterval(iv); res(bot.currentWindow) } else if (Date.now() - st > 8000) { clearInterval(iv); res(null) } }, 100)
    })
    if (!win) return 'no window'
    await sleep(400)
    const items = []
    for (let s = 0; s < win.slots.length; s++) {
      const it = win.slots[s]
      if (it && it.name !== 'gray_stained_glass_pane' && it.name !== 'black_stained_glass_pane') items.push(`${s}:${it.name}x${it.count}`)
    }
    try { bot.closeWindow(win) } catch { }
    return items.join(' ')
  }
  console.log('private bin:', await readBin('/fmkit private'))
  console.log('public bin:', await readBin('/fmkit public'))
  console.log('bot inventory:', bot.inventory.items().map(i => `${i.name}x${i.count}`).join(' ') || '(empty)')
  bot.quit()
  process.exit(0)
}
main().catch(e => { console.error('FATAL', e); process.exit(1) })
