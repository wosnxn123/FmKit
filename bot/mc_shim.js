// Makes minecraft-data / mineflayer accept an MC version that has a known protocol
// number but no packet definitions yet (e.g. 26.2, proto 776): reuse the closest
// older protocol definitions and advertise the real protocol number. Must run
// BEFORE minecraft-data's index initializes (i.e. before requiring mineflayer).
module.exports = function shim (VERSION) {
  const data = require('minecraft-data/data.js')
  if (!data.pc[VERSION]) {
    const pv = require('minecraft-data/minecraft-data/data/pc/common/protocolVersions.json')
    const target = pv.find(v => v.minecraftVersion === VERSION)
    if (!target) throw new Error(`unknown protocol for ${VERSION}`)
    const donors = Object.keys(data.pc)
      .map(k => ({ k, proto: data.pc[k].version.version }))
      .filter(x => x.proto < target.version)
      .sort((a, b) => b.proto - a.proto)
    if (!donors.length) throw new Error(`no donor protocol data for ${VERSION}`)
    const base = data.pc[donors[0].k]
    data.pc[VERSION] = { ...base, version: { ...base.version, version: target.version, minecraftVersion: VERSION } }
    console.log(`NOTE: no packet data for ${VERSION}; reusing ${donors[0].k} definitions, advertising proto ${target.version}`)
  }
  // 26.x does not send static registries (item/entity_type) over the wire, so the
  // donor's network ids may be wrong. Rebuild item/entity tables from a capture of
  // the server's true ids (produced by map_items.js) when available.
  const fs = require('fs')
  const path = require('path')
  const idsPath = path.join(__dirname, `static_ids_${VERSION.replace(/[^0-9a-z_.-]/gi, '_')}.json`)
  if (fs.existsSync(idsPath)) {
    const ids = JSON.parse(fs.readFileSync(idsPath, 'utf8'))
    const d = data.pc[VERSION]
    const itemCount = Object.keys(ids.items).length
    const entCount = Object.keys(ids.entities).length
    if (itemCount || entCount) {
      // Capture is server truth: place captured names at their true ids, keep
      // donor entries for un-captured names only where their slot is still free.
      if (itemCount) {
        const donorItems = Object.values(d.items)
        const itemMeta = {}
        for (const it of donorItems) if (it) itemMeta[it.name] = it
        const captured = new Set(Object.keys(ids.items))
        const items = []
        for (const [name, id] of Object.entries(ids.items)) {
          items[id] = { ...(itemMeta[name] || { displayName: name, stackSize: 64 }), id, name }
        }
        for (const it of donorItems) {
          if (!it || captured.has(it.name)) continue
          const id = it.id ?? items.length
          if (items[id] == null) items[id] = { ...it, id }
        }
        // ids nobody captured: items new to this version whose names the donor
        // can't know. Keep the array dense so full-table walkers never hit
        // undefined; placeholders carry unique names to avoid byName collisions.
        for (let i = 0; i < items.length; i++) {
          if (items[i] == null) items[i] = { id: i, name: `unknown_${i}`, displayName: `Unknown ${i}`, stackSize: 64 }
        }
        d.items = items
      }
      if (entCount) {
        const donorEnts = Object.values(d.entities)
        const captured = new Set(Object.keys(ids.entities))
        const entities = []
        for (const [name, id] of Object.entries(ids.entities)) {
          const donor = donorEnts.find(e => e && e.name === name)
          entities[id] = { ...(donor || { displayName: name, width: 0.6, height: 1.8, type: 'other', category: 'unknown' }), id, internalId: id, name }
        }
        for (const e of donorEnts) {
          if (!e || captured.has(e.name)) continue
          const id = e.id
          if (id == null || entities[id] != null) {
            // evicted from its donor slot by a captured entity: park at the
            // end (registries grow by appending) so byName lookups survive
            // until the mapper captures the true id
            if (!entities.some(x => x && x.name === e.name)) {
              const slot = entities.length
              entities[slot] = { ...e, id: slot, internalId: slot }
            }
            continue
          }
          entities[id] = { ...e, id, internalId: id }
        }
        for (let i = 0; i < entities.length; i++) {
          if (entities[i] == null) entities[i] = { id: i, internalId: i, name: `unknown_${i}`, displayName: `Unknown ${i}`, width: 0.6, height: 1.8, type: 'other', category: 'unknown' }
        }
        d.entities = entities
      }
      console.log(`NOTE: remapped ${itemCount} item / ${entCount} entity ids for ${VERSION} from static_ids capture`)
    } else {
      console.log(`NOTE: static_ids capture for ${VERSION} is empty; keeping donor ids`)
    }
  }
  // Wire-level drift of 26.2 vs the donor 26.1 packet definitions, observed
  // live (see captures_26.2.json). minecraft-protocol compiles and caches one
  // parser per version string, so patching the shared protocol object here —
  // before mineflayer is required — is sufficient. The type trees are shared
  // with the donor entry, so never mutate in place: copy-on-write along the
  // path being changed.
  if (VERSION === '26.2' && data.pc[VERSION].protocol) {
    let proto = data.pc[VERSION].protocol
    const setPacket = (state, dir, name, tuple) => {
      proto = { ...proto, [state]: { ...proto[state], [dir]: { ...proto[state][dir], types: { ...proto[state][dir].types, [name]: tuple } } } }
    }
    // login success and join game carry opaque trailing bytes the donor
    // definitions don't model (constant per server, contents unknown).
    // restBuffer swallows whatever remains so frames parse fully instead of
    // logging a "Chunk size is X but only Y was read" warning on every join;
    // it also tolerates the field being absent again in a later revision.
    const withRestTail = (tuple) => (tuple && tuple[0] === 'container' && !tuple[1].some(f => f.name === 'extra'))
      ? ['container', [...tuple[1], { name: 'extra', type: 'restBuffer' }]]
      : tuple
    const types = (state, dir) => (proto[state] && proto[state][dir] && proto[state][dir].types) || {}
    if (types('login', 'toClient').packet_success) setPacket('login', 'toClient', 'packet_success', withRestTail(types('login', 'toClient').packet_success))
    if (types('play', 'toClient').packet_login) setPacket('play', 'toClient', 'packet_login', withRestTail(types('play', 'toClient').packet_login))
    // explosion: 26.2 dropped the ItemSoundHolder `sound` field that sat
    // between explosionParticle and blockParticles. With the donor definition
    // the parser over-reads the array count as a sound registry id, then dies
    // inside blockParticles and the whole packet is silently discarded —
    // mineflayer never sees explosion knockback. Remove the field.
    const explosion = types('play', 'toClient').packet_explosion
    if (explosion && explosion[0] === 'container' && explosion[1].some(f => f.name === 'sound')) {
      setPacket('play', 'toClient', 'packet_explosion', ['container', explosion[1].filter(f => f.name !== 'sound')])
    }
    data.pc[VERSION].protocol = proto
    console.log(`NOTE: patched 26.2 protocol drift (success/login rest-tail, explosion without sound)`)
  }
  // Mineflayer hardcodes its tested version list and rejects newer protocols in
  // lib/loader.js; extend it before mineflayer's loader reads it.
  const mf = require('mineflayer/lib/version')
  if (!mf.testedVersions.includes(VERSION)) {
    mf.testedVersions.push(VERSION)
    mf.latestSupportedVersion = VERSION
  }
}
