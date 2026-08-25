// Trigger a series of explosions near PLAYER over RCON so the capture/smoke
// script records explosion packets of varying size.
const net = require('net')
const RCON_PORT = parseInt(process.env.RCON_PORT || '25576', 10)
const RCON_PASS = 'fmtest123'
const PLAYER = process.env.PLAYER || 'Smoke26'

function rcon (cmd) {
  return new Promise((res, rej) => {
    const s = net.connect(RCON_PORT, '127.0.0.1')
    let b = Buffer.alloc(0)
    const w = (id, t, body) => {
      const n = Buffer.alloc(14 + Buffer.byteLength(body))
      n.writeInt32LE(n.length - 4, 0); n.writeInt32LE(id, 4); n.writeInt32LE(t, 8); n.write(body, 12); s.write(n)
    }
    s.on('connect', () => w(1, 3, RCON_PASS))
    s.on('data', d => {
      b = Buffer.concat([b, d])
      while (b.length >= 4) {
        const l = b.readInt32LE(0)
        if (b.length < 4 + l) break
        const id = b.readInt32LE(4), t = b.readInt32LE(8), body = b.slice(10, 2 + l).toString()
        b = b.slice(4 + l)
        if (t === 2 && id === 1) w(2, 2, cmd)
        else if (t === 0 && id === 2) { s.end(); res(body) }
        else if (t === 2 && id === -1) { s.end(); rej(new Error('auth fail')) }
      }
    })
    s.on('error', rej)
    setTimeout(() => { s.destroy(); rej(new Error('timeout')) }, 5000)
  })
}

const sleep = ms => new Promise(r => setTimeout(r, ms))

;(async () => {
  await sleep(2000)
  const cmds = []
  // several ignited creepers (small->big) and TNT, staggered so we capture many
  for (let i = 0; i < 4; i++) {
    cmds.push(`/execute as ${PLAYER} at @s run summon creeper ~ ~ ~ {ignited:1b}`)
    cmds.push(`/execute as ${PLAYER} at @s run summon tnt ~ ~1 ~ {fuse:15}`)
  }
  cmds.push(`/execute as ${PLAYER} at @s run summon end_crystal ~ ~2 ~`)
  for (const c of cmds) {
    try { console.log(await rcon(c)) } catch (e) { console.log('ERR', e.message) }
    await sleep(700)
  }
  // explode the end crystal for a big explosion
  await sleep(500)
  try { console.log(await rcon(`/execute as ${PLAYER} at @s positioned ~ ~2 ~ run kill @e[type=end_crystal,distance=..5]`)) } catch (e) {}
})()
