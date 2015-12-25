# cljeromq

My incarnation of clojure bindings for zeromq.

Or something along those lines. It gets complicated.

## Installation

Clone this repo and run

    lein install

I should probably publish it on clojars, but it isn't exactly
stable.

## Usage

In general:

1) Create a context in each process
2) Create sockets
  Read the 0mq guide at http://zguide.zeromq.org/page:all
  My $0.02 summary:
  a) Req/Rep -- not really
  b) Sub/Pub -- For multicasting
  c) Push/Pull -- round-robin
  d) Router/Dealer - Pure asynchronous on 
  e) Pair -- great for inproc things like thread coordination
3) Build your messaging protocol.
  Again, read the guide.
  There's a zproto library that might be worth using. I've
  never had a chance to test-drive it, but I've read a lot
  of good things about it.


## Options

FIXME: listing of options this app accepts.

## Examples

This really fits in with the Guide.

### Bugs

How could there possibly be anything wrong?

### Alternatives

* jeromq
* storm
* netty-zmtp

## License

Copyright © 2013-2016 James Gatannah

I want to give this the most permissive license possible.

It's basically a wrapper around zmq-jni, which uses the Mozilla
Public License.

That's really just a wrapper over 0mq, which is LGPL, with an
exception that I think makes this legal.

Whichever license this actually has to used based on those
constraints must also be compatible with the Eclipse Public
License, since that's pretty much every clojure library on
the planet.

I'm not a lawyer. I want you to use this library. If you do,
please let me know so we can figure out how to work out the
rough edges.

Whichever license winds up controlling this thing, it's going
to have clauses about absolutely no warranty and you not
suing me.

This is just part of a project that I'm dabbling with in my
spare time that might possibly be useful to others.


