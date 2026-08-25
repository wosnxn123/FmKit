// Tiny RCON client: node rcon_cmd.js "<command>"
const net = require('net')
const HOST = '127.0.0.1', PORT = +(process.env.RCON_PORT || 25575), PASS = 'fmtest123'
const cmd = process.argv[2] || 'list'
const sock = net.connect(PORT, HOST)
let buf = Buffer.alloc(0)
const send = (id, type, body) => {
  const blen = Buffer.byteLength(body)
  const b = Buffer.alloc(14 + blen)
  b.writeInt32LE(b.length - 4, 0); b.writeInt32LE(id, 4); b.writeInt32LE(type, 8); b.write(body, 12)
  sock.write(b)
}
const timer = setTimeout(() => { console.log('RCON_TIMEOUT'); process.exit(3) }, 15000)
sock.on('connect', () => send(1, 3, PASS))
sock.on('data', d => {
  buf = Buffer.concat([buf, d])
  while (buf.length >= 4) {
    const len = buf.readInt32LE(0)
    if (buf.length < 4 + len) break
    const id = buf.readInt32LE(4), type = buf.readInt32LE(8)
    const body = buf.slice(10, 4 + len - 2).toString()
    buf = buf.slice(4 + len)
    if (type === 2 && id === 1) send(2, 2, cmd)
    else if (type === 0 && id === 2) { clearTimeout(timer); console.log(body); sock.end(); process.exit(0) }
    else if (type === 2 && id === -1) { clearTimeout(timer); console.log('RCON_AUTH_FAILED'); sock.end(); process.exit(2) }
  }
})
sock.on('error', e => { console.log('RCON_ERROR ' + e.message); process.exit(1) })
