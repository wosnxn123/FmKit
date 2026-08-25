// Smoke test: join the live 26.2 server through mc_shim, reach spawn, survive
// entity/explosion traffic, and report any parse/registry errors. Exit 0 = clean.
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const VERSION = process.env.MC_VERSION || '26.2'
const DWELL = parseInt(process.env.DWELL || '20000', 10)

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')

const errs = []
const bot = mineflayer.createBot({ host: HOST, port: PORT, username: 'Smoke26', version: VERSION, auth: 'offline' })
bot.on('error', e => { errs.push('bot.error: ' + e.message); console.log('ERR', e.message) })
bot.on('kicked', r => { errs.push('kicked: ' + (typeof r === 'string' ? r : JSON.stringify(r))); console.log('KICKED', errs[errs.length - 1]) })
bot._client.on('error', e => { errs.push('client.error: ' + e.message); console.log('CLIENT ERR', e.message) })

let spawns = 0
bot._client.on('spawn_entity', () => { spawns++ })
let explosions = 0
bot._client.on('explosion', () => { explosions++ })

const t0 = Date.now()
bot.on('spawn', async () => {
  console.log(`SPAWN ok after ${Date.now() - t0}ms at ${bot.entity.position} health=${bot.health} dim=${bot.game.dimension}`)
  // FmTerm terms GUI may be open (player frozen until accepted): accept via slot 51
  try {
    const win = bot.currentWindow
    if (win && win.slots[51]) {
      await bot.clickWindow(51, 0, 0)
      console.log('clicked terms slot 51 (' + (win.slots[51].name || '?') + ')')
    }
  } catch (e) { console.log('terms accept skipped: ' + e.message) }
})

setTimeout(() => {
  console.log(`DWELL done: entity_spawns=${spawns} explosions=${explosions} errors=${errs.length}`)
  for (const e of errs) console.log('  - ' + e)
  bot.quit()
  setTimeout(() => process.exit(errs.length ? 1 : 0), 1500)
}, DWELL)
