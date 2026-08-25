// Probe: after /give, which window packets arrive and how do they parse on 26.2?
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25575', 10)
const VERSION = process.env.MC_VERSION || '26.2'
require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    const send = (id, type, body) => {
      const blen = Buffer.byteLength(body)
      const b = Buffer.alloc(14 + blen)
      b.writeInt32LE(b.length - 4, 0)
      b.writeInt32LE(id, 4)
      b.writeInt32LE(type, 8)
      b.write(body, 12)
      sock.write(b)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout')) }, 6000)
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
        if (type === 2 && id === 1) send(2, 2, cmd)
        else if (type === 0 && id === 2) { clearTimeout(timer); sock.end(); resolve(body) }
        else if (type === 2 && id === -1) { clearTimeout(timer); sock.end(); reject(new Error('rcon auth failed')) }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}

const bot = mineflayer.createBot({ host: HOST, port: PORT, username: 'ProbeW', version: VERSION, auth: 'offline' })
bot.on('error', e => console.log('ERR', e.message))

// low-level packet taps
bot._client.on('window_items', p => console.log('PKT window_items windowId=' + p.windowId + ' slots=' + (p.items ? p.items.length : 'undef')))
bot._client.on('set_slot', p => console.log('PKT set_slot window=' + p.windowId + ' slot=' + p.slot + ' item=' + JSON.stringify(p.item)))

bot.on('spawn', async () => {
  console.log('spawned. slots now:', bot.inventory.slots.filter(Boolean).length)
  await new Promise(r => setTimeout(r, 800))
  console.log('give resp:', await rcon('give ProbeW minecraft:stone 1'))
  await new Promise(r => setTimeout(r, 1500))
  const s = bot.inventory.slots
  console.log('slots after give:', s.map((it, i) => it ? `${i}:id=${it.type}n=${it.name}` : null).filter(Boolean))
  // raw peek: dump hex of the next set_slot via deserializer? instead print protocol expectation
  const proto = require('minecraft-data')(VERSION).protocol
  console.log('set_slot def:', JSON.stringify(proto.play.toClient.types.packet_set_slot))
  process.exit(0)
})
setTimeout(() => { console.log('TIMEOUT'); process.exit(1) }, 30000)
