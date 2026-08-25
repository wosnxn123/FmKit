const net = require('net')
const RCON_PORT = parseInt(process.env.RCON_PORT || '25575', 10)
function rcon(cmd) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, '127.0.0.1')
    let buf = Buffer.alloc(0)
    const send = (id, type, body) => {
      const blen = Buffer.byteLength(body)
      const b = Buffer.alloc(14 + blen)
      b.writeInt32LE(b.length - 4, 0)
      b.writeInt32LE(id, 4)
      b.writeInt32LE(type, 8)
      b.write(body, 12)
      sock.write(b)
    }
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('timeout')) }, 6000)
    sock.on('connect', () => send(1, 3, 'fmtest123'))
    sock.on('data', d => {
      buf = Buffer.concat([buf, d])
      while (buf.length >= 4) {
        const len = buf.readInt32LE(0)
        if (buf.length < 4 + len) break
        const id = buf.readInt32LE(4)
        const type = buf.readInt32LE(8)
        const body = buf.slice(10, 4 + len - 2).toString()
        buf = buf.slice(4 + len)
        if (type === 2 && id === 1) send(2, 2, cmd)
        else if (type === 0 && id === 2) { clearTimeout(timer); sock.end(); resolve(body) }
        else if (type === 2 && id === -1) { clearTimeout(timer); sock.end(); reject(new Error('auth failed')) }
      }
    })
    sock.on('error', e => { clearTimeout(timer); reject(e) })
  })
}
;(async () => {
  console.log('port', RCON_PORT)
  console.log('list:', await rcon('list'))
  console.log('seed:', await rcon('seed'))
  process.exit(0)
})()
