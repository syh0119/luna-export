package com.syh.luna.core;

import java.io.UnsupportedEncodingException;
import java.util.Base64;

/**
 * SpadeDecryptor —— 汽水音乐 encrypt_info.spade_a -> AES-128 key(32 hex)。
 *
 * 逐字节等价于 Python 版 _spade.py（已通过官方测试向量）：
 *   kLwe+la9BvZLvwzvcaI97HOnPO9ClAroRbgK8Xa5CPBCuAqDgw==  ->  5011945309e6465581fd0d6971c0e002
 */
public final class Spade {

    private Spade() { }

    static int bitCount(int n) {
        return Integer.bitCount(n);
    }

    static int decodeBase36(int c) {
        if (c >= 48 && c <= 57) return c - 48;
        if (c >= 97 && c <= 122) return c - 97 + 10;
        return 0xFF;
    }

    /** inner -> tmp，长度与 sk 相同 */
    static byte[] decryptInner(byte[] sk) {
        byte[] result = new byte[sk.length];
        for (int i = 0; i < sk.length; i++) {
            int b;
            if (i == 0) b = 0xFA;
            else if (i == 1) b = 0x55;
            else b = sk[i - 2] & 0xFF;
            int v = ((sk[i] & 0xFF) ^ b) - bitCount(i) - 21;
            while (v < 0) v += 0xFF;
            result[i] = (byte) (v & 0xFF);
        }
        return result;
    }

    /** spade 原始字节 -> key 字符串（32 hex），失败返回 null */
    public static String decryptSpade(byte[] b) {
        int n = b.length;
        if (n < 3) return null;
        int padding = ((b[0] & 0xFF) ^ (b[1] & 0xFF) ^ (b[2] & 0xFF)) - 48;
        if (n < padding + 2) return null;
        int innerLen = n - padding - 1;          // python: b[1 : n-padding]
        if (innerLen <= 0) return null;
        byte[] inner = new byte[innerLen];
        System.arraycopy(b, 1, inner, 0, innerLen);

        byte[] tmp = decryptInner(inner);
        if (tmp.length == 0) return null;
        int skip = decodeBase36(tmp[0] & 0xFF);
        int dml = n - padding - 2;
        int end = 1 + dml - skip;
        if (end > tmp.length || end < 0) return null;
        try {
            return new String(tmp, 1, end - 1, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    /** base64(spade_a) -> key hex；失败返回 null */
    public static String fromSpadeA(String s) {
        if (s == null) return null;
        s = s.trim();
        int pad = (4 - s.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(s);
        for (int k = 0; k < pad; k++) sb.append('=');
        byte[] raw;
        try {
            // MIME 解码器：忽略非字母表字符，等价 python 的 validate=False
            raw = Base64.getMimeDecoder().decode(sb.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return decryptSpade(raw);
    }
}
