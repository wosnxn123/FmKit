// FmYunbei settime session-rebase test.
// Proves `fyba settime <online player> <h>` writes an ABSOLUTE value: the
// un-flushed in-flight online segment must not land on top of the new value.
// Run: node settime_test.js   (server: servers/folia, port 25568)
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25576', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.2'
const BOT_NAME = process.env.BOT_NAME || 'FmTester'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')

const results = []
function record(name, ok, detail) {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' :: ' + detail : ''}`)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    let out = ''
    const send = (id, type, body) => {
      const b = Buffer.from(body, 'utf8')
      const p = Buffer.alloc(14 + b.length)
      p.writeInt32LE(10 + b.length, 0)
      p.writeInt32LE(id, 4)
      p.writeInt32LE(type, 8)
      b.copy(p, 12)
      sock.write(p)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout')) }, 15000)
    sock.on('connect', () => send(1, 3, RCON_PASS))
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4 && buf.length >= 4 + buf.readInt32LE(0)) {
        const len = buf.readInt32LE(0)
        const id = buf.readInt32LE(4)
        const type = buf.readInt32LE(8)
        const body = buf.slice(12, 4 + len - 2).toString('utf8')
        buf = buf.slice(4 + len)
        if (type === 2 && id === 1) send(2, 2, cmd)
        else if (type === 0) {
          out += body
          clearTimeout(timer)
          setTimeout(() => { sock.end(); resolve(out.replace(/\u00a7./g, '')) }, 200)
        } else if (type === 2 && id === -1) {
          clearTimeout(timer); sock.end(); reject(new Error('rcon auth failed'))
        }
      }
    })
    sock.on('error', (e) => { clearTimeout(timer); reject(e) })
  })
}

// "3h" / "45s" / "1h2m3s" / "0s" -> seconds
function parseDuration(text) {
  let secs = 0
  const re = /(\d+)([dhms])/g
  let m
  while ((m = re.exec(text)) !== null) {
    const n = parseInt(m[1], 10)
    secs += m[2] === 'd' ? n * 86400 : m[2] === 'h' ? n * 3600 : m[2] === 'm' ? n * 60 : n
  }
  return secs
}

// seconds of accumulated online time as the plugin reports it right now
async function onlineSecs() {
  const out = await rcon(`fmyunbeiadmin status ${BOT_NAME}`)
  const m = out.match(/累计在线：([0-9dhms]+)/)
  if (!m) throw new Error('cannot parse status: ' + JSON.stringify(out))
  return { secs: parseDuration(m[1]), raw: m[1], out }
}

async function main() {
  await rcon(`fmyunbeiadmin settime ${BOT_NAME} 0`)

  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.on('error', (e) => console.log('bot error:', e.message))
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('spawn timeout')), 60000)
    bot.once('spawn', () => { clearTimeout(t); res() })
  })
  console.log('bot spawned as', bot.username)
  await rcon(`effect give ${BOT_NAME} minecraft:resistance 600 4 true`)

  // 1) accumulate a session that crosses one 30s flush boundary
  await sleep(45000)
  const t1 = await onlineSecs()
  record('online time accumulates while connected', t1.secs >= 35 && t1.secs <= 65,
    `累计在线=${t1.raw} (${t1.secs}s), expected 35..65`)

  // 2) absolute write on an ONLINE player: the in-flight segment must be dropped
  await rcon(`fmyunbeiadmin settime ${BOT_NAME} 0`)
  const t2 = await onlineSecs()
  record('settime on online player is absolute (session rebased)', t2.secs <= 3,
    `累计在线=${t2.raw} (${t2.secs}s) right after settime 0, expected <=3`)

  // 3) counting resumes from the value just written, not from the stale start
  await sleep(40000)
  const t3 = await onlineSecs()
  record('counting resumes from the new value', t3.secs >= 30 && t3.secs <= 55,
    `累计在线=${t3.raw} (${t3.secs}s) 40s after settime 0, expected 30..55`)

  // 4) settime with hours>0 on an online player
  await rcon(`fmyunbeiadmin settime ${BOT_NAME} 2`)
  const t4 = await onlineSecs()
  record('settime 2h lands on 2h exactly', t4.secs >= 7200 && t4.secs <= 7205,
    `累计在线=${t4.raw} (${t4.secs}s), expected 7200..7205`)
  const unlocked = (t4.out.match(/解锁：(\d+)/) || [])[1]
  record('unlock count follows the new time', unlocked === '63',
    `解锁=${unlocked}, expected 63 = 27 initial + 0 earned (2h < 3h/slot) + 36 bonus`)

  // 5) quit flushes the session without re-adding the dropped segment
  bot.quit()
  await sleep(3000)
  const t5 = await onlineSecs()
  record('quit flush keeps the absolute value', t5.secs >= 7200 && t5.secs <= 7215,
    `累计在线=${t5.raw} (${t5.secs}s) after quit, expected 7200..7215`)

  await rcon(`fmyunbeiadmin settime ${BOT_NAME} 0`)
  const failed = results.filter((r) => !r.ok)
  console.log(`\n==== ${results.length - failed.length}/${results.length} passed ====`)
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => { console.error('FATAL', e); process.exit(2) })
