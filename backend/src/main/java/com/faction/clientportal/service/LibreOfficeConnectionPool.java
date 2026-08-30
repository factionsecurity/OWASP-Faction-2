package com.faction.clientportal.service;

import com.sun.star.comp.helper.Bootstrap;
import com.sun.star.frame.XDesktop;
import com.sun.star.lang.XMultiComponentFactory;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.uno.XComponentContext;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Singleton pool of reusable connections to a headless LibreOffice server.
 *
 * <p>LibreOffice must be running as a socket server before the first borrow:
 * <pre>
 *   soffice --headless --norestore \
 *     --accept="socket,host=localhost,port=2002;urp;StarOffice.ServiceManager"
 * </pre>
 *
 * <p>Connections are returned to the pool after use. Invalid connections
 * (e.g. after a communication error) are discarded rather than returned.
 */
@Slf4j
public class LibreOfficeConnectionPool {

    private static volatile LibreOfficeConnectionPool instance;

    private final String host;
    private final int    port;
    private final int    poolSize;
    private final BlockingQueue<PooledConnection> pool;

    // ── Inner class ──────────────────────────────────────────────────────────

    public static class PooledConnection {
        private final XComponentContext context;
        private final XDesktop          desktop;
        private volatile boolean valid = true;

        PooledConnection(XComponentContext context, XDesktop desktop) {
            this.context = context;
            this.desktop = desktop;
        }

        public XComponentContext getContext() { return context; }
        public XDesktop          getDesktop()  { return desktop;  }
        public boolean           isValid()     { return valid;    }
        public void              invalidate()  { valid = false;   }
    }

    // ── Singleton ────────────────────────────────────────────────────────────

    private LibreOfficeConnectionPool(String host, int port, int poolSize) {
        this.host     = host;
        this.port     = port;
        this.poolSize = poolSize;
        this.pool     = new ArrayBlockingQueue<>(poolSize);
    }

    public static LibreOfficeConnectionPool getInstance() {
        if (instance == null) {
            synchronized (LibreOfficeConnectionPool.class) {
                if (instance == null) {
                    instance = new LibreOfficeConnectionPool("localhost", 2002, 3);
                }
            }
        }
        return instance;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Borrows a valid connection, creating one if the pool is empty. */
    public PooledConnection borrowConnection() throws Exception {
        PooledConnection conn = pool.poll();
        if (conn != null && conn.isValid()) {
            log.debug("Reusing pooled LibreOffice connection");
            return conn;
        }
        log.info("Creating new LibreOffice connection to {}:{}", host, port);
        return createConnection();
    }

    /** Returns a still-valid connection to the pool. */
    public void returnConnection(PooledConnection conn) {
        if (conn != null && conn.isValid()) {
            if (!pool.offer(conn)) {
                log.debug("Pool full — discarding LibreOffice connection");
            }
        }
    }

    /** Marks a connection as bad so it is not returned to the pool. */
    public void invalidateConnection(PooledConnection conn) {
        if (conn != null) {
            conn.invalidate();
        }
    }

    public String getPoolStats() {
        return String.format("pool=%d/%d", pool.size(), poolSize);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private PooledConnection createConnection() throws Exception {
        String connectString = String.format(
                "uno:socket,host=%s,port=%d;urp;StarOffice.ComponentContext", host, port);

        XComponentContext localCtx = Bootstrap.createInitialComponentContext(null);
        XMultiComponentFactory localSm = localCtx.getServiceManager();

        Object resolverObj = localSm.createInstanceWithContext(
                "com.sun.star.bridge.UnoUrlResolver", localCtx);
        com.sun.star.bridge.XUnoUrlResolver resolver =
                UnoRuntime.queryInterface(com.sun.star.bridge.XUnoUrlResolver.class, resolverObj);

        Object remoteCtxObj = resolver.resolve(connectString);
        XComponentContext remoteCtx =
                UnoRuntime.queryInterface(XComponentContext.class, remoteCtxObj);
        XMultiComponentFactory remoteSm = remoteCtx.getServiceManager();

        Object desktopObj = remoteSm.createInstanceWithContext(
                "com.sun.star.frame.Desktop", remoteCtx);
        XDesktop xDesktop = UnoRuntime.queryInterface(XDesktop.class, desktopObj);

        return new PooledConnection(remoteCtx, xDesktop);
    }
}
