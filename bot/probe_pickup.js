// probe_pickup.js — pin down WHY items at bot+(-1,+1,+2) vanish in 0.2s
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
        const rid = buf.readInt32LE(4)
        const payload = buf.slice(12, 4 + len - 2).toString('utf8')
        buf = buf.slice(4 + len)
        if (rid === 1) { id = 2; send(2, cmd) }
        else if (rid === 2) { sock.end(); resolve(payload) }
        else if (rid === -1) { sock.end(); reject(new Error('rcon auth failed')) }
      }
    })
    sock.on('error', reject)
    setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout')) }, 5000)
  })
}

const sleep = ms => new Promise(r => setTimeout(r, ms))
const t0 = Date.now()
const ts = () => ((Date.now() - t0) / 1000).toFixed(2) + 's'

async function main() {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25568, username: BOT, version: '26.2',
    auth: 'offline', viewDistance: 8,
  })
  await new Promise((res, rej) => { bot.once('spawn', res); bot.once('error', rej) })
  await sleep(3000)
  console.log(`[${ts()}] bot pos=${bot.entity.position} eid=${bot.entity.id}`)
  console.log(`[${ts()}] players online: ${Object.keys(bot.players).join(', ')}`)

  // raw collect packet — the smoking gun for pickup
  bot._client.on('collect', (p) => {
    console.log(`[${ts()}] COLLECT packet: collected=${p.collectedEntityId} collector=${p.collectorEntityId} count=${p.pickupItemCount}`)
  })
  bot.on('playerCollect', (collector, item) => {
    console.log(`[${ts()}] playerCollect: collector=${collector.name}/${collector.id} item=${item.name}/${item.id}`)
  })

  const invCount = () => {
    const m = {}
    for (const s of bot.inventory.slots) if (s) m[s.name] = (m[s.name] || 0) + s.count
    return JSON.stringify(m)
  }
  console.log(`[${ts()}] INVENTORY before: ${invCount()}`)

  // all entities near bot
  const bp = bot.entity.position
  for (const e of Object.values(bot.entities)) {
    if (!e || e === bot.entity) continue
    if (e.position.distanceTo(bp) < 10) {
      console.log(`[${ts()}] nearby entity: id=${e.id} name=${e.name} kind=${e.kind || e.type} @${e.position}`)
    }
  }

  // distance-mapping summons (sequential): offsets relative to bot
  const offsets = [
    [-1, 1, 2],   // the loss geometry
    [1, 1, 2],    // mirror
    [2, 1, 1],
    [0, 1, 3],
    [3, 1, 0],
    [0, 1, 1.6],
  ]
  for (const [dx, dy, dz] of offsets) {
    console.log(`\n--- summon dirt at ~${dx} ~${dy} ~${dz} ---`)
    await rconCmd('kill @e[type=minecraft:item]')
    await sleep(400)
    await rconCmd(`execute as ${BOT} at @s run summon minecraft:item ~${dx} ~${dy} ~${dz} {Item:{id:"minecraft:dirt",count:1},Thrower:[I;0,0,0,1]}`)
    let eid = null, born = 0
    const iv = setInterval(() => {
      for (const e of Object.values(bot.entities)) {
        if (!e || e.name !== 'item') continue
        if (eid === null) { eid = e.id; born = Date.now(); console.log(`[${ts()}] SPAWN eid=${e.id} @${e.position}`); continue }
        if (e.id === eid && born && Date.now() - born > 400) console.log(`[${ts()}] still alive eid=${e.id} @${e.position}`)
      }
      if (eid !== null && eid !== 'gone' && !bot.entities[eid]) {
        console.log(`[${ts()}] GONE eid=${eid} (lived ${Date.now() - born}ms)`)
        eid = 'gone'
      }
    }, 50)
    await sleep(2500)
    clearInterval(iv)
  }
  console.log(`\n[${ts()}] INVENTORY after: ${invCount()}`)
  console.log(`[${ts()}] done`)
  bot.quit()
  process.exit(0)
}
main().catch(e => { console.error('FATAL', e); process.exit(1) })
