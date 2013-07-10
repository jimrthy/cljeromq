# cljeromq

My incarnation of clojure bindings for zeromq.

Or something in that vicinity. It gets complicated.

For now, this is really trying to find a balance between
my work on frereth and a presentation for the Austin
Clojure Group (yes, that should be a link).

## Installation

This is really sort of a <s>bitch</s> <s>pain in the ass</s>
painful exercise in patience.

I had to get advice from the #clojure irc channel to get
this working. Probably because I'm such a n00b.

Then again, it involves native interop, so maybe I deserve
a little slack.

Surely I jotted down at least a few notes somewhere.

## Usage

After you get the native dependencies installed, you
really should follow along with the official guide:
http://zguide.zeromq.org/page:all

Actually, if you have any interest at all, just buy it.
It probably doesn't qualify as a classic, but it's pretty
close.

For a tech book, it's extremely well-written and readable.

In general:

I need to put this library on clojars. Then I need to document
the basic ideas.

1) Create a context in each process
2) Create sockets
  a) Req/Rep -- not really
  b) Sub/Pub
  c) Push/Pull
  d) Router/Dealer
  e) Pair -- well, maybe
3) Profit.


## Options

FIXME: listing of options this app accepts.

## Examples

This really fits in with the Guide. Which I should probably try
to merge request into.

### Bugs

How could there possibly be anything wrong?

### Alternatives

* jeromq
* storm
* netty-zmtp

## License

Copyright © 2013 James Gatannah

Distributed under the LGPL. What do I need to do to make that
legal?
