// Minecraft 26.2 的本机离线测试客户端，不解析世界或物品组件。
import net from 'node:net';
import assert from 'node:assert/strict';
const port = Number(process.argv[2] ?? 25707);
const protocol = 776;
const username = 'EnderProbe';
const successMarker = 'ENDER_TEST_PASS';
setTimeout(() => { console.error('Native test timed out'); process.exit(1); }, 300000).unref();
let expectedBlockEnder;
let expectedCreativeEnder;
let creativeEnderActive = false;
const blockEnderChecks = new Set();
const creativeEnderChecks = new Set();
const enderTitleChecks = new Set();
function vint(number) {
  const bytes = [];
  do { let byte = number & 127; number >>>= 7; bytes.push(byte | (number ? 128 : 0)); } while (number);
  return Buffer.from(bytes);
}
function readInt(bytes, offset = 0) {
  let value = 0;
  for (let index = 0; index < 5; index++) {
    if (offset + index >= bytes.length) return null;
    const byte = bytes[offset + index];
    value |= (byte & 127) << (index * 7);
    if (!(byte & 128)) return [value, index + 1];
  }
  throw new Error('Invalid VarInt');
}
const string = value => Buffer.concat([vint(Buffer.byteLength(value)), Buffer.from(value)]);
function connect() {
  let state = 'login';
  let buffer = Buffer.alloc(0);
  let expectedEnderTitle;
  let advertised = false;
  const socket = net.createConnection({ host: '127.0.0.1', port });
  const send = (id, ...data) => {
    const packet = Buffer.concat([vint(id), ...data]);
    socket.write(Buffer.concat([vint(packet.length), packet]));
  };
  socket.on('connect', () => {
    const portBytes = Buffer.alloc(2); portBytes.writeUInt16BE(port);
    send(0, vint(protocol), string('127.0.0.1'), portBytes, vint(2));
    send(0, string(username), Buffer.alloc(16));
  });
  socket.on('data', chunk => {
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
      const header = readInt(buffer);
      if (!header || buffer.length < header[0] + header[1]) return;
      const packet = buffer.subarray(header[1], header[1] + header[0]);
      buffer = buffer.subarray(header[1] + header[0]);
      const [id, width] = readInt(packet);
      const data = packet.subarray(width);
      if (state === 'login') {
        if (id === 0) throw new Error(`Login rejected: ${data.toString()}`);
        assert.notEqual(id, 3, 'Set network-compression-threshold=-1');
        if (id === 2) { send(3); state = 'configuration'; }
      } else if (state === 'configuration') {
        if (id === 2) throw new Error(`Configuration rejected: ${data.toString()}`);
        if (id === 14) send(7, vint(0));
        if (id === 4) send(4, data);
        if (id === 3) { send(3); state = 'play'; send(44); }
      } else {
        const text = data.toString();
        if (text.includes('minecraft:register') && text.includes('enderlink:open_v1')) advertised = true;
        const wire = text.match(/NATIVE_WIRE:([A-Za-z0-9+/=]+):END/);
        if (wire) { assert.ok(advertised, 'Paper advertises the open channel'); socket.write(Buffer.from(wire[1], 'base64')); }
        if (successMarker === 'ENDER_TEST_PASS') {
          const block = text.match(/ENDER_BLOCK_EXPECT:(SURVIVAL|CREATIVE):(MAIN_HAND|OFF_HAND):([A-Za-z0-9+/=]+):END/);
          if (block) {
            assert.equal(expectedBlockEnder, undefined);
            expectedBlockEnder = { label: `${block[1]}:${block[2]}`,
              slot: block[2] === 'MAIN_HAND' ? 36 : 45, item: Buffer.from(block[3], 'base64'), synced: false, hinted: false };
          }
          if (expectedBlockEnder && id === 20) {
            const [window, windowWidth] = readInt(data);
            const [, stateWidth] = readInt(data, windowWidth);
            const slot = data.readInt16BE(windowWidth + stateWidth);
            if (window === 0 && slot === expectedBlockEnder.slot) {
              const item = data.subarray(windowWidth + stateWidth + 2);
              assert.ok(item.equals(expectedBlockEnder.item), 'cancelled placement restores the held item');
              expectedBlockEnder.synced = true;
            }
          }
          if (expectedBlockEnder && id === 59) {
            assert.fail('legacy block use must not open a menu');
          }
          if (text.includes('/enderlink')) {
            if (expectedBlockEnder) expectedBlockEnder.hinted = true;
            if (expectedCreativeEnder) expectedCreativeEnder.hinted = true;
          }
          const blockPass = text.match(/ENDER_BLOCK_CASE_PASS:(SURVIVAL|CREATIVE):(MAIN_HAND|OFF_HAND):END/);
          if (blockPass) {
            const label = `${blockPass[1]}:${blockPass[2]}`;
            assert.equal(expectedBlockEnder?.label, label);
            assert.ok(expectedBlockEnder.synced, 'cancelled placement must resync the held slot');
            assert.ok(expectedBlockEnder.hinted, 'legacy block use teaches the command');
            assert.ok(!blockEnderChecks.has(label));
            blockEnderChecks.add(label);
            expectedBlockEnder = undefined;
            console.log(`ENDER_BLOCK_CLIENT_PASS ${label}`);
          }
          if (data.includes(Buffer.from('ENDER_BLOCK_CASES_DONE')) || data.includes(Buffer.from(successMarker))) {
            assert.equal(blockEnderChecks.size, 4);
            assert.equal(expectedBlockEnder, undefined);
          }
          const expected = data.toString().match(/CREATIVE_ENDER_EXPECT:(MAIN_HAND|OFF_HAND):(27|36|45|54):END/);
          if (expected) { creativeEnderActive = true; expectedCreativeEnder = { hand: expected[1], size: Number(expected[2]), hinted: false }; }
          if (creativeEnderActive && id === 59) {
            assert.fail('legacy creative use must not open a menu');
          }
          const creativePass = text.match(/CREATIVE_ENDER_CASE_PASS:(MAIN_HAND|OFF_HAND):(27|36|45|54):END/);
          if (creativePass) {
            assert.ok(expectedCreativeEnder?.hinted, 'legacy creative use teaches the command');
            const { hand, size } = expectedCreativeEnder;
            assert.equal(hand, creativePass[1]);
            assert.equal(size, Number(creativePass[2]));
            assert.ok(!creativeEnderChecks.has(`${hand}:${size}`));
            creativeEnderChecks.add(`${hand}:${size}`);
            expectedCreativeEnder = undefined;
            console.log(`CREATIVE_ENDER_CLIENT_PASS hand=${hand} rows=${size / 9}`);
          }
          if (data.includes(Buffer.from('CREATIVE_ENDER_CASES_DONE')) || data.includes(Buffer.from(successMarker))) {
            assert.equal(creativeEnderChecks.size, 8);
            assert.equal(expectedCreativeEnder, undefined);
            creativeEnderActive = false;
          }
        }
        if (successMarker === 'ENDER_TEST_PASS') {
          const expected = text.match(/ENDER_TITLE_EXPECT:(block|command):(27|36|45|54):END/);
          if (expected) expectedEnderTitle = { source: expected[1], size: Number(expected[2]) };
          if (text.includes('ENDER_TITLE_CASES_DONE') || text.includes(successMarker)) assert.equal(enderTitleChecks.size, 8);
        }
        if (id === 44) send(28, data);
        if (id === 72) send(0, vint(readInt(data)[0]));
        if (id === 11) { const rate = Buffer.alloc(4); rate.writeFloatBE(10); send(11, rate); }
        if (id === 59) {
          const [, windowWidth] = readInt(data);
          const [menu, menuWidth] = readInt(data, windowWidth);
          if (successMarker === 'ENDER_TEST_PASS' && menu >= 2 && menu <= 5) {
            const title = data.subarray(windowWidth + menuWidth);
            assert.ok(title.includes(Buffer.from('translate')) && title.includes(Buffer.from('container.enderchest')), 'ender chest packet must carry client translation');
            assert.ok(!title.includes(Buffer.from('Ender Chest')), 'ender chest packet must not contain fixed English title');
            if (expectedEnderTitle) {
              const { source, size } = expectedEnderTitle;
              assert.equal(menu, size / 9 - 1);
              assert.ok(!enderTitleChecks.has(`${source}:${size}`));
              enderTitleChecks.add(`${source}:${size}`);
              console.log(`ENDER_TITLE_CLIENT_PASS source=${source} rows=${size / 9} key=container.enderchest`);
              expectedEnderTitle = undefined;
            }
          }
          assert.ok(menu >= 2 && menu <= 5, `Unexpected ender menu ${menu}`);
          console.log(`menu rows=${menu + 1}`);
        }
        if (text.includes(successMarker)) { console.log(successMarker); process.exit(0); }
        if (id === 32) socket.end();
      }
    }
  });
  socket.on('error', error => { if (error.code !== 'ECONNREFUSED' && error.code !== 'ECONNRESET') throw error; });
  socket.on('close', () => setTimeout(connect, 1500));
}
connect();
