// probe_loss.js — why did the dirt at dx=-1,dz=+2 (x≈-14.5,z≈5.5) vanish 0.3s after landing?
// 1) dump bot inventory (pickup check)
// 2) terrain scan of columns around the loss spot down to y=-64
// 3) summon 1 dirt at the exact spot, track at 100ms, then re-check inventory
require('./mc_shim')('26.2')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const BOT = 'FmTester'
const RCON_PORT = 25576, RCON_PASS = 'fmtest123'

function rconCmd(cmd) {
  return new Promise((resolve, reject) => {
    const net = require('net')
    const sock = net.connect(RCON_PORT, '127.0.0.1')
    let buf = Buffer.alloc(0), id = 2
    const send = (type, body) => {
      const b = Buffer.from(body, 'utf8')
      const p = Buffer.alloc(14 + b.length)
      p.writeInt32LE(10 + b.length, 0); p.writeInt32LE(id, 4); p.writeInt32LE(type, 8)
      b.copy(p, 12); p.writeInt16LE(0, 12 + b.length)
      sock.write(p)
    }
    sock.on('connect', () => { id = 1; send(3, RCON_PASS) })
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 12) {
        const len = buf.readInt32LE(0)
        if (buf.length < 4 + len) break
        const rid = buf.readInt32LE(4), type = buf.readInt32LE(8)
        const payload = buf.slice(12, 4 + len - 2).toString('utf8')
        buf = buf.slice(4 + len)
        if (rid === 1) { id = 2; send(2, cmd) }
        else if (rid === 2) { sock.end(); resolve(payload) }
        else if (type === 3 && rid === -1) { sock.end(); reject(new Error('rcon auth failed')) }
      }
    })
    sock.on('error', reject)
    setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout')) }, 5000)
  })
}

function itemName(e) {
  const s = e.objectData && e.objectData.stack
  return s ? `${s.name}x${s.count}` : '?'
}

async function main() {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25568, username: BOT, version: '26.2',
    auth: 'offline', hideErrors: false, viewDistance: 8,
  })
  const t0 = Date.now()
  const ts = () => ((Date.now() - t0) / 1000).toFixed(1) + 's'
  await new Promise((res, rej) => { bot.once('spawn', res); bot.once('error', rej) })
  await new Promise(r => setTimeout(r, 3000))
  console.log('bot pos:', bot.entity.position, 'dim=', bot.game.dimension)

  // ---- 1) inventory ----
  const inv = bot.inventory.slots.filter(Boolean).map(s => `${s.name}x${s.count}`).join(' ') || '(empty)'
  console.log('INVENTORY:', inv)

  // ---- 2) terrain scan: columns x=-16..-13, z=4..7, from y=-60 down to -64 ----
  console.log('--- terrain scan (loss spot ≈ x=-14.5,z=5.5; bot hole floor y=-64..-63) ---')
  for (let x = -16; x <= -12; x++) {
    for (let z = 3; z <= 8; z++) {
      const col = []
      for (let y = -60; y >= -64; y--) {
        const b = bot.blockAt(new Vec3(x + 0.5, y, z + 0.5))
        const n = b ? b.name : 'null'
        if (n !== 'air') col.push(`y${y}:${n}`)
      }
      console.log(`x${x} z${z}: ${col.join(' ') || 'AIR all the way down'}`)
    }
  }

  // ---- 3) single-item drop test at the exact loss spot ----
  const uuidInts = '0,0,0,1'
  console.log('--- summon 1 owned dirt at ~-1 ~1 ~2, tracking @100ms ---')
  await rconCmd(`execute as ${BOT} at @s run summon minecraft:item ~-1 ~1 ~2 {Item:{id:"minecraft:dirt",count:1},Thrower:[I;${uuidInts}]}`)
  let tracked = null, lastPos = null, lastVel = null
  const iv = setInterval(() => {
    for (const e of Object.values(bot.entities)) {
      if (!e || e.name !== 'item') continue
      if (!tracked) { tracked = e; console.log(`[${ts()}] NEW eid=${e.id} @${e.position} vel=${e.velocity} item=${itemName(e)}`); continue }
      if (e.id === tracked.id) {
        lastPos = e.position.clone(); lastVel = e.velocity && e.velocity.clone()
      }
    }
    if (tracked && !bot.entities[tracked.id]) {
      console.log(`[${ts()}] GONE eid=${tracked.id} last@${lastPos} lastVel=${lastVel}`)
      tracked = 'gone'
    }
  }, 100)
  await new Promise(r => setTimeout(r, 6000))
  clearInterval(iv)
  console.log('INVENTORY after:', bot.inventory.slots.filter(Boolean).map(s => `${s.name}x${s.count}`).join(' ') || '(empty)')
  // check public+private for the dirt
  async function readBin(label, ix) {
    bot.chat(`/fmkit ${label}`)
    await new Promise(r => setTimeout(r, 1500))
    const items = []
    for (let s = 9; s < 45; s++) {
      const it = bot.inventory.slots[s]
      if (it && it.name !== 'gray_stained_glass_pane') items.push(`${s}:${it.name}x${it.count}`)
    }
    console.log(`${label} bin:`, items.join(' ') || '(empty)')
    bot.chat('/fmkit close')
    await new Promise(r => setTimeout(r, 800))
  }
  // note: dirt is owned -> private bin of owner (bot itself)
  await readBin('private', 0)
  console.log('done')
  bot.quit()
  process.exit(0)
}
main().catch(e => { console.error('FATAL', e); process.exit(1) })
