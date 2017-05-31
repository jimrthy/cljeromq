#! /usr/bin/env python3

import socket
# TODO: Should use this for everything
import struct
import threading
import time


# Low-level helpers

def extract_props(raw):
    result = {}
    n = 0
    while n < len(raw):
        k_len = raw[n]
        n += 1
        k = raw[n:n+k_len]
        n += k_len
        v_len_bytes = raw[n:n+4]
        n += 4
        v_len = struct.unpack("!i", v_len_bytes)[0]
        v = raw[n:n+v_len]
        n += v_len
        result[k] = v
    assert n == len(raw)
    return result


def recv_n(sock, n):
    """ Read n bytes from sock """
    chunks = []
    bytes_rcvd = 0
    #import pdb; pdb.set_trace()
    while bytes_rcvd < n:
        chunk = sock.recv(min(n-bytes_rcvd, n))
        if chunk == b'':
            raise RuntimeError('socket connection broken')
        chunks.append(chunk)
        bytes_rcvd += len(chunk)
        print('Received {}/{} bytes'.format(bytes_rcvd, n))
    return b''.join(chunks)


def send(sock, bs):
    sent = 0
    while sent < len(bs):
        sent += sock.send(bs[sent:])


# Actual protocol


def send_preamble(sock):
    """ Send the 1st 11 bytes """
    preamble = [0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0x7f, 0x3]
    bs = bytes(preamble)
    send(sock, bs)


def finish_sending_greeting(sock):
    # 1 for the minor version
    # 20 NULL-padded bytes for the security mechanism
    # 0 for not-server
    # 31 filler bytes
    remainder = [b'\x01', b'NULL', b'\x00'*16,
                 b'\x00', b'\x00' * 31]
    bs = b''.join(remainder)
    send(sock, bs)


def finish_receiving_greeting(sock):
    return recv_n(sock, 53)


def recv_preamble(sock):
    return recv_n(sock, 11)


def recv_command(sock):
    flag = recv_n(sock, 1)[0]
    if flag == 4:
        size = recv_n(sock, 1)[0]
    elif flag == 6:
        size_bytes = recv_n(sock, 8)
        size = struct.unpack("!q", size_bytes)[0]
    print('Command length: {}'.format(size))

    name_len = recv_n(sock, 1)[0]
    name = recv_n(sock, name_len)
    data = recv_n(sock, size - name_len - 1)
    return name, data


def recv_message(sock):
    flags = recv_n(sock, 1)[0]
    if flags & 0x01:
        raise RuntimeError('CLISRV does not support multiple frames')
    if flags == 0x00:
        size = recv_n(sock, 1)[0]
    elif flags == 0x02:
        size_bytes = recv_n(sock, 8)
        size = struct.unpack("!q", size_bytes)[0]
    return recv_n(sock, size)


def respond_ready(sock):
    fmt = '!bbb5sb11sl6s'
    msg = struct.pack(fmt, 0x04, 28, 0x05, b'READY',
                      11, b'Socket-Type', 6, b'SERVER')
    send(sock, msg)


# Top-level implementation


def handle(sock):
    """
    Background thread that does the work
    """
    try:
        print('Sending preamble...')
        send_preamble(sock)
        print('Preamble sent')

        peer_preamble = recv_preamble(sock)
        if peer_preamble[-1] < 3:
            raise RuntimeError('Not coping w/ old peers')

        finish_sending_greeting(sock)
        greet = finish_receiving_greeting(sock)
        print(('Major: {}\nMinor: {}\n'
               'Server: {}\n'
               'Security Mechanism: {}').format(peer_preamble[-1],
                                                greet[0],
                                                greet[22],
                                                greet[1:21]))

        name, props = recv_command(sock)
        if name != b'READY':
            raise RuntimeError('Unexpected command name: {}'.format(name))
        extracted = extract_props(props)
        print('Extracted properties: {}'.format(extracted))
        peer_type = extracted[b'Socket-Type']
        if peer_type != b'CLIENT':
            raise RuntimeError('Discarding attempted {} connection'.format(peer_type))
        respond_ready(sock)

        incoming = recv_message(sock)
        print('Echoing: {}'.format(incoming))
        # I'm not seeing this back at the client.
        # Q: What am I missing?
        # The routing ID is supposed to be part of the metadata associated
        # with the frame I just received.
        # Q: How does that get handled?
        send(sock, incoming)
        # Q: What's the proper way to close out the connection?

    finally:
        sock.shutdown(socket.SHUT_RDWR)
        sock.close()


def main():
    # Start with these:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(('127.0.0.1', 9122))
    srv.listen(5)
    # Instead of this:
    #srv = czmq.Zsock.new_server(b'tcp://127.0.0.1:9121')

    # And then spawn handler threads
    while True:
        (clientsocket, address) = srv.accept()
        print('Connection accepted at {}. Spawning handler thread'.format(time.time()))
        handler = threading.Thread(target=handle, args=(clientsocket,))
        handler.run()


if __name__ == '__main__':
    main()
