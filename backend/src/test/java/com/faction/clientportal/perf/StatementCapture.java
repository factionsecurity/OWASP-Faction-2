package com.faction.clientportal.perf;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the prepared statements the application sends while capturing, with the exact {@code set*}
 * calls that bound their parameters, so each can be replayed under {@code EXPLAIN} with the same SQL and
 * the same values. Test tooling for {@link AssessmentWorkflowsPerfCheck} only.
 */
final class StatementCapture {

    /** One {@code PreparedStatement.setX(index, value, ...)} call. */
    record BindCall(Method method, Object[] args) {
    }

    record CapturedStatement(String sql, List<BindCall> binds) {
    }

    /** Thrown in place of executing when a capture is record-only. */
    static final class NotExecuted extends RuntimeException {
        NotExecuted() {
            super("statement captured without executing");
        }
    }

    private final List<CapturedStatement> captured = new CopyOnWriteArrayList<>();
    /**
     * The thread inside {@link #capture}, or {@code null} outside it. Statements are recorded (and, with
     * {@code execute} false, aborted) only when sent from this thread — a scheduled job on another thread
     * (e.g. {@code MentionQueueService}'s poller) sending a statement while a capture is in flight must
     * neither pollute the captured list nor be aborted by a record-only capture meant for the test thread.
     */
    private volatile Thread capturingThread;
    private volatile boolean execute = true;

    /**
     * Runs {@code call} and returns the statements it executed, in order. With {@code execute} false the
     * first statement is recorded and then aborted with {@link NotExecuted}, which is swallowed here.
     */
    List<CapturedStatement> capture(boolean execute, Runnable call) {
        captured.clear();
        this.execute = execute;
        capturingThread = Thread.currentThread();
        try {
            call.run();
        } catch (RuntimeException e) {
            if (!causedByNotExecuted(e)) {
                throw e;
            }
        } finally {
            capturingThread = null;
            this.execute = true;
        }
        return List.copyOf(captured);
    }

    DataSource wrap(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    return result instanceof Connection connection && method.getName().equals("getConnection")
                            ? wrapConnection(connection) : result;
                });
    }

    /** Replays {@code statement} as {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} and returns the JSON plan. */
    static String explain(Connection connection, CapturedStatement statement) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statement.sql())) {
            for (BindCall bind : statement.binds()) {
                try {
                    bind.method().invoke(ps, bind.args());
                } catch (ReflectiveOperationException e) {
                    throw new SQLException("Could not replay " + bind.method().getName(), e);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private Connection wrapConnection(Connection target) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                        return wrapStatement((PreparedStatement) result, sql);
                    }
                    return result;
                });
    }

    private PreparedStatement wrapStatement(PreparedStatement target, String sql) {
        List<BindCall> binds = new ArrayList<>();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    boolean noArgs = args == null || args.length == 0;
                    if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                        binds.add(new BindCall(method, args.clone()));
                    } else if (name.equals("clearParameters")) {
                        binds.clear();
                    } else if (Thread.currentThread() == capturingThread && noArgs
                            && (name.equals("executeQuery") || name.equals("execute") || name.equals("executeUpdate"))) {
                        captured.add(new CapturedStatement(sql, List.copyOf(binds)));
                        if (!execute) {
                            throw new NotExecuted();
                        }
                    }
                    return invoke(target, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static boolean causedByNotExecuted(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NotExecuted) {
                return true;
            }
        }
        return false;
    }
}
