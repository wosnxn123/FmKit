// FmShop console smoke: drives /fsa + /fmshop over RCON, asserts on output.
// usage: RCON_PORT=25581 node fmshop_console.js
const net = require('net')
const HOST = '127.0.0.1', PORT = +(process.env.RCON_PORT || 25581), PASS = process.env.RCON_PASS || 'fmtest123'

function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(PORT, HOST)
    let buf = Buffer.alloc(0), out = ''
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
        const body = buf.slice(12, 4 + len - 2).toString('utf8')
        buf = buf.slice(4 + len)
        if (type === 2 && id === 1) send(2, 2, cmd)
        else if (type === 2 && id === -1) { clearTimeout(timer); sock.end(); reject(new Error('RCON_AUTH_FAILED')) }
        else if (type === 0) { out += body; clearTimeout(timer); sock.end(); resolve(strip(out)) }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}

// RCON echoes legacy section-sign colour codes; drop them so assertions read plainly.
const strip = s => s.replace(/\u00a7x(\u00a7[0-9a-fA-F]){6}/g, '').replace(/\u00a7[0-9a-fk-orA-FK-OR]/g, '')

let pass = 0, fail = 0
function check(name, ok, detail) {
  if (ok) { pass++; console.log('PASS ' + name) }
  else { fail++; console.log('FAIL ' + name + (detail ? ' :: ' + detail.replace(/\n/g, ' | ') : '')) }
}

const fs = require('fs')
const path = require('path')
const DATA = process.env.DATA_DIR || path.join(__dirname, '..', 'servers', 'shoptest', 'plugins', 'FmShop')

async function main() {
  const T = {}
  T.status = await rcon('fsa status')
  check('status reports item count', /129/.test(T.status), T.status)
  check('status names currency', /金币/.test(T.status), T.status)

  T.doctor = await rcon('fsa doctor')
  check('doctor reports zero arbitrage loops', /0 错误/.test(T.doctor), T.doctor.slice(0, 300))
  check('doctor still lists soft findings', /53 警告/.test(T.doctor), T.doctor.slice(0, 300))

  // 80/40 are the shipped defaults, so the write is idempotent.
  T.priceGood = await rcon('fsa price DIAMOND 80 40')
  check('admin sets price', /已设置/.test(T.priceGood) && /80\.00/.test(T.priceGood)
    && /40\.00/.test(T.priceGood), T.priceGood)
  // /fmshop price quotes base x market multiplier, and /fsa price only reloads
  // config -- never the market state -- so an earlier suite's diamond trades
  // would leave the row off 100% and make the exact-number assert order
  // dependent. Reset the row so the quote is the base price by definition.
  await rcon('fsa market DIAMOND reset')
  T.priceRead = await rcon('fmshop price diamond')
  check('price lookup works for console', /买入 80\.00/.test(T.priceRead)
    && /卖出 40\.00/.test(T.priceRead), T.priceRead)
  check('price lookup shows market multiplier', /行情 100%/.test(T.priceRead), T.priceRead)

  T.priceBad = await rcon('fsa price NOT_A_REAL_ITEM 1 1')
  check('unknown material rejected', /未找到该商品/.test(T.priceBad), T.priceBad)
  T.priceInverted = await rcon('fsa price DIAMOND 10 40')
  check('sell above buy rejected', /买入|卖出|不能|必须/.test(T.priceInverted), T.priceInverted)

  T.giveOffline = await rcon('fsa give NoSuchPlayer_zz 100')
  check('give unknown player rejected', /玩家|找不到|未|不存在/.test(T.giveOffline), T.giveOffline)
  T.giveBadAmount = await rcon('fsa give NoSuchPlayer_zz abc')
  check('non-numeric amount rejected', /金额|数字|用法|不存在|玩家/.test(T.giveBadAmount), T.giveBadAmount)

  T.tax = await rcon('fsa tax')
  check('tax pool reported', /税|金币|0/.test(T.tax), T.tax)
  T.market = await rcon('fsa market DIAMOND')
  check('market shows multiplier', /%|行情|倍/.test(T.market), T.market)
  T.resetlimit = await rcon('fsa resetlimit *')
  check('resetlimit all responds', /限额|重置|已/.test(T.resetlimit), T.resetlimit)
  T.help = await rcon('fsa help')
  check('help lists admin verbs', /price/.test(T.help) && /doctor/.test(T.help), T.help.slice(0, 300))
  T.unknown = await rcon('fsa nosuchverb')
  check('unknown verb falls back to help', /商店管理指令/.test(T.unknown) && /doctor/.test(T.unknown),
    T.unknown.slice(0, 200))

  // /fsa audit reads the ledger off the IO thread, so RCON's synchronous reply
  // window closes before the rows arrive; assert on the ledger the admin
  // commands above wrote instead.
  await rcon('fsa audit')
  const day = new Date()
  const stamp = day.getFullYear() + '-' + String(day.getMonth() + 1).padStart(2, '0') + '-'
    + String(day.getDate()).padStart(2, '0')
  const ledger = path.join(DATA, 'audit', stamp + '.log')
  const rows = fs.existsSync(ledger) ? fs.readFileSync(ledger, 'utf8') : ''
  check('audit ledger records price change', /ADMIN DIAMOND PRICE/.test(rows), rows.slice(-200))
  check('audit rows carry actor and balance', /by=Rcon/.test(rows) && /bal=/.test(rows), rows.slice(-200))

  T.reload = await rcon('fsa reload')
  check('reload succeeds', /重载|已|完成/.test(T.reload), T.reload)
  T.afterReload = await rcon('fsa status')
  check('status still sane after reload', /129/.test(T.afterReload), T.afterReload)
  T.doctorAfter = await rcon('fsa doctor')
  check('table stays arbitrage-free after write+reload', /0 错误/.test(T.doctorAfter),
    T.doctorAfter.slice(0, 200))

  T.shopConsole = await rcon('fmshop')
  check('shop GUI refuses console', /玩家|控制台|players/.test(T.shopConsole), T.shopConsole)
  T.payConsole = await rcon('fmshop pay Someone 1')
  check('pay refuses console', /玩家|控制台|players/.test(T.payConsole), T.payConsole)
  T.buyConsole = await rcon('fmshop buy')
  check('buy refuses console', /玩家|控制台|players/.test(T.buyConsole), T.buyConsole)

  console.log('\n' + pass + ' passed, ' + fail + ' failed')
  process.exit(fail)
}

main().catch(e => { console.log('FATAL ' + e.message); process.exit(99) })
