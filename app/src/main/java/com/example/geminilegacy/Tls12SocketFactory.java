package com.example.geminilegacy;

import android.os.Build;
import android.util.Log;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

/**
 * Makes HTTPS work on old Android.
 *
 * Android 4.4 and older ship an old TLS stack: TLS 1.2 is off by default (API 16-19) or missing
 * (API 14-15), and there are no AES-GCM cipher suites before API 20. Modern servers such as GitHub
 * accept only GCM suites, so the platform stack cannot connect to them.
 *
 * On API 14-20 we therefore use the bundled Conscrypt library (modern TLS 1.2/1.3 with GCM and
 * ChaCha20) and add a few root certificates those phones lack. If Conscrypt cannot load, we fall
 * back to forcing TLS 1.2 on the platform stack (enough for Google's API on API 16-20).
 */
public class Tls12SocketFactory extends SSLSocketFactory {
    private static final String TAG = "Tls12SocketFactory";
    private static final String[] TLS_V12_ONLY = {"TLSv1.2"};

    private final SSLSocketFactory delegate;

    public Tls12SocketFactory(SSLSocketFactory base) {
        this.delegate = base;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }

    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return patch(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return patch(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return patch(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return patch(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return patch(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket patch(Socket s) {
        if (s instanceof SSLSocket) {
            ((SSLSocket) s).setEnabledProtocols(TLS_V12_ONLY);
        }
        return s;
    }

    /** Applies the best available TLS setup to a builder, only on the API levels that need it. */
    public static OkHttpClient.Builder enableTls12(OkHttpClient.Builder builder) {
        if (Build.VERSION.SDK_INT >= 21) {
            return builder; // Lollipop and newer already speak modern TLS
        }
        if (useConscrypt(builder)) {
            return builder;
        }
        if (Build.VERSION.SDK_INT < 16) {
            return builder; // no TLS 1.2 on the platform stack at all
        }
        try {
            X509TrustManager systemTm = systemTrustManager();
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, new TrustManager[]{systemTm}, null);
            builder.sslSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()), systemTm);

            ConnectionSpec tls12 = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build();
            builder.connectionSpecs(Arrays.asList(tls12, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT));
        } catch (Exception e) {
            Log.e(TAG, "Could not enable TLS 1.2", e);
        }
        return builder;
    }

    private static boolean useConscrypt(OkHttpClient.Builder builder) {
        try {
            if (!Conscrypt.isAvailable()) return false;
            Provider provider = Conscrypt.newProvider();
            X509TrustManager tm = new CompositeTrustManager(systemTrustManager(), bundledTrustManager());
            SSLContext sc = SSLContext.getInstance("TLS", provider);
            sc.init(null, new TrustManager[]{tm}, null);
            builder.sslSocketFactory(sc.getSocketFactory(), tm);
            builder.connectionSpecs(Arrays.asList(
                    ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Conscrypt unavailable, using platform TLS", t);
            return false;
        }
    }

    private static X509TrustManager systemTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return firstX509(tmf);
    }

    private static X509TrustManager bundledTrustManager() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < BundledRoots.PEMS.length; i++) {
            ks.setCertificateEntry("bundled" + i,
                    cf.generateCertificate(new ByteArrayInputStream(BundledRoots.PEMS[i].getBytes("US-ASCII"))));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return firstX509(tmf);
    }

    private static X509TrustManager firstX509(TrustManagerFactory tmf) {
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("No X509TrustManager available");
    }

    /** Trusts a chain if either the phone's own CA list or our bundled roots vouch for it. */
    private static final class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager bundled;

        CompositeTrustManager(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (Exception first) {
                try {
                    bundled.checkServerTrusted(chain, authType);
                } catch (Exception second) {
                    if (first instanceof CertificateException) throw (CertificateException) first;
                    throw new CertificateException(first);
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = system.getAcceptedIssuers();
            X509Certificate[] b = bundled.getAcceptedIssuers();
            X509Certificate[] all = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            return all;
        }
    }
}
