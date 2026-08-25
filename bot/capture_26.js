// Capture raw bytes of packets the 26.1-donor definitions fail to parse (or
// under-parse) on the live 26.2 server. Writes captures_26.2.json for offline
// layout derivation. Trigger explosions from a second terminal via rcon while
// this dwells.
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const VERSION = process.env.MC_VERSION || '26.2'
const DWELL = parseInt(process.env.DWELL || '25000', 10)
const OUT = __dirname + '/captures_' + VERSION + '.json'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const { FullPacketParser } = require('protodef')
const fs = require('fs')

const caps = []
const origParse = FullPacketParser.prototype.parsePacketBuffer
FullPacketParser.prototype.parsePacketBuffer = function (buffer) {
  try {
    const p = origParse.call(this, buffer)
    if (p.metadata.size !== buffer.length) {
      caps.push({ kind: 'trailing', unread: buffer.length - p.metadata.size, hex: buffer.toString('hex'), data: p.data })
    }
    return p
  } catch (e) {
    if (e.partialReadError) caps.push({ kind: 'fail', hex: buffer.toString('hex'), err: String(e && e.message) })
    throw e
  }
}

const bot = mineflayer.createBot({ host: HOST, port: PORT, username: 'Cap26', version: VERSION, auth: 'offline' })
bot.on('error', e => console.log('ERR', e.message))
bot.on('kicked', r => console.log('KICKED', typeof r === 'string' ? r : JSON.stringify(r)))
bot.on('spawn', () => console.log('SPAWN at', bot.entity.position.toString()))

setTimeout(() => {
  fs.writeFileSync(OUT, JSON.stringify(caps, null, 1))
  console.log(`captured ${caps.length} problem packets -> ${OUT}`)
  for (const c of caps) console.log(`  ${c.kind} len=${c.hex.length / 2}${c.unread ? ' unread=' + c.unread : ''} head=${c.hex.slice(0, 24)}`)
  bot.quit()
  setTimeout(() => process.exit(0), 1000)
}, DWELL)
