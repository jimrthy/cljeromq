import org.zeromq.czmq as czmq

try:
    req = czmq.Zsock.newReq('@inproc://abcde')
except TypeError:
    print 'Specific socket constructors are not static'
else:
    print 'It would be nice if the JVM version worked this way'

# But we can create a garbage socket in order to create
# the ones we actually care about.
# Well, we could have created one that matters first
# and then bound/connected it manually.
# But that seems to defeat the purpose of having the
# direct constructors at all.
factory = czmq.Zsock(1)
s = factory.newServer('@inproc://abcde')
c = factory.newClient('>inproc://abcde')

# OK, great. Now we're ready to go.

req = 'getting somewhere'
f = czmq.Zframe(req, len(req))

# This is the way it really should work
try:
    f.send(c.self, 0)
except TypeError as ex:
    # 1st arg can't be coerced to long
    print ex
else:
    # This is one of the failures I ran
    # across in clojure
    print "I didn't expect that to work"

success = f.send(c.self, 0)
assert success == 0

# This is weird, but usable
server_factory = czmq.Zframe('garbage', 7)
incoming = server_factory.recv(s.self)
body = incoming.strdup()
assert body == req
# Because router/dealer
client_id = incoming.routingId()
del incoming
print 'Request from: {}'.format(client_id)

rsp_body = body.upper()
rsp = czmq.Zframe(rsp_body, len(rsp_body))
rsp.setRoutingId(client_id)
success = rsp.send(s.self, 0)
assert success == 0

rcvd = f.recv(c.self)
assert rcvd
assert rcvd.strdup() == rsp_body
del f

print 'Hands shook'
# Despite these calls, which shouldn't be needed,
# I'm getting errors about dangling sockets.
# This would be more worrisome if this were
# something more dynamic
del c
del s
del factory
