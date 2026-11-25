package com.gathin.tvhome;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.regex.Pattern;

import javax.crypto.Cipher;

/**
 * Pure-Java loopback ADB client talking strictly to {@code 127.0.0.1:5555} with a locally generated
 * RSA-2048 keypair stored in private app files. Only executes strictly whitelisted maintenance
 * commands (grant {@code READ_TV_LISTINGS}, safe force-stop of non-protected apps, and safe MiTV
 * stock-launcher restore following {@code launcherx} -> {@code setupwraith} -> {@code sync} order).
 */
final class LocalAdbClient implements Closeable {

    private static final String TAG = "LocalAdbClient";
    private static final String LOOPBACK = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int CLSE = 0x45534c43;
    private static final int WRTE = 0x45545257;
    private static final int STLS = 0x534c5453;
    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    private static final Pattern SAFE_PACKAGE = Pattern.compile("^[a-zA-Z0-9._]{2,120}$");

    private static final String STOCK_LAUNCHER_PKG = "com.google.android.apps.tv.launcherx";
    private static final String STOCK_LAUNCHER_ACT = "com.google.android.apps.tv.launcherx/.MainActivity";
    private static final String SETUPWRAITH_PKG = "com.google.android.tungsten.setupwraith";

    interface ApprovalListener {
        void onWaitingForApproval();
    }

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private LocalAdbClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    // ---- Whitelisted safe high-level actions ----------------------------------------------------

    /** Grants READ_TV_LISTINGS + GET_USAGE_STATS to this launcher via loopback ADB. */
    static String grantTvPermissions(Context ctx, @Nullable ApprovalListener approval) throws IOException {
        String pkg = ctx.getPackageName();
        try (LocalAdbClient adb = connect(ctx, 45_000, approval)) {
            StringBuilder out = new StringBuilder();
            out.append(adb.shell("pm grant " + pkg + " android.permission.READ_TV_LISTINGS"));
            out.append(adb.shell("appops set " + pkg + " GET_USAGE_STATS allow"));
            adb.shell("sync");
            return out.toString().trim();
        }
    }

    /**
     * Silently force-stops a single non-protected user package over loopback ADB if already
     * authorized (never pops an ADB approval dialog mid-Recents). Returns true on success.
     */
    static boolean forceStopSilent(Context ctx, String pkg) {
        if (!isSafeForceStopPackage(ctx, pkg)) return false;
        LocalAdbClient adb = connectSilent(ctx);
        if (adb == null) return false;
        try (LocalAdbClient c = adb) {
            c.shell("am force-stop " + pkg);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Silent force-stop failed for " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Restores the stock Google TV launcher safely, strictly obeying the MiTV Box S 3rd Gen
     * re-enable order: {@code launcherx} FIRST, set HOME, THEN {@code setupwraith}, then {@code sync}.
     */
    static void restoreStockLauncher(Context ctx, @Nullable ApprovalListener approval) throws IOException {
        try (LocalAdbClient adb = connect(ctx, 45_000, approval)) {
            runStockRestoreCommands(adb);
        }
    }

    /** Silent variant used by {@link CrashWatchdog} when no UI approval can be shown. */
    static boolean restoreStockLauncherSilent(Context ctx) {
        LocalAdbClient adb = connectSilent(ctx);
        if (adb == null) return false;
        try (LocalAdbClient c = adb) {
            runStockRestoreCommands(c);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Watchdog silent restore failed", e);
            return false;
        }
    }

    private static void runStockRestoreCommands(LocalAdbClient adb) throws IOException {
        // Order matters on MiTV Box S 3rd Gen (Android 14): enable launcherx BEFORE setupwraith!
        adb.shell("pm enable " + STOCK_LAUNCHER_PKG);
        adb.shell("cmd package set-home-activity " + STOCK_LAUNCHER_ACT);
        adb.shell("pm enable " + SETUPWRAITH_PKG);
        adb.shell("sync");
    }

    static boolean isSafeForceStopPackage(Context ctx, @Nullable String pkg) {
        if (pkg == null || !SAFE_PACKAGE.matcher(pkg).matches()) return false;
        if (pkg.equals(ctx.getPackageName())) return false;
        if (pkg.startsWith("com.android.systemui") || pkg.equals("android")) return false;
        return !AppForceStopper.isProtectedAccessibilityPackage(ctx, pkg);
    }

    // ---- ADB transport & authentication ---------------------------------------------------------

    private static LocalAdbClient connect(Context ctx, int approvalMs, @Nullable ApprovalListener approval)
            throws IOException {
        KeyPair keys = loadOrCreateKeys(ctx);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(LOOPBACK, PORT), 3000);
        } catch (IOException e) {
            safeClose(s);
            throw new IOException("ADB port 5555 is closed on TV (enable USB/Network debugging)");
        }
        LocalAdbClient adb;
        try {
            s.setSoTimeout(10_000);
            adb = new LocalAdbClient(s);
        } catch (IOException | RuntimeException e) {
            safeClose(s);
            throw e;
        }
        try {
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed = false;
            boolean offeredKey = false;
            while (true) {
                Message m;
                try {
                    m = adb.read();
                } catch (EOFException | SocketTimeoutException e) {
                    throw new IOException(offeredKey
                            ? "ADB authorization was not accepted on TV"
                            : "TV ADB daemon did not respond");
                }
                if (m.command == CNXN) {
                    s.setSoTimeout(20_000);
                    return adb;
                }
                if (m.command == STLS) {
                    throw new IOException("TV requested TLS ADB; port 5555 TCP ADB is required");
                }
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) {
                    throw new IOException("Unexpected ADB reply");
                }
                if (!signed) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, signToken(keys.getPrivate(), m.data));
                    signed = true;
                } else if (!offeredKey) {
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.getPublic()));
                    offeredKey = true;
                    if (approval != null) approval.onWaitingForApproval();
                    if (Build.VERSION.SDK_INT >= 30) {
                        s.setSoTimeout(approvalMs);
                    } else {
                        long until = SystemClock.elapsedRealtime() + approvalMs;
                        while (adb.in.available() == 0) {
                            if (SystemClock.elapsedRealtime() > until) {
                                throw new IOException("ADB authorization timed out");
                            }
                            SystemClock.sleep(2000);
                            LocalAdbClient fresh = connectSilentWithKeys(keys);
                            if (fresh != null) {
                                adb.close();
                                return fresh;
                            }
                        }
                    }
                } else {
                    throw new IOException("TV rejected local ADB key");
                }
            }
        } catch (IOException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    /** Connects only if the local keypair already exists and is trusted (never triggers UI prompt). */
    @Nullable
    private static LocalAdbClient connectSilent(Context ctx) {
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!priv.exists() || !pub.exists()) return null;
        try {
            return connectSilentWithKeys(loadOrCreateKeys(ctx));
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    private static LocalAdbClient connectSilentWithKeys(KeyPair keys) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(LOOPBACK, PORT), 2000);
            s.setSoTimeout(4000);
            LocalAdbClient adb = new LocalAdbClient(s);
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Message m = adb.read();
            if (m.command == AUTH && m.arg0 == AUTH_TOKEN) {
                adb.send(AUTH, AUTH_SIGNATURE, 0, signToken(keys.getPrivate(), m.data));
                m = adb.read();
            }
            if (m.command == CNXN) {
                s.setSoTimeout(15_000);
                return adb;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        safeClose(s);
        return null;
    }

    private String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                result.write(m.data);
                send(OKAY, local, m.arg0, null);
            } else if (m.command == CLSE) {
                if (remote == 0 && m.arg0 == 0) {
                    throw new IOException("ADB refused command");
                }
                send(CLSE, local, m.arg0, null);
                return result.toString("UTF-8");
            }
        }
    }

    @Override
    public void close() {
        safeClose(socket);
    }

    private static void safeClose(@Nullable Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        b.getInt(); // checksum
        int magic = b.getInt();
        if ((m.command ^ 0xffffffff) != magic) throw new IOException("Invalid ADB message");
        if (length < 0 || length > 1024 * 1024) throw new IOException("ADB payload too large");
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    private void send(int command, int arg0, int arg1, @Nullable byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) {
            for (byte x : data) sum += x & 0xff;
        }
        ByteBuffer b = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(command ^ 0xffffffff);
        if (data != null) b.put(data);
        out.write(b.array());
        out.flush();
    }

    private static synchronized KeyPair loadOrCreateKeys(Context ctx) throws IOException {
        File dir = ctx.getFilesDir();
        File priv = new File(dir, "adbkey");
        File pub = new File(dir, "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                try {
                    return new KeyPair(
                            kf.generatePublic(new X509EncodedKeySpec(readBytes(pub))),
                            kf.generatePrivate(new PKCS8EncodedKeySpec(readBytes(priv))));
                } catch (GeneralSecurityException | IOException e) {
                    Log.w(TAG, "Regenerating unreadable local ADB keypair");
                }
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            writeAtomic(priv, pair.getPrivate().getEncoded());
            writeAtomic(pub, pair.getPublic().getEncoded());
            return pair;
        } catch (GeneralSecurityException e) {
            throw new IOException("RSA key error: " + e.getMessage());
        }
    }

    private static byte[] signToken(PrivateKey key, byte[] token) throws IOException {
        try {
            byte[] block = new byte[256];
            block[1] = 1;
            int sep = block.length - SHA1_PREFIX.length - token.length - 1;
            for (int i = 2; i < sep; i++) block[i] = (byte) 0xff;
            System.arraycopy(SHA1_PREFIX, 0, block, sep + 1, SHA1_PREFIX.length);
            System.arraycopy(token, 0, block, sep + 1 + SHA1_PREFIX.length, token.length);
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException("ADB signature failed: " + e.getMessage());
        }
    }

    private static byte[] publicKeyBlob(PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        BigInteger n = rsa.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(rsa.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " cleanyourtv@tv\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        byte[] little = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) {
            little[i] = big[big.length - 1 - i];
        }
        return little;
    }

    private static byte[] readBytes(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) throw new IOException("Failed to write " + target.getName());
    }
}
