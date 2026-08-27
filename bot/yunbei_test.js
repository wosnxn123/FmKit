// FmYunbei GUI smoke test — mineflayer bot + RCON assertions.
// Run: node yunbei_test.js   (server: servers/folia, port 25568)
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
function rcon(cmd) {
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
/** Parse 存放：X 格非空 / 共 Y 件 from admin status. */
async function statusCounts() {
  const out = (await rcon(`fmyunbeiadmin status ${BOT_NAME}`)).replace(/\u00a7./g, '')
  const m = out.match(/(\d+)\s*格非空.*?(\d+)\s*件/)
  return m ? { used: +m[1], items: +m[2], raw: out } : { used: -1, items: -1, raw: out }
}

// ---- GUI helpers ----
async function click(bot, slot, button = 0, mode = 0) {
  try { await bot.clickWindow(slot, button, mode) } catch (e) { console.log(`  click(${slot}) err: ${e.message}`) }
  await sleep(250)
}
async function openGui(bot, cmd) {
  let win = null
  const onOpen = w => { win = w }
  bot.on('windowOpen', onOpen)
  bot.chat(cmd)
  await waitFor(() => win !== null, 6000)
  bot.removeListener('windowOpen', onOpen)
  await sleep(400)
  return bot.currentWindow
}
function closeGui(bot) {
  if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch { } }
}
function titleText(win) {
  // title is a parsed NBT component; collect only strings under 'text' keys
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
/** Item custom display name, flattened to plain text (§ codes stripped). */
function textOfItem(it) {
  if (!it) return ''
  const out = []
  const walk = (n, key) => {
    if (n == null) return
    if (typeof n === 'string') { if (key === 'text' || key === '') out.push(n); return }
    if (typeof n !== 'object') return
    if (Array.isArray(n)) { for (const c of n) walk(c, key); return }
    if (n.type === 'string' && typeof n.value === 'string') { if (key === 'text' || key === '') out.push(n.value); return }
    if (n.value !== undefined) { walk(n.value, key); return }
    for (const [k, v] of Object.entries(n)) if (k !== 'type') walk(v, k)
  }
  const comp = it.components && it.components.find
    ? it.components.find(c => c.type === 'custom_name' || c.type === 8)
    : null
  walk(it.customName ?? (comp ? comp.data : null) ?? it.nbt?.value?.display?.value?.Name, '')
  return out.join('').replace(/\u00a7./g, '').trim()
}
function invCount(bot, name) {
  return bot.inventory.items().filter(i => i.name === name).reduce((a, i) => a + i.count, 0)
}

// ---- Scenario ----
async function scenario() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: VERSION, auth: 'offline' })
  bot.on('error', e => console.log('bot error:', e.message))
  let protoStateId = 0
  bot._client.on('window_items', p => { protoStateId = p.stateId })
  bot._client.on('set_slot', p => { protoStateId = p.stateId })
  const rawClick = (slot, button, mode) => {
    const w = bot.currentWindow
    bot._client.write('window_click', {
      windowId: w.id, stateId: protoStateId, slot, mouseButton: button, mode,
      changedSlots: [], cursorItem: null
    })
  }
  // ---- carried-item (cursor) sync for protocol >= 1.21.2 ----
  // 26.x syncs the mouse cursor via set_cursor_item and window_items.carriedItem;
  // stock mineflayer ignores both, leaving window.selectedItem stuck at whatever
  // local click prediction computed. Mirror server state into selectedItem.
  let ItemCls = null
  const itemCls = () => ItemCls || (ItemCls = require('prismarine-item')(bot.registry))
  const setCursor = (w, slot) => { if (w) w.selectedItem = itemCls().fromNotch(slot) }
  bot._client.on('set_cursor_item', p => setCursor(bot.currentWindow || bot.inventory, p.contents))
  bot._client.on('window_items', p => {
    if (!('carriedItem' in p)) return
    setCursor(p.windowId === 0 ? bot.inventory : bot.currentWindow, p.carriedItem)
  })
  await new Promise(res => bot.once('spawn', res))
  console.log('bot spawned:', bot.username)
  const chat = []
  bot.on('message', m => chat.push(m.toString()))

  // shield the bot: a creeper once killed it mid-run, closing the GUI and
  // dropping the cursor stack (sweep then ate it) — pure environment noise.
  await rcon(`effect give ${BOT_NAME} minecraft:resistance 600 4 true`)
  await rcon(`effect give ${BOT_NAME} minecraft:fire_resistance 600 0 true`)

  // fresh-profile baseline: earlier runs (and yunbei_hotkey_test's `giveslots 36`)
  // accumulate bonus slots on the cached profile, and the layout / locked-slot cases
  // below assume the default 27 unlocked. Reclaim whatever bonus is on the profile.
  await rcon(`fmyunbeiadmin clear ${BOT_NAME} confirm`)
  await rcon(`fmyunbeiadmin settier ${BOT_NAME} 0`)
  const bonus0 = Number(((await statusCounts()).raw.match(/加成\s*(\d+)/) || [, 0])[1])
  if (bonus0 > 0) await rcon(`fmyunbeiadmin giveslots ${BOT_NAME} -${bonus0}`)

  // clean slate + give 64 stone
  await rcon(`clear ${BOT_NAME}`)
  await rcon(`give ${BOT_NAME} minecraft:stone 64`)
  await sleep(500)
  record('give stone', invCount(bot, 'stone') === 64, `inv stone=${invCount(bot, 'stone')}`)

  // ---- open GUI (FmTerm terms probe loop) ----
  let win = null
  for (let attempt = 0; attempt < 3; attempt++) {
    win = await openGui(bot, '/fmyunbei')
    if (!win) break
    const t = titleText(win)
    if (t.includes('云背包')) break
    const li = win.slots.findIndex((s, i) => i < 54 && s && s.name === 'lime_concrete')
    if (li >= 0) { console.log('  FmTerm terms menu detected, accepting…'); await click(bot, li); await sleep(600); continue }
    break
  }
  if (!win) { record('open gui', false, 'no window opened'); return finish(bot) }
  const title = titleText(win)
  console.log('  TITLE:', title)
  console.log('  TITLE RAW:', JSON.stringify(win.title).slice(0, 400))
  record('open gui', title.includes('云背包'), `title=${title.slice(0, 80)}`)

  // layout: page-0 top bar = panes 0,1,3,5,7,8 + xp_bottle@2 sunflower@4 book@6;
  // unlocked=27 -> locals 9..35 air, single preview local 36 light-blue, rest gray;
  // toolbar 45..53 = arrow(prev), comparator(sort), paper(page), nether_star(tier),
  //                  clock(refresh), pane, hopper(takeAll), lime_shulker_box(depositAll), arrow(next)
  console.log('  slots0-8:', win.slots.slice(0, 9).map(s => s ? s.name : 'null').join(','))
  console.log('  slots9-44:', win.slots.slice(9, 45).map(s => s ? s.name : 'null').join(','))
  const topBarOk = [0, 1, 3, 5, 7, 8].every(i => win.slots[i] && win.slots[i].name === 'black_stained_glass_pane')
    && win.slots[2] && win.slots[2].name === 'experience_bottle'
    && win.slots[4] && win.slots[4].name === 'sunflower'
    && win.slots[6] && win.slots[6].name === 'writable_book'
  const unlockedOk = win.slots.slice(9, 36).every(s => !s || s.name === 'air')
  const lockedOk = win.slots[36] && win.slots[36].name === 'light_blue_stained_glass_pane'
    && win.slots.slice(37, 45).every(s => s && s.name === 'gray_stained_glass_pane')
  const toolbarItems = win.slots[45] && win.slots[45].name === 'arrow'
    && win.slots[46] && win.slots[46].name === 'comparator'
    && win.slots[47] && win.slots[47].name === 'paper'
    && win.slots[48] && win.slots[48].name === 'nether_star'
    && win.slots[49] && win.slots[49].name === 'clock'
    && win.slots[50] && win.slots[50].name === 'black_stained_glass_pane'
    && win.slots[51] && win.slots[51].name === 'hopper'
    && win.slots[52] && win.slots[52].name === 'lime_shulker_box'
    && win.slots[53] && win.slots[53].name === 'arrow'
  record('layout top bar + unlocked/locked panes', topBarOk && unlockedOk && lockedOk,
    `top=${topBarOk} unlocked=${unlockedOk} locked=${lockedOk}`)
  record('toolbar icons', toolbarItems, win.slots.slice(45, 54).map(s => s ? s.name : '-').join(','))

  // ---- store stone via cursor click on slot 0 ----
  const stoneWinSlot = win.slots.findIndex((s, i) => i >= (win.inventoryStart || 54) && s && s.name === 'stone')
  record('stone visible in player inv window', stoneWinSlot >= 0, `slot=${stoneWinSlot}`)
  await click(bot, stoneWinSlot)                       // pick up to cursor
  record('cursor holds stone', !!(win.selectedItem && win.selectedItem.name === 'stone'), win.selectedItem ? win.selectedItem.name : 'empty')
  await click(bot, 9)                                  // local 9 = global slot 0 (top bar occupies 0..8)
  let st = await statusCounts()
  console.log('  STATUS RAW AFTER STORE:\n' + st.raw)
  st = await waitFor(async () => (await statusCounts()).items === 64, 8000).then(async ok => ok ? statusCounts() : statusCounts())
  record('store stone 64', st.used === 1 && st.items === 64, `used=${st.used} items=${st.items}`)
  await sleep(1200) // let auto-refresh repaint
  win = bot.currentWindow || win // store caused a retitle reopen: refresh window ref
  const shown = win.slots[9]
  record('gui local 9 shows stone', !!shown && shown.name === 'stone' && shown.count === 64, shown ? `${shown.name}x${shown.count}` : 'empty')
  // ---- take it back: left-click occupied slot with empty cursor ----
  await click(bot, 9)
  // The take triggers a retitle (used 1->0) -> server reopens the window:
  // bot.currentWindow becomes a fresh object. The server-synced cursor arrives
  // via set_cursor_item / window_items.carriedItem (mirrored into selectedItem above).
  const cursorOf = () => {
    const c = bot.inventory.slots[45]
    if (c && c.name === 'stone') return c
    const w = bot.currentWindow
    if (w && w.selectedItem && w.selectedItem.name === 'stone') return w.selectedItem
    return null
  }
  await waitFor(cursorOf, 5000)
  win = bot.currentWindow || win
  const cursor = cursorOf()
  record('take unit stack to cursor', !!(cursor && cursor.name === 'stone' && cursor.count === 64),
    cursor ? `${cursor.name}x${cursor.count}` : 'empty')
  // put cursor stone back into player inventory: click an empty inventory slot
  const emptyInv = win.slots.findIndex((s, i) => i >= (win.inventoryStart || 54) && !s)
  if (emptyInv >= 0) await click(bot, emptyInv)
  await sleep(2500)
  st = await statusCounts()
  record('cloud emptied', st.used === 0 && st.items === 0, `used=${st.used} items=${st.items}`)
  record('stone back in inventory', invCount(bot, 'stone') === 64, `inv=${invCount(bot, 'stone')}`)

  // ---- stale-sync regression: partial take must leave the CLIENT slot at 64 ----
  // The click is cancelled server-side, so the vanilla client repaints slot 9 from
  // its own prediction (whole stack gone). Only the forced resync (pendingSync ->
  // full redraw + updateInventory) puts the true remainder back on screen.
  await rcon(`clear ${BOT_NAME}`)
  await rcon(`give ${BOT_NAME} minecraft:stone 64`)
  await sleep(400)
  let src = win.slots.findIndex((s, i) => i >= (win.inventoryStart || 54) && s && s.name === 'stone')
  await click(bot, src)                 // 64 to cursor
  await click(bot, 9)                   // store 64
  await waitFor(async () => (await statusCounts()).items === 64, 8000)
  await rcon(`give ${BOT_NAME} minecraft:stone 64`)
  await sleep(400)
  win = bot.currentWindow || win
  src = win.slots.findIndex((s, i) => i >= (win.inventoryStart || 54) && s && s.name === 'stone')
  await click(bot, src)                 // second stack to cursor
  await click(bot, 9)                   // merge -> 128 (cap = 64 * 2x)
  const merged = await waitFor(async () => (await statusCounts()).items === 128, 8000)
  record('merge to 128 (cap 2x)', merged, `items=${(await statusCounts()).items}`)
  await click(bot, 9)                   // empty-cursor left click: take one stack (64)
  await waitFor(async () => (await statusCounts()).items === 64, 8000)
  await sleep(1200)                     // let the resync land
  win = bot.currentWindow || win
  const left = win.slots[9]
  const leftName = left ? textOfItem(left) : ''
  record('client slot resynced after partial take',
    !!left && left.name === 'stone' && left.count === 64 && leftName.includes('×64'),
    left ? `${left.name}x${left.count} name="${leftName}"` : 'slot empty (stale prediction won)')
  await click(bot, 51)                  // takeAll reset
  await waitFor(async () => (await statusCounts()).items === 0, 8000)
  await rcon(`clear ${BOT_NAME}`)
  await rcon(`give ${BOT_NAME} minecraft:stone 64`)
  await sleep(500)

  // ---- cursor + occupied slot, non-mergeable: vanilla-style exchange ----
  // <= one vanilla unit in the slot -> true swap (slot stack to the cursor, cursor
  // stack into the slot). Above one unit the cursor cannot carry the slot out, so
  // the click is refused (swap-too-big) rather than stranding a remainder.
  const cursorNamed = (name) => {
    const c = bot.inventory.slots[45]
    if (c && c.name === name) return c
    const w = bot.currentWindow
    if (w && w.selectedItem && w.selectedItem.name === name) return w.selectedItem
    return null
  }
  /** Cloud empty, tier 0, bag empty, GUI closed — both swap cases start here. */
  const resetSides = async () => {
    closeGui(bot)
    await sleep(600)
    await rcon(`fmyunbeiadmin clear ${BOT_NAME} confirm`)
    await rcon(`fmyunbeiadmin settier ${BOT_NAME} 0`)
    await rcon(`clear ${BOT_NAME}`)
    await sleep(400)
  }
  /** n of item straight into cloud slot 0 via the admin path, then reopen the GUI. */
  const seedCloud = async (item, n) => {
    await rcon(`fmyunbeiadmin give ${BOT_NAME} ${item} ${n}`)
    await sleep(400)
    win = await openGui(bot, '/fmyunbei')
    await sleep(700)
    const s = await statusCounts()
    return { ok: s.used === 1 && s.items === n, detail: `used=${s.used} items=${s.items}` }
  }
  /** One vanilla stack of stone onto the cursor. */
  const cursorStone = async () => {
    await rcon(`give ${BOT_NAME} minecraft:stone 64`)
    await sleep(500)
    win = bot.currentWindow || win
    src = win.slots.findIndex((s, i) => i >= (win.inventoryStart || 54) && s && s.name === 'stone')
    if (src >= 0) await click(bot, src)
    await sleep(400)
  }

  // case 7: slot holds exactly one unit -> swap
  await resetSides()
  const seed64 = await seedCloud('dirt', 64)
  await cursorStone()
  await click(bot, 9)
  await waitFor(() => cursorNamed('dirt'), 6000)
  await sleep(1000)
  win = bot.currentWindow || win
  st = await statusCounts()
  const onCursor = cursorNamed('dirt')
  const inSlot = win.slots[9]
  record('cursor swap: seeded one unit in cloud', seed64.ok, seed64.detail)
  record('cursor swap: slot stack lands on cursor',
    !!onCursor && onCursor.count === 64, onCursor ? `${onCursor.name}x${onCursor.count}` : 'empty')
  record('cursor swap: cursor stack takes the slot',
    !!inSlot && inSlot.name === 'stone' && inSlot.count === 64 && st.used === 1 && st.items === 64,
    `slot=${inSlot ? inSlot.name + 'x' + inSlot.count : 'empty'} used=${st.used} items=${st.items}`)

  // case 8: slot holds two units -> refuse, both sides untouched
  await resetSides()
  const seed128 = await seedCloud('dirt', 128)
  await cursorStone()
  chat.length = 0
  await click(bot, 9)
  await sleep(1000)
  win = bot.currentWindow || win
  st = await statusCounts()
  const kept = cursorNamed('stone')
  const stillDirt = win.slots[9]
  const tooBig = chat.find(t => t.includes('无法与手中物品交换'))
  record('cursor swap denied: seeded two units in cloud', seed128.ok, seed128.detail)
  const dirtName = stillDirt ? textOfItem(stillDirt) : ''
  record('cursor swap denied: cloud untouched',
    st.used === 1 && st.items === 128 && !!stillDirt && stillDirt.name === 'dirt'
    && stillDirt.count === 64 && dirtName.includes('×128'),
    `slot=${stillDirt ? stillDirt.name + 'x' + stillDirt.count : 'empty'} name="${dirtName}" used=${st.used} items=${st.items}`)
  record('cursor swap denied: cursor keeps its stack',
    !!kept && kept.count === 64, kept ? `${kept.name}x${kept.count}` : 'empty')
  record('cursor swap denied: swap-too-big message', !!tooBig, tooBig || '(none)')

  await resetSides()
  await rcon(`give ${BOT_NAME} minecraft:stone 64`)
  await sleep(500)
  win = await openGui(bot, '/fmyunbei')
  await sleep(600)

  // ---- locked slot deny ----
  await click(bot, 40) // locked slot (global 31)
  await sleep(300)
  st = await statusCounts()
  record('locked slot denied', st.used === 0 && st.items === 0, `used=${st.used} items=${st.items}`)

  // ---- depositAll (slot 52, shulker) then takeAll (slot 51, hopper) ----
  const preDeposit = bot.inventory.items().map(i => `${i.name}x${i.count}`).join(',')
  await click(bot, 52)
  st = await waitFor(async () => (await statusCounts()).items === 64, 8000).then(async ok => ok ? statusCounts() : statusCounts())
  record('depositAll via toolbar', st.used === 1 && st.items === 64,
    `used=${st.used} items=${st.items}; inv=${invCount(bot, 'stone')}; pre=[${preDeposit}]`)
  await click(bot, 51)
  const tookBack = await waitFor(async () => invCount(bot, 'stone') === 64, 8000)
  st = await statusCounts()
  record('takeAll via toolbar', tookBack && st.used === 0 && st.items === 0, `inv=${invCount(bot, 'stone')} used=${st.used} items=${st.items}`)

  // ---- page indicator sanity ----
  win = bot.currentWindow || win
  const pageInfo = win.slots[47]
  record('page indicator present', !!pageInfo && pageInfo.name === 'paper', pageInfo ? pageInfo.name : 'missing')

  // ---- refresh button (slot 49): redraw + resync, storage untouched, window stays usable ----
  await click(bot, 49)
  await sleep(600)
  win = bot.currentWindow || win
  st = await statusCounts()
  const afterRefresh = win.slots[47] && win.slots[47].name === 'paper'
    && win.slots[2] && win.slots[2].name === 'experience_bottle'
  record('refresh button redraws', afterRefresh && st.used === 0 && st.items === 0,
    `paper=${!!(win.slots[47])} used=${st.used} items=${st.items}`)

  // ---- display-only / no-op buttons: help@6, progress@2, tier@48 must not corrupt storage ----
  win = bot.currentWindow || win
  await click(bot, 6)
  await sleep(300)
  await click(bot, 2)
  await sleep(300)
  await click(bot, 48) // no economy on the test server -> buy denied
  await sleep(300)
  st = await statusCounts()
  record('top bar clicks safe', st.used === 0 && st.items === 0, `used=${st.used} items=${st.items}`)

  // ---- drag: 64 stone over 4 empty unlocked slots -> 16 each, nothing lost ----
  win = bot.currentWindow || win
  const dragSrc = win.slots.findIndex((s, i) => i >= (win.inventoryStart || 54) && s && s.name === 'stone')
  await click(bot, dragSrc)                      // cursor = 64 stone
  rawClick(-999, 0, 5); await sleep(200)         // drag start
  for (const t of [9, 10, 11, 12]) { rawClick(t, 1, 5); await sleep(150) }
  rawClick(-999, 2, 5); await sleep(300)         // drag end
  const dragOk = await waitFor(async () => (await statusCounts()).items === 64, 8000)
  st = await statusCounts()
  record('drag stores all 64', dragOk && st.items === 64, `used=${st.used} items=${st.items}`)
  await sleep(800)
  win = bot.currentWindow || win
  const dragCounts = [9, 10, 11, 12].map(i => win.slots[i] ? win.slots[i].count : 0)
  record('drag splits 16 per slot', st.used === 4 && dragCounts.every(c => c === 16), dragCounts.join(','))
  await click(bot, 51)                           // takeAll reset
  await waitFor(async () => invCount(bot, 'stone') === 64, 8000)

  closeGui(bot)
  return finish(bot)
}

async function finish(bot) {
  await sleep(500)
  try { bot.end() } catch { }
  const failed = results.filter(r => !r.ok)
  console.log(`\n==== ${results.length - failed.length}/${results.length} passed ====`)
  process.exit(failed.length ? 1 : 0)
}

scenario().catch(e => { console.error('FATAL', e); process.exit(2) })
