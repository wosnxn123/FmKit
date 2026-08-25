const mineflayer = require('mineflayer')

const HOST = '127.0.0.1'
const PORT = parseInt(process.env.PORT || '25565', 10)
const VERSION = '26.1'

const results = []
function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`)
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }

function waitFor(cond, timeoutMs = 8000, poll = 100) {
  const start = Date.now()
  return new Promise(resolve => {
    const tick = () => {
      let v
      try { v = cond() } catch { v = undefined }
      if (v) return resolve(v)
      if (Date.now() - start > timeoutMs) return resolve(null)
      setTimeout(tick, poll)
    }
    tick()
  })
}

function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VERSION, auth: 'offline' })
  bot.messages = []
  bot.on('messagestr', (msg) => bot.messages.push(msg))
  return bot
}

function echoOf(bot, text) {
  return bot.messages.some(m => m.includes(text))
}

async function joinAndWaitWindow(bot) {
  await new Promise(res => bot.once('spawn', res))
  const win = await waitFor(() => bot.currentWindow, 10000)
  if (win) await waitFor(() => win.slots.filter(Boolean).length > 20, 5000)
  return win
}

async function scenario() {
  // ---- Scenario A: layout + accept flow ----
  {
    const bot = makeBot('TestAccept')
    let kicked = null
    bot.on('kicked', r => { kicked = r })
    await new Promise(res => bot.once('spawn', res))
    record('A0 join', true, 'spawned')

    const win = await waitFor(() => bot.currentWindow, 10000)
    if (win) {
      // open + items packets can arrive separately (esp. on Folia); wait for items
      await waitFor(() => win.slots.filter(Boolean).length > 20, 5000)
    }
    record('A1 terms GUI opens on join', !!win, win ? `type=${win.type} slots=${win.slots.length}` : 'no window')

    const banner = win && win.slots[4] && win.slots[4].name === 'book'
    const frame0 = win && win.slots[0] && win.slots[0].name === 'black_stained_glass_pane'
    const declineAt47 = win && win.slots[47] && win.slots[47].name === 'red_concrete'
    const acceptAt51 = win && win.slots[51] && win.slots[51].name === 'lime_concrete'
    const pageFilled = win && [10, 11, 12, 13, 14, 15, 16, 19, 25, 28, 34, 37, 43]
        .every(s => win.slots[s] != null)
    record('A2 banner (book) at slot 4', !!banner)
    record('A3 frame panes at border', !!frame0)
    record('A4 decline button at slot 47', !!declineAt47)
    record('A5 accept button at slot 51', !!acceptAt51)
    record('A6 page area filled (cards/filler)', !!pageFilled)

    // Simulate Esc: client closes window; plugin must reopen
    await sleep(500)
    if (win) bot.closeWindow(win)
    await sleep(2500)
    record('A7 reopen after Esc', bot.currentWindow != null, `currentWindow=${bot.currentWindow ? 'open' : 'null'}`)

    // Chat must be blocked while pending
    bot.messages.length = 0
    bot.chat('SHOULD_BE_BLOCKED_12345')
    await sleep(2500)
    record('A8 chat blocked while pending', !echoOf(bot, 'SHOULD_BE_BLOCKED_12345'))

    // Neutral click keeps GUI open
    try { await bot.clickWindow(10, 0, 0) } catch { }
    await sleep(1000)
    record('A9 neutral click keeps GUI open', bot.currentWindow != null)

    // Click green dye = accept
    try { await bot.clickWindow(51, 0, 0) } catch { }
    const closed = await waitFor(() => bot.currentWindow == null, 5000)
    record('A10 accept closes GUI', !!closed)

    const gotAcceptMsg = await waitFor(() => echoOf(bot, '祝你游玩愉快'), 4000)
    record('A11 accept chat feedback received', !!gotAcceptMsg, `msgs=${bot.messages.slice(-3).join(' | ').slice(0, 120)}`)

    // Chat works again after accepting
    bot.messages.length = 0
    bot.chat('hello_after_accept')
    const echoed = await waitFor(() => echoOf(bot, 'hello_after_accept'), 4000)
    record('A12 chat works after accept', !!echoed)

    await sleep(3000)
    record('A13 GUI stays closed after accept', bot.currentWindow == null)
    record('A14 not kicked after accept', kicked == null, `kicked=${JSON.stringify(kicked)}`)
    bot.quit()
    await sleep(1500)
  }

  // ---- Scenario B: rejoin after accept -> no GUI ----
  {
    const bot = makeBot('TestAccept')
    await new Promise(res => bot.once('spawn', res))
    await sleep(5000) // plugin opens after 20 ticks if still pending
    record('B1 no GUI on rejoin after accept', bot.currentWindow == null)
    bot.quit()
    await sleep(1000)
  }

  // ---- Scenario C: decline flow ----
  {
    const bot = makeBot('TestDecline')
    const win = await joinAndWaitWindow(bot)
    record('C1 terms GUI opens for new player', !!win)
    if (win) {
      try { await bot.clickWindow(47, 0, 0) } catch { }
    }
    const kicked = await new Promise(res => {
      bot.once('kicked', r => res(r))
      setTimeout(() => res(null), 8000)
    })
    record('C2 decline kicks player', kicked != null, `reason=${JSON.stringify(kicked)}`)
    await sleep(1500)
  }

  // ---- Scenario D: rejoin after decline -> GUI again ----
  {
    const bot = makeBot('TestDecline')
    await joinAndWaitWindow(bot)
    record('D1 GUI reappears after declined rejoin', bot.currentWindow != null)
    bot.quit()
    await sleep(1000)
  }

  const failed = results.filter(r => !r.ok).length
  console.log(`\nSUMMARY: ${results.length - failed}/${results.length} passed`)
  process.exit(failed ? 1 : 0)
}

scenario().catch(e => { console.error('FATAL', e); process.exit(2) })
