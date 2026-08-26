// Top-bar 件数 refresh verification (FmKit private/public bin titles).
//
// Pre-fix the {n} in private-title / public-title was baked in at
// Bukkit.createInventory() and never re-sent, so the count froze at the value it
// had when the window opened. This drives the real server: deposit entries, open
// the bin, then take entries and assert (a) every open_window title the server
// sends carries the live count, and (b) the count always equals the number of
// entry cards actually rendered.
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25567', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25575', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.1'
const BOT_NAME = process.env.BOT_NAME || 'FmTitler'
const FILLER = 'gray_stained_glass_pane'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')

const results = []
function record(name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`)
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

function waitFor(cond, timeoutMs = 8000, poll = 100) {
  const start = Date.now()
  return new Promise(resolve => {
    const iv = setInterval(() => {
      let v = false
      try { v = cond() } catch { v = false }
      if (v) { clearInterval(iv); resolve(v) }
      else if (Date.now() - start > timeoutMs) { clearInterval(iv); resolve(null) }
    }, poll)
  })
}

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    const send = (id, type, body) => {
      const b = Buffer.alloc(14 + Buffer.byteLength(body))
      b.writeInt32LE(b.length - 4, 0); b.writeInt32LE(id, 4); b.writeInt32LE(type, 8); b.write(body, 12)
      sock.write(b)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout: ' + cmd)) }, 8000)
    sock.on('connect', () => send(1, 3, RCON_PASS))
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
        else if (type === 2 && id === -1) { clearTimeout(timer); sock.end(); reject(new Error('rcon auth failed')) }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}

/** Chat component (object / JSON string / NBT-ish) -> flat text. */
// Titles arrive as prismarine-nbt tagged trees: {type:'compound',value:{...}},
// with implicit sibling strings stored under an unnamed '' key.
const TAGS = new Set(['compound', 'list', 'string', 'byte', 'short', 'int', 'long', 'float', 'double', 'byteArray', 'intArray', 'longArray', 'shortArray', 'end'])
function unnbt(x) {
  if (x == null || typeof x !== 'string' && typeof x !== 'object') return x
  if (Array.isArray(x)) return x.map(unnbt)
  if (typeof x === 'object') {
    const k = Object.keys(x)
    if (k.length === 2 && 'type' in x && 'value' in x && typeof x.type === 'string' && TAGS.has(x.type)) return unnbt(x.value)
    const out = {}
    for (const key of k) out[key] = unnbt(x[key])
    return out
  }
  return x
}

function flatten(raw) {
  const walk = t => {
    if (t == null) return ''
    if (typeof t !== 'object') return String(t)
    if (Array.isArray(t)) return t.map(walk).join('')
    let out = ''
    if ('' in t) out += walk(t[''])
    if (t.text != null) out += walk(t.text)
    if (t.translate != null) out += walk(t.translate)
    if (t.with != null) out += walk(t.with)
    if (t.extra != null) out += walk(t.extra)
    return out
  }
  if (typeof raw === 'string') {
    const s = raw.trim()
    if (s.startsWith('{') || s.startsWith('[') || s.startsWith('"')) {
      try { return walk(unnbt(JSON.parse(raw))) } catch { return raw }
    }
    return raw
  }
  return walk(unnbt(raw))
}

/** The 件数 in a bin title, or null when the title has no count. */
function titleCount(raw) {
  const m = /(\d+)\s*件/.exec(flatten(raw).replace(/\u00a7./g, ''))
  return m ? parseInt(m[1], 10) : null
}

const PAGE0_SLOTS = []
for (const row of [1, 2, 3, 4]) for (let col = 0; col < 9; col++) PAGE0_SLOTS.push(row * 9 + col)
const isFiller = it => it == null || it.name === FILLER || it.name === 'black_stained_glass_pane'
const cards = win => PAGE0_SLOTS.filter(s => !isFiller(win.slots[s]))

async function main() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.messages = []
  bot.on('messagestr', m => bot.messages.push(m))
  const echoOf = sub => bot.messages.some(m => m.includes(sub))

  // Every title the server pushes, in order: the open packet plus each in-place
  // retitle. Registered on the raw client up front so nothing is missed.
  const titles = []
  bot._client.on('open_window', p => {
    const text = flatten(p.windowTitle ?? p.title)
    titles.push({ id: p.windowId, text })
    if (process.env.DEBUG) console.log(`  [open_window] id=${p.windowId} title="${text}"`)
  })

  await new Promise((res, rej) => {
    bot.once('spawn', res)
    bot.once('error', rej)
    bot.once('kicked', r => rej(new Error('kicked: ' + r)))
    setTimeout(() => rej(new Error('spawn timeout')), 45000)
  })
  await sleep(1200)
  // FmTerm (sibling plugin on this server) forces a terms GUI on first join and
  // freezes commands until the player decides, so accept it before testing.
  const terms = await waitFor(() => bot.currentWindow && /服务器条款/.test(flatten(bot.currentWindow.title)) ? bot.currentWindow : false, 6000)
  if (terms) {
    await bot.clickWindow(51, 0, 0)  // buttons.accept.slot in FmTerm config.yml
    const ok = await waitFor(() => echoOf('已同意服务器条款'), 8000)
    console.log(`  [prelude] terms accepted=${!!ok}`)
    await sleep(800)
  }
  titles.length = 0


  await rcon(`op ${BOT_NAME}`)
  await rcon('difficulty peaceful')
  await rcon('gamerule doMobSpawning false')
  await rcon('kill @e[type=item]')
  await rcon(`clear ${BOT_NAME}`)
  await rcon(`fmkitadmin clear ${BOT_NAME}`)
  await sleep(800)

  // Distinct materials: same-material entries would merge into one entry.
  const uuidInts = (() => {
    const h = bot.player.uuid.replace(/-/g, '')
    return [0, 1, 2, 3].map(i => (parseInt(h.slice(i * 8, i * 8 + 8), 16) | 0)).join(',')
  })()
  const MATS = ['dirt', 'stone', 'cobblestone', 'granite']
  for (let i = 0; i < MATS.length; i++) {
    await rcon(`execute as ${BOT_NAME} at @s run summon minecraft:item ~ ~1 ~${6 + i} {Item:{id:"minecraft:${MATS[i]}",count:1},Thrower:[I;${uuidInts}]}`)
  }
  await sleep(500)
  bot.messages.length = 0
  await rcon('fmkitadmin sweep now')
  await waitFor(() => echoOf('收集了'), 20000)
  await sleep(1200)

  // ---- private bin ----
  titles.length = 0
  bot.chat('/fmkit private')
  let win = await waitFor(() => bot.currentWindow, 10000)
  if (!win) { record('private GUI opens', false); return finish(bot) }
  await sleep(600)
  const openId = win.id
  const openCount = titleCount(titles[0] && titles[0].text)
  const openCards = cards(win).length
  if (openCards !== MATS.length || openCount == null) {
    console.log('  slots:', PAGE0_SLOTS.map(s => `${s}:${win.slots[s] ? win.slots[s].name : '-'}`).join(' '))
    console.log('  titles:', JSON.stringify(titles))
  }
  record('T1 private opens with live count in title', openCount === openCards && openCount === MATS.length,
    `title=${openCount} cards=${openCards} raw="${(titles[0] || {}).text}"`)

  // Take one entry: the count must follow, in place, without the window closing.
  const slot = cards(win)[0]
  titles.length = 0
  try { await bot.clickWindow(slot, 0, 0) } catch (e) { console.log('  click err', e.message) }
  await sleep(1200)
  const afterTake = titleCount(titles.length ? titles[titles.length - 1].text : null)
  record('T2 take-one pushes a retitle', titles.length > 0,
    `packets=${titles.length} last="${(titles[titles.length - 1] || {}).text}"`)
  record('T3 retitled count decremented', afterTake === MATS.length - 1,
    `want=${MATS.length - 1} got=${afterTake}`)
  record('T4 retitle reused the same window id', titles.length > 0 && titles.every(t => t.id === openId),
    `open=${openId} got=${titles.map(t => t.id).join(',')}`)
  record('T5 window stayed open (no close-flush)', bot.currentWindow != null,
    `currentWindow=${bot.currentWindow ? bot.currentWindow.id : null}`)
  await sleep(400)
  const liveWin = bot.currentWindow
  record('T6 title count matches rendered cards', liveWin != null && afterTake === cards(liveWin).length,
    `title=${afterTake} cards=${liveWin ? cards(liveWin).length : 'n/a'}`)

  // Idle ticks must not spam retitles while the count is unchanged.
  titles.length = 0
  await sleep(4000)
  record('T7 unchanged count sends no retitle', titles.length === 0, `packets=${titles.length}`)

  // Right-click moves an entry to the public bin: private count drops again.
  const slot2 = cards(bot.currentWindow)[0]
  titles.length = 0
  try { await bot.clickWindow(slot2, 1, 0) } catch (e) { console.log('  click err', e.message) }
  await sleep(1200)
  const afterMove = titleCount(titles.length ? titles[titles.length - 1].text : null)
  record('T8 move-to-public refreshes private count', afterMove === MATS.length - 2,
    `want=${MATS.length - 2} got=${afterMove} packets=${titles.length}`)

  // ---- public bin: the moved entry must show up in its title ----
  try { bot.closeWindow(bot.currentWindow) } catch { }
  await sleep(400)
  titles.length = 0
  bot.chat('/fmkit public')
  win = await waitFor(() => bot.currentWindow, 10000)
  await sleep(600)
  const pubOpen = titleCount(titles[0] && titles[0].text)
  record('T9 public title counts the moved entry', win != null && pubOpen === cards(win).length && pubOpen >= 1,
    `title=${pubOpen} cards=${win ? cards(win).length : 'n/a'} raw="${(titles[0] || {}).text}"`)

  const pubId = win.id
  const pslot = cards(win)[0]
  titles.length = 0
  try { await bot.clickWindow(pslot, 0, 0) } catch (e) { console.log('  click err', e.message) }
  await sleep(1200)
  const pubAfter = titleCount(titles.length ? titles[titles.length - 1].text : null)
  record('T10 public take refreshes its count in place',
    pubAfter === pubOpen - 1 && titles.every(t => t.id === pubId) && bot.currentWindow != null,
    `want=${pubOpen - 1} got=${pubAfter} ids=${titles.map(t => t.id).join(',')}`)

  await rcon(`fmkitadmin clear ${BOT_NAME}`)
  await rcon('fmkitadmin clearpublic')
  await rcon('fmkitadmin clearpublic')
  finish(bot)
}

function finish(bot) {
  const bad = results.filter(r => !r.ok)
  console.log(`\n${results.length - bad.length}/${results.length} passed`)
  console.log(bad.length === 0 ? 'TITLE_OK' : 'TITLE_FAIL ' + bad.map(r => r.name).join('; '))
  try { bot.quit() } catch { }
  setTimeout(() => process.exit(bad.length === 0 ? 0 : 1), 500)
}

main().catch(e => {
  console.log('FATAL ' + (e && e.message ? e.message : e))
  process.exit(3)
})
