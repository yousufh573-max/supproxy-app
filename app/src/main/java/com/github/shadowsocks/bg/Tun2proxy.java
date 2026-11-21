package com.github.shadowsocks.bg;

/** JNI entry points exported by the pinned tun2proxy Android library. */
public final class Tun2proxy {
    static {
        System.loadLibrary("tun2proxy");
    }

    private Tun2proxy() {}

    public static native int run(String args, char mtu);
    public static native int stop();
}