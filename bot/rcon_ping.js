// tiny RCON probe: node rcon_ping.js [pass] [port] [cmd]
const net = require('net')
const pass = process.argv[2] || 'fmtest123'
const port = parseInt(process.argv[3] || '25576', 10)
const cmd = process.argv[4] || 'list'
const sock = net.connect(port, '127.0.0.1')
let buf = Buffer.alloc(0), authed = false
const send = (id, type, body) => {
  const b = Buffer.alloc(14 + Buffer.byteLength(body))
  b.writeInt32LE(b.length - 4, 0)
  b.writeInt32LE(id, 4)
  b.writeInt32LE(type, 8)
  b.write(body, 12)
  sock.write(b)
}
const timer = setTimeout(() => { console.log('TIMEOUT'); process.exit(2) }, 5000)
sock.on('connect', () => send(0, 3, pass))
sock.on('data', d => {
  buf = Buffer.concat([buf, d])
  while (buf.length >= 4) {
    const len = buf.readInt32LE(0)
    if (buf.length < 4 + len) break
    const id = buf.readInt32LE(4), type = buf.readInt32LE(8)
    const body = buf.slice(10, 4 + len - 2).toString()
    buf = buf.slice(4 + len)
    if (type === 2 && id === 0) { if (!authed) { authed = true; send(1, 2, cmd) } }
    else if (type === 0 && id === 1) { console.log('OK:', body); clearTimeout(timer); process.exit(0) }
    else if (type === 2 && id === -1) { console.log('AUTH FAILED'); clearTimeout(timer); process.exit(1) }
  }
})
sock.on('error', e => { console.log('ERR', e.message); clearTimeout(timer); process.exit(2) })
