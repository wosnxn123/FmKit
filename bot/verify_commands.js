// 验证新增指令：/fmyunbei sort、/fmyunbeiadmin give、/fmyunbeiadmin clear
// 以及 51/52 工具栏按钮对调后的图标与功能归属。
const HOST = '127.0.0.1'
const PORT = 25568
const RCON_PORT = 25576
const RCON_PASS = 'fmtest123'
const VERSION = '26.2'
const TAKE_ALL = 51   // 一键取回（漏斗）
const DEPOSIT = 52    // 一键存入（绿色潜影盒）
const BOT = 'FmProbe'   // 复用已完成 FmTerm 条款流程的账号，否则条款 GUI 会挡住云背包

require('./mc_shim')(VERSION)
const mineflayer = require('mineflayer')
const net = require('net')

const sleep = ms => new Promise(r => setTimeout(r, ms))

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let id = 0
    const pkt = (type, body) => {
      const b = Buffer.from(body, 'utf8')
      const f = Buffer.alloc(14 + b.length)
      f.writeInt32LE(10 + b.length, 0); f.writeInt32LE(++id, 4); f.writeInt32LE(type, 8); b.copy(f, 12)
      return f
    }
    let out = ''
    let idle = null
    sock.on('connect', () => { sock.write(pkt(3, RCON_PASS)); setTimeout(() => sock.write(pkt(2, cmd)), 120) })
    sock.on('data', d => {
      let o = 0
      while (o + 4 <= d.length) {
        const len = d.readInt32LE(o); if (o + 4 + len > d.length) break
        out += d.toString('utf8', o + 12, o + 4 + len - 2); o += 4 + len
      }
      clearTimeout(idle)
      idle = setTimeout(() => { sock.end(); resolve(out.replace(/\u00a7./g, '')) }, 350)
    })
    sock.on('error', reject)
  })
}

const status = async () => {
  for (let a = 0; a < 3; a++) {
    const s = await rcon(`fmyunbeiadmin status ${BOT}`)
    const m = s.match(/共\s*([\d,]+)\s*件/)
    const u = s.match(/(\d+)\s*格非空/)
    if (m && u) return { count: parseInt(m[1].replace(/,/g, ''), 10), used: parseInt(u[1], 10) }
    await sleep(400)
  }
  return { count: -1, used: -1 }
}

let fails = 0
const check = (label, got, want) => {
  const ok = got === want
  if (!ok) fails++
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}: got ${got}, want ${want}`)
}

async function main() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: VERSION, auth: 'offline' })
  bot.on('error', e => console.log('bot error:', e.message))
  await new Promise(r => bot.once('spawn', r))
  await sleep(2500)
  await rcon(`effect give ${BOT} minecraft:resistance 900 4 true`)
  await rcon('fmyunbeiadmin reload')
  // 需要足够格子来观察整理效果
  await rcon(`fmyunbeiadmin giveslots ${BOT} 20`)
  await sleep(400)

  let win = null
  const open = async () => {
    win = null
    for (let a = 1; a <= 3 && !win; a++) {
      const p = new Promise(r => { bot.once('windowOpen', w => r(w)); setTimeout(() => r(null), 6000) })
      await bot.chat('/fmyunbei')
      win = await p
    }
    if (!win) { console.log('FATAL no window'); process.exit(2) }
    await sleep(700)
  }
  const w = () => bot.currentWindow || win
  const click = async (slot, button = 0, mode = 0) => {
    await bot.clickWindow(slot, button, mode).catch(e => console.log('  click err:', e.message))
    await sleep(700)
  }
  const close = async () => { try { bot.closeWindow(bot.currentWindow) } catch { } ; await sleep(900) }
  const inv = name => bot.inventory.items().filter(i => i.name === name).reduce((a, i) => a + i.count, 0)

  // ---------- 起点：清空
  await rcon(`fmyunbeiadmin clear ${BOT} confirm`)
  await rcon(`clear ${BOT}`)
  await sleep(500)
  check('clear 后云端归零', (await status()).count, 0)

  // ---------- admin clear 二次确认：不带 confirm 必须不删数据
  await rcon(`fmyunbeiadmin give ${BOT} stone 500`)
  await sleep(500)
  check('admin give 写入 500', (await status()).count, 500)
  const warn = await rcon(`fmyunbeiadmin clear ${BOT}`)
  await sleep(400)
  check('clear 无 confirm 时保留数据', (await status()).count, 500)
  check('clear 无 confirm 时给出确认提示', /不可撤销/.test(warn), true)

  // ---------- admin give 未知物品必须拒绝
  const bad = await rcon(`fmyunbeiadmin give ${BOT} not_a_real_item 5`)
  check('give 未知物品被拒绝', /未知物品/.test(bad), true)
  check('give 未知物品不改动数据', (await status()).count, 500)

  // ---------- 51/52 图标与功能归属
  await open()
  check('槽 51 是漏斗', w().slots[TAKE_ALL].name, 'hopper')
  check('槽 52 是绿色潜影盒', w().slots[DEPOSIT].name, 'lime_shulker_box')

  // 51 = 一键取回：云端应减少，背包拿到石头
  await click(TAKE_ALL)
  await sleep(1200)
  await close()
  const afterTake = await status()
  check('点 51 触发取回（云端减少）', afterTake.count < 500, true)
  check('点 51 后背包有石头', inv('stone') > 0, true)

  // 52 = 一键存入：背包石头回到云端
  await open()
  await click(DEPOSIT)
  await sleep(1300)
  await close()
  check('点 52 触发存入（云端回到 500）', (await status()).count, 500)
  check('点 52 后背包清空石头', inv('stone'), 0)

  // ---------- sort：制造碎片格，再整理
  await rcon(`fmyunbeiadmin clear ${BOT} confirm`)
  await rcon(`clear ${BOT}`)
  await sleep(500)
  // give 会先填已有格再开新格，无法造出碎片；改用 GUI：手持一叠石头，
  // 右键点 3 个空云端格 —— CloudGui.storeOneEmpty 每次只存 1 个。
  await rcon(`give ${BOT} minecraft:stone 10`)
  await sleep(600)
  await open()
  // 54 起是玩家背包在容器窗口里的镜像（容器 0..53 之后）
  let handSlot = -1
  for (let i = 54; i < 90; i++) {
    const it = w().slots[i]
    if (it && it.name === 'stone') { handSlot = i; break }
  }
  check('窗口内找到石头', handSlot > 0, true)
  await click(handSlot)              // 拿到光标
  await click(9, 1, 0)               // 右键存 1
  await click(10, 1, 0)
  await click(11, 1, 0)
  await close()                      // 归还光标里剩下的石头到背包
  const frag = await status()
  check('制造出 3 个碎片格', frag.used, 3)
  check('碎片格共 3 件', frag.count, 3)

  const sortOut = await new Promise(r => {
    const lines = []
    const h = m => lines.push(m.toString())
    bot.on('message', h)
    bot.chat('/fmyunbei sort')
    setTimeout(() => { bot.removeListener('message', h); r(lines.join('\n')) }, 2500)
  })
  const sorted = await status()
  check('sort 后合并为 1 格', sorted.used, 1)
  check('sort 不吞件数', sorted.count, 3)
  check('sort 报告腾出格数', /腾出\s*2\s*格/.test(sortOut), true)

  // sort 幂等：已整齐时不再腾出
  const again = await new Promise(r => {
    const lines = []
    const h = m => lines.push(m.toString())
    bot.on('message', h)
    bot.chat('/fmyunbei sort')
    setTimeout(() => { bot.removeListener('message', h); r(lines.join('\n')) }, 2500)
  })
  check('sort 幂等（已整齐）', /已经很整齐/.test(again), true)
  check('sort 幂等后件数不变', (await status()).count, 3)

  // ---------- 收尾：清空探针数据
  await rcon(`fmyunbeiadmin clear ${BOT} confirm`)
  await rcon(`fmyunbeiadmin giveslots ${BOT} -20`)
  await rcon(`clear ${BOT}`)

  console.log(fails === 0 ? 'ALL PASS' : `${fails} FAILED`)
  bot.end()
  process.exit(fails === 0 ? 0 : 1)
}

main().catch(e => { console.log('FATAL', e); process.exit(2) })
