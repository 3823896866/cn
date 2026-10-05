package com.xiaoran.nb.net;

import android.content.Context;

/**
 * Shizuku 真实可用性检测（不引入 Shizuku SDK）。
 * Shizuku 通过 ADB/Root 激活后，会在本地 127.0.0.1:9527 提供 RPC 服务；
 * 用 TCP 连通性作为"是否已激活/授权"的判定。
 */
public final class ShizukuDetector {
    public static class Result {
        public final boolean ok; public final String msg;
        Result(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
    }
    private ShizukuDetector() {}

    public static Result check(Context ctx) {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 9527), 1500);
            s.close();
            return new Result(true, "Shizuku 已激活");
        } catch (Exception e) {
            return new Result(false, "未检测到 Shizuku（请在 Shizuku 应用中激活并授权本应用）");
        }
    }
}
