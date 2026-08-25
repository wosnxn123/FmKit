// One-shot: empirically map the server's TRUE network IDs for items and key entity
// types in versions whose static registries minecraft-data doesn't have (26.x does
// not send item/entity_type registries over the wire). Works by /give-ing every
// known item name to a bot and reading the raw item id from the slot update, and
// by observing spawn_entity type ids. Writes bot/static_ids_<ver>.json consumed
// by mc_shim.js. Run: node map_items.js   (env: PORT RCON_PORT MC_VERSION)
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25576', 10)
const RCON_PASS = 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.2'
const OUT = __dirname + `/static_ids_${VERSION.replace(/[^0-9a-z_.-]/gi, '_')}.json`

require('./mc_shim')(VERSION)
const mcdata = require('minecraft-data')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')
const fs = require('fs')

const BOT = 'MapBot'
const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: VERSION, auth: 'offline' })

function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }
function waitFor(cond, timeoutMs = 5000, poll = 50) {
  const start = Date.now()
  return new Promise(resolve => {
    const iv = setInterval(() => {
      let v = null
      try { v = cond() } catch { v = null }
      if (v) { clearInterval(iv); resolve(v) }
      else if (Date.now() - start > timeoutMs) { clearInterval(iv); resolve(null) }
    }, poll)
  })
}

// ---- persistent RCON client: one connection for all commands (avoids
// thousands of TIME_WAIT sockets -> EADDRINUSE on long runs) ----
const rconState = { send: null, authed: false, nextId: 1, pending: new Map(), buf: Buffer.alloc(0) }
function rconConnect() {
  return new Promise((resolve, reject) => {
    let settled = false
    const ct = setTimeout(() => { if (!settled) { settled = true; reject(new Error('rcon connect timeout')) } }, 8000)
    const ok = () => { if (!settled) { settled = true; clearTimeout(ct); resolve() } }
    const fail = e => { if (!settled) { settled = true; clearTimeout(ct); reject(e) } }
    const sock = net.connect(RCON_PORT, HOST)
    const send = (id, type, body) => {
      const blen = Buffer.byteLength(body)
      const b = Buffer.alloc(14 + blen)
      b.writeInt32LE(b.length - 4, 0)
      b.writeInt32LE(id, 4)
      b.writeInt32LE(type, 8)
      b.write(body, 12)
      sock.write(b)
    }
    rconState.send = send
    sock.on('connect', () => send(0, 3, RCON_PASS))
    sock.on('data', d => {
      rconState.buf = Buffer.concat([rconState.buf, d])
      while (rconState.buf.length >= 4) {
        const len = rconState.buf.readInt32LE(0)
        if (rconState.buf.length < 4 + len) break
        const id = rconState.buf.readInt32LE(4)
        const type = rconState.buf.readInt32LE(8)
        const body = rconState.buf.slice(10, 4 + len - 2).toString()
        rconState.buf = rconState.buf.slice(4 + len)
        if (type === 2 && id === 0) { rconState.authed = true; ok() }
        else if (type === 2 && id === -1) {
          if (!rconState.authed) fail(new Error('rcon auth failed'))
          else console.log('rcon: spurious auth-fail packet ignored')
        }
        else if (type === 0) {
          const p = rconState.pending.get(id)
          if (p) { rconState.pending.delete(id); clearTimeout(p.timer); p.resolve(body) }
        }
      }
    })
    sock.on('error', e => {
      fail(e)
      for (const p of rconState.pending.values()) { clearTimeout(p.timer); p.reject(e) }
      rconState.pending.clear()
    })
  })
}
function rcon(cmd) {
  return new Promise((resolve, reject) => {
    if (!rconState.authed) return reject(new Error('rcon not connected'))
    const id = rconState.nextId++
    const timer = setTimeout(() => {
      if (rconState.pending.has(id)) { rconState.pending.delete(id); reject(new Error('rcon timeout: ' + cmd)) }
    }, 5000)
    rconState.pending.set(id, { resolve, reject, timer })
    rconState.send(id, 2, cmd)
  })
}

const out = { items: {}, entities: {} }
try {
  const prev = JSON.parse(fs.readFileSync(OUT, 'utf8'))
  Object.assign(out.items, prev.items || {})
  Object.assign(out.entities, prev.entities || {})
  console.log('resuming with', Object.keys(out.items).length, 'captured items')
} catch { }
let lastType = null
let spawnDebug = false
bot._client.on('spawn_entity', pkt => {
  lastType = pkt.type
  if (spawnDebug) {
    const p = { ...pkt }
    if (p.objectUUID && p.objectUUID.data) p.objectUUID = p.objectUUID.toString('hex')
    console.log('SPAWN', JSON.stringify(p))
  }
})

bot.on('death', () => { try { bot._client.write('client_command', { actionId: 0 }) } catch { } })
bot.on('error', e => console.log('BOT ERR', e.message))
bot.on('kicked', r => { console.log('KICKED', typeof r === 'string' ? r : JSON.stringify(r)); process.exit(1) })
const safety = setTimeout(() => { console.error('no spawn within 60s'); process.exit(1) }, 60000)
// joined while still dead (persisted death + respawn screen): force respawn
setTimeout(() => { if (bot.health === 0) { try { bot._client.write('client_command', { actionId: 0 }) } catch { } } }, 3000)
let running = false
bot.on('spawn', () => {
  clearTimeout(safety)
  if (running) return
  running = true
  main().catch(e => { console.error('MAIN ERR', e); process.exit(1) })
})

async function invEmpty() {
  return waitFor(() => bot.inventory.slots.every(s => s == null), 3000)
}

// FmTerm shows a 54-slot terms GUI on join until accepted; while undecided the
// player is frozen. Accept button = lime_concrete at config slot 51. Item NAMES
// may not resolve before remapping, so fall back to the raw slot.
async function acceptTerms() {
  for (let attempt = 0; attempt < 5; attempt++) {
    await sleep(800)
    const w = bot.currentWindow
    if (!w) return
    let li = w.slots.findIndex(s => s && s.name === 'lime_concrete')
    if (li < 0 || li >= 54) li = 51
    try { await bot.clickWindow(li, 0, 0) } catch (e) { console.log('accept click err', e.message) }
    await sleep(800)
  }
}

async function mapItem(name) {
  await rcon(`clear ${BOT}`)
  await invEmpty()
  const resp = await rcon(`give ${BOT} minecraft:${name} 1`)
  const slot = await waitFor(() => {
    const s = bot.inventory.slots.find(x => x != null)
    if (s) return s
    const w = bot.currentWindow
    if (w) for (let i = w.inventoryStart ?? 54; i < w.slots.length; i++) if (w.slots[i]) return w.slots[i]
    return null
  }, 2500)
  if (!slot) {
    if (!/Gave|Given/i.test(resp)) console.log(`  give said: ${resp}`)
    return false
  }
  out.items[name] = slot.type
  return true
}

async function main() {
  await rconConnect()
  await sleep(1000)
  await acceptTerms()
  const names = mcdata.itemsArray.filter(i => i && i.name && !i.name.startsWith('unknown_')).map(i => i.name)
  console.log('mapping', names.length, 'items on', VERSION)
  let done = 0, miss = 0
  const started = Date.now()
  for (const name of names) {
    if (out.items[name] != null) { done++; continue }
    try {
      if (!await mapItem(name)) { miss++; console.log('MISS', name) }
    } catch (e) {
      miss++; console.log('ERR', name, e.message)
      await sleep(500)
    }
    done++
    if (done % 150 === 0) {
      fs.writeFileSync(OUT, JSON.stringify(out, null, 1))
      const rate = done / ((Date.now() - started) / 1000)
      console.log(`progress ${done}/${names.length} miss=${miss} ${rate.toFixed(1)}/s eta=${Math.round((names.length - done) / rate)}s`)
    }
  }
  // pass 2: names in the 26.1 donor registry never captured. Give-by-name
  // probes names, not ids — holes in itemsArray (ids whose donor item was
  // captured at a different id) were never probed, so give every uncaptured
  // donor name and let the server report where it lives now.
  const rawData = require('minecraft-data/data.js')
  const donorNames = Object.values(rawData.pc['26.1'].items).filter(i => i && i.name).map(i => i.name)
  let pass2 = 0, miss2 = 0
  for (const name of donorNames) {
    if (out.items[name] != null) continue
    pass2++
    try {
      if (!await mapItem(name)) { miss2++; console.log('MISS2', name) }
    } catch (e) {
      miss2++; console.log('ERR2', name, e.message)
      await sleep(500)
    }
  }
  console.log(`pass2 probed ${pass2} names, miss=${miss2}`)
  spawnDebug = true
  // entity type: item — toss a stone and watch spawn_entity
  await rcon(`clear ${BOT}`)
  await invEmpty()
  await rcon(`give ${BOT} minecraft:stone 1`)
  const stone = await waitFor(() => bot.inventory.slots.find(s => s != null), 3000)
  if (stone) {
    lastType = null
    await bot.tossStack(stone)
    const t = await waitFor(() => lastType, 3000)
    if (t != null) out.entities.item = t
    await rcon('kill @e[type=minecraft:item]')
  }
  // entity type: fishing_bobber — cast a rod and watch spawn_entity
  await rcon(`clear ${BOT}`)
  await invEmpty()
  await rcon(`give ${BOT} minecraft:fishing_rod 1`)
  const rod = await waitFor(() => bot.inventory.slots.find(s => s != null), 3000)
  if (rod) {
    await bot.equip(rod, 'hand')
    lastType = null
    await bot.activateItem()
    const ft = await waitFor(() => lastType, 3000)
    if (ft != null) out.entities.fishing_bobber = ft
    await bot.deactivateItem()
    await rcon(`clear ${BOT}`)
  }
  // entity type: player — second bot joins, we observe its spawn_entity
  const tmp = mineflayer.createBot({ host: HOST, port: PORT, username: 'TmpMap', version: VERSION, auth: 'offline' })
  tmp.on('error', () => {})
  lastType = null
  const pt = await waitFor(() => lastType, 15000)
  if (pt != null) out.entities.player = pt
  tmp.quit()
  fs.writeFileSync(OUT, JSON.stringify(out, null, 1))
  console.log('DONE items=' + Object.keys(out.items).length + '/' + names.length +
    ' entities=' + JSON.stringify(out.entities))
  process.exit(0)
}
