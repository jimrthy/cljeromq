#! /bin/sh

# This works for me because these folders are where I set my --prefix
# before I ran ./configure for the entire 0mq toolchain.
# Your paths almost definitely should be something different and make
# sense to you

LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$HOME/environs/px_czmq/lib jython \
-Dpython.path=$HOME/projects/3rd/zmq/czmq/bindings/jni/build/libs/czmq-jni-4.0.3.jar \
-Djava.library.path=$HOME/environs/px_czmq/lib sample.py
