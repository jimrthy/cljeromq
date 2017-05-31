#! /usr/bin/env python3

import czmq

def main():
    cli = czmq.Zsock.new_client(b'tcp://127.0.0.1:9122')
    assert(cli)
    s = b'The quick red fox, etc'
    msg = czmq.Zframe(s, len(s))
    czmq.Zframe.send(msg, cli, 0)
    echo = czmq.Zframe.recv(cli)
    print('Received: {}'.format(echo.strdup()))

if __name__ == '__main__':
    main()
