// FmYunbei hotkey (number-key / off-hand swap) e2e test.
// Covers every reachable branch of CloudGui.hotbarSwap and asserts item
// conservation + the "never hand a client slot more than one vanilla stack"
// invariant. Run: node yunbei_hotkey_test.js   (server: servers/folia, 25568)
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25568', 10)
const RCON_PORT = parseInt(process.env.RCON_PORT || '25576', 10)
const RCON_PASS = process.env.RCON_PASS || 'fmtest123'
const VERSION = process.env.MC_VERSION || '26.2'
const BOT_NAME = process.env.BOT_NAME || 'FmTester'
// negative control: BREAK=1 asserts against deliberately wrong expectations
const BREAK = process.env.BREAK === '1'

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')

// page-0 layout: local 0-8 = top bar, local 9 => cloud global 0
const RAW_OF_GLOBAL = g => g + 9
// 54-slot chest window: 54-80 main inventory, 81-89 hotbar
const HOT = 0            // `give` after `clear` always fills hotbar slot 0
const SWAP_OFFHAND = 40  // vanilla button id for the off-hand swap

const results = []
function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -- ' + detail : ''}`)
}
function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }
async function waitFor(cond, timeoutMs = 8000, poll = 150) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    if (await cond()) return true
    await sleep(poll)
  }
  return false
}

// ---- RCON ----
const DEBUG = process.env.DEBUG === '1'
function rcon(cmd) {
  return DEBUG ? rcon0(cmd).then(r => { console.log(`  rcon> ${cmd}\n  rcon< ${String(r).replace(/\u00a7./g, '').trim().slice(0, 220)}`); return r }) : rcon0(cmd)
}
function rcon0(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    const send = (id, type, body) => {
      const blen = Buffer.byteLength(body)
      const b = Buffer.alloc(14 + blen)
      b.writeInt32LE(b.length - 4, 0); b.writeInt32LE(id, 4); b.writeInt32LE(type, 8); b.write(body, 12)
      sock.write(b)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('rcon timeout')) }, 15000)
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
/** Parse 存放：X 格非空 / 共 Y 件 from admin status — server-side ground truth. */
async function cloud() {
  const out = (await rcon(`fmyunbeiadmin status ${BOT_NAME}`)).replace(/\u00a7./g, '')
  const m = out.match(/(\d+)\s*格非空.*?(\d+)\s*件/)
  return m ? { used: +m[1], items: +m[2], raw: out } : { used: -1, items: -1, raw: out }
}
// Folia rejects `data get` / `item replace` (both parse-fail over RCON here), so
// the off-hand is seeded with a real client-side equip and read back from
// window 0: once the chest closes, vanilla's per-tick broadcastChanges pushes
// set_slot(0, 45) for the slot CloudGui rewrote. Player-side seeding uses
// `clear` + `give`, which always lands in hotbar slot 0.

// ---- GUI helpers ----
function titleText(win) {
  const out = []
  const walk = (n, key) => {
    if (n == null) return
    if (typeof n === 'string') { if (key === 'text') out.push(n); return }
    if (typeof n !== 'object') return
    if (Array.isArray(n)) { for (const c of n) walk(c, key); return }
    if (n.type === 'string' && typeof n.value === 'string') { if (key === 'text') out.push(n.value); return }
    if (n.value !== undefined) walk(n.value, key)
    for (const [k, v] of Object.entries(n)) if (k !== 'value' && k !== 'type') walk(v, k)
  }
  walk(win.title, '')
  return out.join('')
}
function fmt(s) { return s.name ? `${s.name}x${s.count}` : 'empty' }

async function scenario() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.on('error', e => console.log('bot error:', e.message))
  let protoStateId = 0
  bot._client.on('window_items', p => { protoStateId = p.stateId })
  bot._client.on('set_slot', p => { protoStateId = p.stateId })
  // Authoritative player-inventory mirror, fed from window 0 packets. mineflayer's
  // Window.slots does not track the player rows of an open container reliably
  // (measured: server pushed set_slot(win=0, slot=36, empty) while win.slots[81]
  // still read stonex64), so every hand/off-hand assertion reads this instead.
  const w0 = new Array(46).fill(null)
  const pk = it => (!it || it.present === false || it.itemCount === 0 || it.itemId === undefined)
    ? null
    : { name: bot.registry.items[it.itemId]?.name ?? String(it.itemId), count: it.itemCount }
  bot._client.on('set_slot', p => {
    if (p.windowId === 0 && p.slot >= 0 && p.slot < 46) w0[p.slot] = pk(p.item)
  })
  bot._client.on('window_items', p => {
    if (p.windowId !== 0 || !p.items) return
    for (let i = 0; i < 46 && i < p.items.length; i++) w0[i] = pk(p.items[i])
  })
  const seedMirror = () => {
    for (let i = 0; i < 46; i++) {
      const s = bot.inventory.slots[i]
      w0[i] = s ? { name: s.name, count: s.count } : null
    }
  }
  const at = raw => w0[raw] ? { name: w0[raw].name, count: w0[raw].count } : { name: null, count: 0 }
  const handSlot = () => at(36 + HOT)
  // raw packet: bypasses mineflayer's local click prediction entirely, so every
  // assertion below reads server state pushed by CloudGui.resyncLater
  const rawClick = (slot, button, mode) => {
    const w = bot.currentWindow
    bot._client.write('window_click', {
      windowId: w.id, stateId: protoStateId, slot, mouseButton: button, mode,
      changedSlots: [], cursorItem: null
    })
  }
  const swap = async (global, button) => { rawClick(RAW_OF_GLOBAL(global), button, 2); await sleep(700) }

  await new Promise(res => bot.once('spawn', res))
  console.log('bot spawned:', bot.username)
  await rcon(`effect give ${BOT_NAME} minecraft:resistance 600 4 true`)
  await rcon(`effect give ${BOT_NAME} minecraft:fire_resistance 600 0 true`)
  await rcon(`gamemode survival ${BOT_NAME}`)

  let win = null
  const openGui = async () => {
    for (let attempt = 0; attempt < 3; attempt++) {
      let w = null
      const onOpen = x => { w = x }
      bot.on('windowOpen', onOpen)
      bot.chat('/fmyunbei')
      await waitFor(() => w !== null, 6000)
      bot.removeListener('windowOpen', onOpen)
      await sleep(500)
      if (!bot.currentWindow) return null
      const t = titleText(bot.currentWindow)
      if (t.includes('云背包')) return bot.currentWindow
      // FmTerm terms gate: accept and retry
      const li = bot.currentWindow.slots.findIndex((s, i) => i < 54 && s && s.name === 'lime_concrete')
      if (li < 0) return null
      console.log('  FmTerm terms menu detected, accepting…')
      rawClick(li, 0, 0); await sleep(800)
    }
    return null
  }

  /** Off-hand exactly as the server last pushed it to window 0. */
  const offhandSlot = () => at(45)
  const closeWin = async () => {
    if (win) { try { bot.closeWindow(win) } catch { } ; win = null; await sleep(700) }
  }

  /** Reset cloud + player inventory, then seed both sides. */
  const setup = async ({ tier = 0, cloudItem = null, cloudN = 0, handItem = null, handN = 0, offItem = null, offN = 0, dropTier = null }) => {
    await closeWin()
    await rcon(`fmyunbeiadmin clear ${BOT_NAME} confirm`)
    await rcon(`fmyunbeiadmin settier ${BOT_NAME} ${tier}`)
    await rcon(`clear ${BOT_NAME}`)
    if (cloudItem) await rcon(`fmyunbeiadmin give ${BOT_NAME} ${cloudItem} ${cloudN}`)
    if (dropTier !== null) await rcon(`fmyunbeiadmin settier ${BOT_NAME} ${dropTier}`)
    if (handItem) await rcon(`give ${BOT_NAME} minecraft:${handItem} ${handN}`)
    if (offItem) {
      await rcon(`give ${BOT_NAME} minecraft:${offItem} ${offN}`)
      await sleep(500)
      const it = bot.inventory.items().find(i => i.name === offItem)
      if (it) { try { await bot.equip(it, 'off-hand') } catch (e) { console.log('  equip err:', e.message) } }
      await sleep(400)
    }
    await sleep(400)
    seedMirror()
    win = await openGui()
    return win
  }

  win = await openGui()
  if (!win) { record('open gui', false, 'no window opened'); return finish(bot) }
  record('open gui', true, titleText(win).slice(0, 40))
  await rcon(`fmyunbeiadmin giveslots ${BOT_NAME} 36`) // once: keep unlocked >= 36


  const bad = n => BREAK ? n + 1 : n   // negative control skews expectations

  // ---- A: empty hand + occupied cloud slot, number key (branch: handEmpty) ----
  // 100 dirt, takeUnit 64 -> exactly one vanilla stack leaves the cloud
  if (await setup({ cloudItem: 'dirt', cloudN: 100 })) {
    const before = await cloud()
    await swap(0, HOT)
    const after = await cloud(), hand = handSlot()
    record('A numberkey take: hand gets one stack unit',
      hand.name === 'dirt' && hand.count === bad(64), `hand=${fmt(hand)}`)
    record('A numberkey take: cloud keeps remainder',
      after.items === bad(36) && after.used === 1, `cloud=${after.items} used=${after.used}`)
    record('A numberkey take: conserved',
      before.items === after.items + hand.count, `${before.items} -> ${after.items}+${hand.count}`)
    record('A numberkey take: no oversized client stack', hand.count <= 64, `count=${hand.count}`)
  } else record('A setup', false, 'gui did not open')

  // ---- B: empty off-hand + occupied cloud slot, F key (SWAP_OFFHAND) ----
  if (await setup({ cloudItem: 'dirt', cloudN: 100 })) {
    const before = await cloud()
    await swap(0, SWAP_OFFHAND)
    const after = await cloud()
    await closeWin()               // let window 0 resync slot 45
    const off = offhandSlot()
    record('B offhand take: off-hand gets one stack unit',
      off.name === 'dirt' && off.count === bad(64), `offhand=${fmt(off)}`)
    record('B offhand take: cloud keeps remainder',
      after.items === bad(36) && after.used === 1, `cloud=${after.items} used=${after.used}`)
    record('B offhand take: conserved',
      before.items === after.items + off.count, `${before.items} -> ${after.items}+${off.count}`)
  } else record('B setup', false, 'gui did not open')

  // ---- C: hand item + empty cloud slot (branch: sd == null) ----
  // tier 0 cap = 128 >= 64, so the whole hand stack is stored
  if (await setup({ handItem: 'stone', handN: 64 })) {
    const before = handSlot()
    const msgs = []
    const onMsg = m => msgs.push(m.toString())
    bot.on('message', onMsg)
    await swap(0, HOT)
    const after = await cloud(), hand = handSlot()
    bot.removeListener('message', onMsg)
    const stored = msgs.find(t => t.includes('存入'))
    record('C numberkey deposit: seeded one stack in hand',
      before.name === 'stone' && before.count === 64, `hand=${fmt(before)}`)
    record('C numberkey deposit: hand emptied', hand.name === null, `hand=${fmt(hand)}`)
    record('C numberkey deposit: cloud stores stack',
      after.items === bad(64) && after.used === 1, `cloud=${after.items} used=${after.used}`)
    record('C numberkey deposit: no duplication',
      after.items + hand.count === 64, `cloud=${after.items} hand=${hand.count}`)
    record('C numberkey deposit: stored message names the item, not air',
      !!stored && /石头|stone/i.test(stored) && stored.includes('×64') && !/空气|air/i.test(stored),
      `msg=${stored ?? '(none)'}`)
  } else record('C setup', false, 'gui did not open')

  // ---- D: mergeable hand + occupied cloud slot (branch: canMerge, room) ----
  // cloud 100 dirt, hand 20 dirt -> hand 64, cloud 100-64+20 = 56
  if (await setup({ cloudItem: 'dirt', cloudN: 100, handItem: 'dirt', handN: 20 })) {
    await swap(0, HOT)
    const after = await cloud(), hand = handSlot()
    record('D merge swap: hand holds one stack unit',
      hand.name === 'dirt' && hand.count === bad(64), `hand=${fmt(hand)}`)
    record('D merge swap: cloud absorbs hand stack',
      after.items === bad(56) && after.used === 1, `cloud=${after.items}`)
    record('D merge swap: conserved 120', after.items + hand.count === 120,
      `${after.items}+${hand.count}`)
  } else record('D setup', false, 'gui did not open')

  // ---- E: non-mergeable hand, cloud holds more than one unit -> DENY ----
  // the anti-strand rule: swapping would leave 36 dirt with nowhere to go
  if (await setup({ cloudItem: 'dirt', cloudN: 100, handItem: 'stone', handN: 64 })) {
    await swap(0, HOT)
    const after = await cloud(), hand = handSlot()
    record('E denied swap: hand untouched',
      hand.name === (BREAK ? 'dirt' : 'stone') && hand.count === 64, `hand=${fmt(hand)}`)
    record('E denied swap: cloud untouched',
      after.items === bad(100) && after.used === 1, `cloud=${after.items}`)
  } else record('E setup', false, 'gui did not open')

  // ---- F: non-mergeable hand, cloud holds exactly one unit -> true swap ----
  if (await setup({ cloudItem: 'dirt', cloudN: 64, handItem: 'stone', handN: 32 })) {
    await swap(0, HOT)
    const after = await cloud(), hand = handSlot()
    record('F exact swap: hand receives cloud stack',
      hand.name === (BREAK ? 'stone' : 'dirt') && hand.count === 64, `hand=${fmt(hand)}`)
    record('F exact swap: cloud receives hand stack',
      after.items === bad(32) && after.used === 1, `cloud=${after.items}`)
  } else record('F setup', false, 'gui did not open')

  // ---- G: count above cap after a tier downgrade -> DENY (slot-full) ----
  // seed 250 at tier 1 (cap 256), drop to tier 0 (cap 128): nothing may move in
  if (await setup({ tier: 1, cloudItem: 'dirt', cloudN: 250, dropTier: 0, handItem: 'dirt', handN: 64 })) {
    const before = await cloud()
    await swap(0, HOT)
    const after = await cloud(), hand = handSlot()
    record('G over-cap slot: seeded above post-downgrade cap', before.items === 250, `cloud=${before.items}`)
    record('G over-cap slot: deposit refused',
      after.items === bad(250) && hand.name === 'dirt' && hand.count === 64,
      `cloud=${after.items} hand=${fmt(hand)}`)
  } else record('G setup', false, 'gui did not open')

  // ---- H: empty hand + empty cloud slot -> no-op ----
  if (await setup({})) {
    await swap(0, HOT)
    const after = await cloud(), hand = handSlot()
    record('H empty/empty: nothing happens',
      after.items === bad(0) && after.used === 0 && hand.name === null,
      `cloud=${after.items} hand=${fmt(hand)}`)
  } else record('H setup', false, 'gui did not open')

  // ---- J: off-hand item + empty cloud slot -> deposit writes back to slot 45 ----
  if (await setup({ offItem: 'stone', offN: 48 })) {
    await swap(0, SWAP_OFFHAND)
    const after = await cloud()
    await closeWin()
    const off = offhandSlot()
    record('J offhand deposit: cloud stores stack',
      after.items === bad(48) && after.used === 1, `cloud=${after.items} used=${after.used}`)
    record('J offhand deposit: off-hand emptied', off.name === null, `offhand=${fmt(off)}`)
  } else record('J setup', false, 'gui did not open')

  // ---- I: invariant sweep needs a live window ----
  if (!win) win = await openGui()

  // ---- I: invariant sweep — no client-visible slot exceeds one vanilla stack ----
  const over = win.slots
    .map((s, i) => ({ i, s }))
    .filter(({ s }) => s && s.count > s.stackSize)
    .map(({ i, s }) => `${i}:${s.name}x${s.count}>${s.stackSize}`)
  record('I no slot exceeds its vanilla max', over.length === 0, over.join(',') || 'clean')

  return finish(bot)
}

async function finish(bot) {
  const pass = results.filter(r => r.ok).length
  console.log(`\n=== ${pass}/${results.length} passed ===`)
  for (const r of results.filter(r => !r.ok)) console.log(`  FAILED: ${r.name} ${r.detail}`)
  try { await rcon(`fmyunbeiadmin clear ${BOT_NAME} confirm`) } catch { }
  try { bot.quit() } catch { }
  await sleep(300)
  process.exit(pass === results.length ? 0 : 1)
}

scenario().catch(e => { console.error('FATAL', e); process.exit(2) })
