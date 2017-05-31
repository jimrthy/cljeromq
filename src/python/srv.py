#! /usr/bin/env python3

try:
    import czmq
except ImportError:
    import ctypes
    from ctypes.util import find_library

    libcpath = find_library("c")
    libc = ctypes.cdll.LoadLibrary(libcpath)

def main():
    srv = czmq.Zsock.new_server(b'tcp://127.0.0.1:9121')
    assert(srv)
    msg = czmq.Zframe.recv(srv)
    print('Incoming message routing ID: {}'.format(msg.routing_id()))
    czmq.Zframe.send(msg, srv, 0)

if __name__ == '__main__':
    main()
