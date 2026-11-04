#!/usr/bin/env python3
"""临时冒烟测试：启动 Folia，用真实协议登录两次，检查扩容、存档与新增槽位。"""

import hashlib
import json
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
import zlib
from pathlib import Path

USER_AGENT = 'enderlink-smoke-test (https://github.com/PluginsKers/enderlink)'
NAME = 'EnderLinkTest'
PORT = 25565
PROPERTIES = '''\
online-mode=false
server-ip=127.0.0.1
server-port=25565
level-type=minecraft\\:flat
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
max-players=4
white-list=false
enforce-whitelist=false
'''
FORBIDDEN = re.compile(r'com\.eyreland\.enderlink|preflight failed|Ender chest failure|(WARN|ERROR)\].*\[enderlink\]')


class Failure(Exception):
    pass


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response:
        return response.read()


def download(url, path, algorithm, digest):
    data = fetch(url)
    if hashlib.new(algorithm, data).hexdigest() != digest:
        raise Failure(f'{url} 校验失败')
    path.write_bytes(data)


def varint(value):
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        byte, value = value & 0x7F, value >> 7
        out.append(byte | 0x80 if value else byte)
        if not value:
            return bytes(out)


def text(value):
    data = value.encode()
    return varint(len(data)) + data


def offline_uuid(name):
    digest = bytearray(hashlib.md5(f'OfflinePlayer:{name}'.encode()).digest())
    digest[6] = digest[6] & 0x0F | 0x30
    digest[8] = digest[8] & 0x3F | 0x80
    return bytes(digest)


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, size):
        chunk = self.data[self.pos:self.pos + size]
        if len(chunk) != size:
            raise EOFError('truncated packet')
        self.pos += size
        return chunk

    def varint(self):
        result = shift = 0
        while True:
            byte = self.take(1)[0]
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result - (1 << 32) if result >= 1 << 31 else result
            shift += 7

    def text(self):
        return self.take(self.varint()).decode()

    def rest(self):
        return self.data[self.pos:]


class Protocol:
    """数据包编号取自同版本原版服务端生成的 packets.json，避免手写各版本编号。"""

    def __init__(self, report):
        self.version = None
        self.ids, self.names = {}, {}
        for state, flows in report.items():
            for flow, packets in flows.items():
                for name, entry in packets.items():
                    name = name.removeprefix('minecraft:')
                    self.ids[state, flow, name] = entry['protocol_id']
                    if flow == 'clientbound':
                        self.names[state, entry['protocol_id']] = name

    def serverbound(self, state, name):
        return self.ids[state, 'serverbound', name]

    def name(self, state, packet):
        return self.names.get((state, packet), f'0x{packet:02x}')


class Connection:
    def __init__(self):
        self.sock = socket.create_connection(('127.0.0.1', PORT), timeout=180)
        self.threshold = -1
        self.lock = threading.Lock()

    def exact(self, size):
        data = bytearray()
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise EOFError('connection closed')
            data += chunk
        return bytes(data)

    def read(self):
        length = shift = 0
        while True:
            byte = self.exact(1)[0]
            length |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
            shift += 7
        reader = Reader(self.exact(length))
        if self.threshold >= 0:
            size = reader.varint()
            reader = Reader(zlib.decompress(reader.rest()) if size else reader.rest())
        return reader.varint(), reader

    def send(self, packet, payload=b''):
        data = varint(packet) + payload
        if self.threshold >= 0:
            data = varint(len(data)) + zlib.compress(data) if len(data) >= self.threshold else varint(0) + data
        with self.lock:
            self.sock.sendall(varint(len(data)) + data)

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


def handshake(version, intent):
    return varint(version) + text('127.0.0.1') + struct.pack('>H', PORT) + varint(intent)


def query_version(protocol):
    connection = Connection()
    try:
        connection.send(protocol.serverbound('handshake', 'intention'), handshake(0, 1))
        connection.send(protocol.serverbound('status', 'status_request'))
        _, reader = connection.read()
        return json.loads(reader.text())['version']['protocol']
    finally:
        connection.close()


class Client:
    def __init__(self, protocol):
        self.protocol = protocol
        self.connection = Connection()
        self.joined = threading.Event()
        self.opened = threading.Event()
        self.container = self.state_id = 0
        self.stopping = False
        self.failure = None
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        try:
            self.loop()
        except Exception as failure:
            if not self.stopping:
                self.failure = failure
                print(f'client: {failure!r}', flush=True)

    def loop(self):
        protocol, connection = self.protocol, self.connection
        connection.send(protocol.serverbound('handshake', 'intention'), handshake(protocol.version, 2))
        connection.send(protocol.serverbound('login', 'hello'), text(NAME) + offline_uuid(NAME))
        state = 'login'

        def reply(name, payload=b''):
            connection.send(protocol.serverbound(state, name), payload)

        while True:
            packet, reader = connection.read()
            name = protocol.name(state, packet)
            if name in ('login_disconnect', 'disconnect'):
                raise Failure(f'{state} 阶段被断开: {reader.rest()!r}')
            if state == 'login':
                if name == 'hello':
                    raise Failure('服务端要求加密，online-mode 必须为 false')
                if name == 'login_compression':
                    connection.threshold = reader.varint()
                elif name == 'custom_query':
                    reply('custom_query_answer', varint(reader.varint()) + b'\0')
                elif name == 'cookie_request':
                    reply('cookie_response', text(reader.text()) + b'\0')
                elif name in ('login_finished', 'game_profile'):
                    reply('login_acknowledged')
                    state = 'configuration'
            elif state == 'configuration':
                if name == 'select_known_packs':
                    reply('select_known_packs', reader.rest())
                elif name == 'keep_alive':
                    reply('keep_alive', reader.take(8))
                elif name == 'ping':
                    reply('pong', reader.take(4))
                elif name == 'cookie_request':
                    reply('cookie_response', text(reader.text()) + b'\0')
                elif name == 'code_of_conduct':
                    reply('accept_code_of_conduct')
                elif name == 'finish_configuration':
                    reply('finish_configuration')
                    state = 'play'
            elif name == 'login':
                self.joined.set()
            elif name == 'open_screen':
                self.container = reader.varint()
                self.opened.set()
            elif name in ('container_set_content', 'container_set_slot'):
                if reader.varint() == self.container:
                    self.state_id = reader.varint()
            elif name == 'keep_alive':
                reply('keep_alive', reader.take(8))
            elif name == 'ping':
                reply('pong', reader.take(4))
            elif name == 'start_configuration':
                raise Failure('服务端意外要求重新配置')

    def wait_joined(self, timeout=180):
        deadline = time.monotonic() + timeout
        while not self.joined.wait(1):
            if self.failure or not self.thread.is_alive():
                raise Failure(f'登录失败: {self.failure!r}')
            if time.monotonic() > deadline:
                raise Failure('等待进入游戏超时')

    def command(self, command):
        self.connection.send(self.protocol.serverbound('play', 'chat_command'), text(command))

    def wait_opened(self, timeout=30):
        if not self.opened.wait(timeout):
            raise Failure('等待打开末影箱超时')

    def click(self, slot):
        # 左键拾取或放下；不预测槽位变化，服务端会以自身状态为准并重新同步。
        payload = varint(self.container) + varint(self.state_id) + struct.pack('>hb', slot, 0) + varint(0) + varint(0) + b'\0'
        self.connection.send(self.protocol.serverbound('play', 'container_click'), payload)

    def close_container(self):
        self.connection.send(self.protocol.serverbound('play', 'container_close'), varint(self.container))
        self.opened.clear()

    def close(self):
        self.stopping = True
        self.connection.close()
        self.thread.join(10)


class Server:
    def __init__(self, java, directory):
        self.process = subprocess.Popen(
            [java, '-Xms1G', '-Xmx2G', '-jar', 'folia.jar', '--nogui'], cwd=directory,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', bufsize=1,
        )
        self.lines = []
        self.changed = threading.Condition()
        self.reader = threading.Thread(target=self.pump, daemon=True)
        self.reader.start()

    def pump(self):
        for line in self.process.stdout:
            print(line, end='', flush=True)
            with self.changed:
                self.lines.append(line.rstrip('\n'))
                self.changed.notify_all()
        with self.changed:
            self.changed.notify_all()

    def mark(self):
        with self.changed:
            return len(self.lines)

    def expect(self, pattern, since=0, timeout=60):
        regex = re.compile(pattern)
        deadline = time.monotonic() + timeout
        with self.changed:
            while not any(regex.search(line) for line in self.lines[since:]):
                if not self.reader.is_alive():
                    raise Failure(f'服务端已退出，未出现 {pattern!r}')
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise Failure(f'等待 {pattern!r} 超时')
                self.changed.wait(min(remaining, 1))

    def run(self, command, pattern):
        since = self.mark()
        print(f'> {command}', flush=True)
        self.process.stdin.write(command + '\n')
        self.process.stdin.flush()
        self.expect(pattern, since)

    def stop(self):
        if self.process.poll() is None:
            try:
                self.process.stdin.write('stop\n')
                self.process.stdin.flush()
                self.process.wait(180)
            except (OSError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait()
        self.reader.join(10)
        return self.process.returncode


def packets(minecraft, java, directory):
    directory.mkdir(parents=True)
    manifest = json.loads(fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
    version = json.loads(fetch(next(entry['url'] for entry in manifest['versions'] if entry['id'] == minecraft)))
    server = version['downloads']['server']
    download(server['url'], directory / 'server.jar', 'sha1', server['sha1'])
    subprocess.run(
        [java, '-DbundlerMainClass=net.minecraft.data.Main', '-jar', 'server.jar', '--reports', '--output', 'generated'],
        cwd=directory, check=True, timeout=900,
    )
    return Protocol(json.loads((directory / 'generated' / 'reports' / 'packets.json').read_text()))


def session(server, protocol, steps):
    joined, left = rf'{NAME} joined the game', rf'{NAME} left the game'
    since = server.mark()
    client = Client(protocol)
    try:
        client.wait_joined()
        server.expect(joined, since)
        steps(client)
    finally:
        since = server.mark()
        client.close()
    server.expect(left, since)
    time.sleep(3)


def player_command(server, client, command, pattern):
    since = server.mark()
    print(f'{NAME}> /{command}', flush=True)
    client.command(command)
    server.expect(pattern, since)


def first_login(server, client):
    # 新玩家从 27 格起步；扩到 54 格后通过真实界面把物品放进最后一个新增槽位。
    server.run(f'op {NAME}', rf'Made {NAME} a server operator')
    server.run('enderlink reload', r'Language files reloaded\.')
    server.run(f'enderlink size {NAME}', rf"{NAME}'s ender chest has 27 slots\.")
    server.run(f'enderlink size {NAME} 54', rf"Set {NAME}'s ender chest capacity to 54 slots\.")
    server.run(f'give {NAME} minecraft:diamond 5', rf'Gave 5 \[Diamond\] to {NAME}')
    time.sleep(2)
    print(f'{NAME}> /enderlink', flush=True)
    client.command('enderlink')
    client.wait_opened()
    time.sleep(1)
    # 六行箱子界面中 0-53 为末影箱，81 为快捷栏第一格。
    client.click(81)
    time.sleep(0.5)
    client.click(53)
    time.sleep(0.5)
    client.close_container()
    time.sleep(1)
    server.run(f'enderlink size {NAME} 27', r'Remove items from the slots beyond the requested capacity first\.')


def second_login(server, client):
    # 重新登录后容量与新增槽位中的物品都必须保留，且不能缩容丢弃物品。
    server.run(f'enderlink size {NAME}', rf"{NAME}'s ender chest has 54 slots\.")
    server.run(f'enderlink size {NAME} 27', r'Remove items from the slots beyond the requested capacity first\.')
    player_command(
        server, client, 'execute if items entity @s enderchest.53 minecraft:diamond run say enderlink-slot-53-kept',
        rf'\[{NAME}\] enderlink-slot-53-kept',
    )


def main():
    minecraft, java, plugin = sys.argv[1], sys.argv[2], Path(sys.argv[3]).resolve()
    root = Path('smoke-run').resolve()
    shutil.rmtree(root, ignore_errors=True)
    directory = root / 'server'
    (directory / 'plugins').mkdir(parents=True)
    shutil.copy(plugin, directory / 'plugins')

    build = json.loads(fetch(f'https://fill.papermc.io/v3/projects/folia/versions/{minecraft}/builds/latest'))
    jar = build['downloads']['server:default']
    download(jar['url'], directory / 'folia.jar', 'sha256', jar['checksums']['sha256'])
    print(f'Folia {minecraft} build {build["id"]} ({build.get("channel")})', flush=True)
    protocol = packets(minecraft, java, root / 'vanilla')

    (directory / 'eula.txt').write_text('eula=true\n')
    (directory / 'server.properties').write_text(PROPERTIES)
    server = Server(java, directory)
    try:
        server.expect(r'Done \(', timeout=900)
        protocol.version = query_version(protocol)
        print(f'Protocol {protocol.version}', flush=True)
        session(server, protocol, lambda client: first_login(server, client))
        session(server, protocol, lambda client: second_login(server, client))
    finally:
        code = server.stop()
    problems = [line for line in server.lines if FORBIDDEN.search(line)]
    if problems:
        raise Failure('日志中出现插件错误:\n' + '\n'.join(problems))
    if code != 0:
        raise Failure(f'服务端退出码 {code}')
    print('Smoke test passed', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Failure as failure:
        print(f'::error::{failure}', flush=True)
        sys.exit(1)
