Get into REPL:

    LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$PATH_TO_CZMQ jython \
    -Dpython.path=$PATH_TO_CZMQ_JNI_JAR -Djava.library.path=$PATH_TO_CZMQ

Then you can run

    import org.zeromq.czmq as czmq

Although that doesn't demonstrate much.

If those details are set up incorrectly, you won't hit problems until
you try to access something like

    dir(czmq.Zsock)
