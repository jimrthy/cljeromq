#! /usr/bin/env python

import zmq

class EncryptedPushTester(object):
    def __init__(self, server_key):
        self.url = "tcp://127.0.0.1:2111"
        self.ctx = zmq.Context(2)
        self.pusher = self.ctx.socket(zmq.PUSH)
        self.keys = zmq.curve_keypair()
        #self.pusher.CURVE_PUBLICKEY = self.keys[0]
        #self.pusher.CURVE_SECRETKEY = self.keys[1]
        #self.pusher.CURVE_SERVERKEY = server_key
        self.pusher.connect(self.url)

    def run(self):
        try:
            print "Begin pushing"
            [self.pusher.send("Hello! " + str(i)) for i in range(10)]
            print "Pushes sent"
        finally:
            print "Disconnecting"
            self.pusher.disconnect(self.url)
            self.pusher.LINGER=0
            print "Closing"
            self.pusher.close()
            print "Terminating Context"
            self.ctx.term()

def main(argv):
    tester = EncryptedPushTester(argv[1])
    tester.run()

if __name__ == "__main__":
    import sys
    print "Called with args:\n" + str(sys.argv)
    main(["pusher.py", "nvXGXG:{=4&>pXCRSuk<p#Hv)&4w$)g{pdk2NCJx"])
