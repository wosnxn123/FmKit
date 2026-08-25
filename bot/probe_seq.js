// Probe: does sweep deposit order match summon order? (I16 anomaly on 26.2)
// usage: node probe_seq.js [rconPort]  (25576=26.2, 25575=26.1)
const net = require('net')
const fs = require('fs')
const path = require('path')
const HOST = '127.0.0.1', PORT = parseInt(process.argv[2] || '25576', 10), PASS = 'fmtest123'
const YAML = path.join(__dirname, '..', 'servers', PORT === 25576 ? 'folia' : 'folia261', 'plugins', 'FmKit', 'bins', 'public.yml')

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(PORT, HOST)
    let buf = Buffer.alloc(0)
    const send = (id, type, body) => {
      const b = Buffer.alloc(14 + Buffer.byteLength(body))
      b.writeInt32LE(b.length - 4, 0); b.writeInt32LE(id, 4); b.writeInt32LE(type, 8); b.write(body, 12)
      sock.write(b)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('RCON_TIMEOUT ' + cmd)) }, 15000)
    sock.on('connect', () => send(1, 3, PASS))
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
        else if (type === 2 && id === -1) { clearTimeout(timer); sock.end(); reject(new Error('RCON_AUTH_FAILED')) }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}

const sleep = ms => new Promise(r => setTimeout(r, ms))
const MATS = ['cobblestone', 'granite', 'diorite', 'andesite']

function ymlOrder() {
  const txt = fs.readFileSync(YAML, 'utf8')
  // entry blocks: count occurrences of material keys in file order
  const out = []
  const re = /==: org\.bukkit\.inventory\.ItemStack\n\s*type: (\w+)/g
  let m
  while ((m = re.exec(txt)) !== null) out.push(m[1])
  return out
}

async function main() {
  await rcon('forceload add 0 0')
  for (let trial = 1; trial <= 5; trial++) {
    await rcon('fmkitadmin clearpublic')
    await rcon('fmkitadmin clearpublic')
    await rcon('kill @e[type=item]')
    await sleep(500)
    for (let i = 0; i < MATS.length; i++) {
      const r = await rcon(`summon minecraft:item 0 -58 ${5 + i} {Item:{id:"minecraft:${MATS[i]}",count:1}}`)
      if (trial === 1) console.log(`  summon[${i}] ${MATS[i]}: ${r}`)
      await sleep(200)
    }
    await sleep(300)
    const sw = await rcon('fmkitadmin sweep now')
    if (trial === 1) console.log(`  sweep now: ${sw}`)
    await sleep(2500)
    console.log(`TRIAL ${trial}: summon=${MATS.join(',')} bin=${ymlOrder().join(',')}`)
  }
  await rcon('forceload remove 0 0')
}
main().catch(e => { console.error('PROBE_ERROR', e.message); process.exit(1) })
