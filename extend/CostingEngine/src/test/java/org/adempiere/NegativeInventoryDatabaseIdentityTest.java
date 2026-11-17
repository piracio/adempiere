package org.adempiere;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.adempiere.NegativeInventoryCostAdjustmentTest.DatabaseIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure validation tests: JDBC objects are strict proxies; ADempiere is never started. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class NegativeInventoryDatabaseIdentityTest {
    private static final String PREFIX = "adempiere.costtest.database.";
    private static final String URL = "jdbc:postgresql://localhost:5432/adempiere?encoding=UNICODE";
    private static final String[] KEYS = {"jdbcUrl", "serverAddress", "serverPort", "databaseName", "user"};
    private final Properties saved = new Properties();

    @BeforeEach
    void configure() {
        for (String key : KEYS) {
            String value = System.getProperty(PREFIX + key);
            if (value != null) saved.setProperty(key, value);
        }
        set("jdbcUrl", URL);
        set("serverAddress", "::1");
        set("serverPort", "5432");
        set("databaseName", "adempiere");
        set("user", "adempiere");
    }

    @AfterEach
    void restore() {
        for (String key : KEYS) {
            System.clearProperty(PREFIX + key);
            if (saved.containsKey(key)) set(key, saved.getProperty(key));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        URL,
        "jdbc:postgresql://localhost:5432/adempiere",
        "jdbc:postgresql://127.0.0.1:5432/adempiere?encoding=UNICODE",
        "jdbc:postgresql://[::1]:5432/adempiere?encoding=UNICODE"
    })
    void acceptsExplicitPostgresqlUrls(String url) {
        set("jdbcUrl", url);
        assertEquals(url, DatabaseIdentity.configured().url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "postgresql://localhost:5432/adempiere",
        "jdbc:mysql://localhost:5432/adempiere",
        "jdbc:postgresql:adempiere",
        "jdbc:postgresql://localhost/adempiere",
        "jdbc:postgresql://localhost:0/adempiere",
        "jdbc:postgresql://localhost:65536/adempiere",
        "jdbc:postgresql://localhost:5433/adempiere",
        "jdbc:postgresql://localhost:5432/other",
        "jdbc:postgresql://user@localhost:5432/adempiere",
        "jdbc:postgresql://localhost:5432/adempiere#fragment",
        "jdbc:postgresql://localhost:5432/adempiere?",
        "jdbc:postgresql://localhost:5432/adempiere?encoding=UTF8",
        "jdbc:postgresql://localhost:5432/adempiere?encoding=UNICODE&ssl=true",
        "jdbc:postgresql://localhost:5432/adempiere?encoding=UNICODE&encoding=UNICODE",
        "jdbc:postgresql://localhost:5432,other:5432/adempiere",
        "jdbc:postgresql://::1:5432/adempiere"
    })
    void rejectsAmbiguousOrMismatchedUrls(String url) {
        set("jdbcUrl", url);
        assertThrows(IllegalStateException.class, DatabaseIdentity::configured);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbcUrl", "serverAddress", "serverPort", "databaseName", "user"})
    void requiresEveryExplicitAllowlistProperty(String key) {
        System.clearProperty(PREFIX + key);
        assertThrows(IllegalStateException.class, DatabaseIdentity::configured);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" adempiere", "adempiere ", "*", "${PGUSER}", "adempiere,other"})
    void rejectsMissingOrAmbiguousValues(String user) {
        if (user == null) System.clearProperty(PREFIX + "user");
        else set("user", user);
        assertThrows(IllegalStateException.class, DatabaseIdentity::configured);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "localhost", "999.999.999.999", "256.0.0.1", "127.1", "2130706433",
        "1:::2", ":", "abcd:", "1:2:3:4:5:6:7:8:9", "[::1]", "fe80::1%lo0",
        "127.0.0.1/32", "::1/128", "127.0.0.0/24", "::/64"
    })
    void rejectsMalformedOrNonliteralAddresses(String address) {
        set("serverAddress", address);
        assertThrows(IllegalStateException.class, DatabaseIdentity::configured);
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, 127.0.0.1",
        "::1, ::1",
        "0:0:0:0:0:0:0:1, ::1",
        "::1, 0:0:0:0:0:0:0:1",
        "2001:DB8::A, 2001:db8:0:0:0:0:0:a"
    })
    void acceptsEquivalentLiteralAddresses(String allowed, String actual) throws SQLException {
        set("serverAddress", allowed);
        DatabaseIdentity.configured().verify(connection(URL, false, 1,
                actual, "5432", "adempiere", "adempiere", "adempiere"));
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, 127.0.0.1/32",
        "::1, ::1/128",
        "0:0:0:0:0:0:0:1, ::1/128",
        "::1, 0:0:0:0:0:0:0:1/128",
        "2001:DB8::A, 2001:db8:0:0:0:0:0:a/128"
    })
    void acceptsPostgresqlInetTextHostMasks(String allowed, String actual) throws SQLException {
        set("serverAddress", allowed);
        DatabaseIdentity.configured().verify(connection(URL, false, 1,
                actual, "5432", "adempiere", "adempiere", "adempiere"));
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, 127.0.0.2/32", "::1, ::2/128",
        "127.0.0.1, ::1/128", "::1, 127.0.0.1/32",
        "127.0.0.1, 127.0.0.1/24", "::1, ::1/64",
        "127.0.0.1, 127.0.0.1/128", "::1, ::1/32",
        "127.0.0.1, 127.0.0.1/032", "::1, ::1/0128",
        "127.0.0.1, 127.0.0.1/32/32", "::1, ::1/128/128",
        "127.0.0.1, localhost/32", "::1, localhost/128",
        "127.0.0.1, 127.0.0.1/", "::1, ::1/",
        "127.0.0.1, ::ffff:7f00:1/128", "::ffff:7f00:1, 127.0.0.1/32"
    })
    void rejectsMismatchedOrNonHostServerMasks(String allowed, String actual) {
        set("serverAddress", allowed);
        assertThrows(IllegalStateException.class, () -> DatabaseIdentity.configured().verify(
                connection(URL, false, 1, actual, "5432", "adempiere", "adempiere", "adempiere")));
    }

    @ParameterizedTest
    @CsvSource({"127.0.0.1, ::1", "::1, 127.0.0.1", "::1, ::2", "127.0.0.1, 127.0.0.2",
        "127.0.0.1, ::ffff:7f00:1", "::ffff:7f00:1, 127.0.0.1"})
    void rejectsDifferentAddressesAndIpFamilies(String allowed, String actual) {
        set("serverAddress", allowed);
        assertThrows(IllegalStateException.class, () -> DatabaseIdentity.configured().verify(
                connection(URL, false, 1, actual, "5432", "adempiere", "adempiere", "adempiere")));
    }

    @Test
    void requiresExactActualJdbcUrlBeforeQuerying() {
        for (String actual : new String[] {null, URL.replace("localhost", "127.0.0.1"),
                URL.replace("?encoding=UNICODE", ""), URL + "&ssl=true"}) {
            assertThrows(IllegalStateException.class, () -> DatabaseIdentity.configured()
                    .verify(connection(actual, false, 1)));
        }
    }

    @Test
    void rejectsAbsentOrClosedConnections() {
        DatabaseIdentity identity = DatabaseIdentity.configured();
        assertThrows(IllegalStateException.class, () -> identity.verify(null));
        assertThrows(IllegalStateException.class, () -> identity.verify(connection(URL, true, 1)));
    }

    @Test
    void rejectsEveryMismatchedOrMissingServerIdentityField() {
        String[] expected = {"::1", "5432", "adempiere", "adempiere", "adempiere"};
        for (int i = 0; i < expected.length; i++) {
            for (String wrong : new String[] {null, "other"}) {
                String[] actual = expected.clone();
                actual[i] = wrong;
                assertThrows(IllegalStateException.class, () -> DatabaseIdentity.configured()
                        .verify(connection(URL, false, 1, actual)), "identity column " + (i + 1));
            }
        }
    }

    @Test
    void requiresExactlyOneServerIdentityRow() {
        for (int count : new int[] {0, 2}) {
            assertThrows(IllegalStateException.class, () -> DatabaseIdentity.configured().verify(
                    connection(URL, false, count, "::1", "5432", "adempiere", "adempiere", "adempiere")));
        }
    }

    @Test
    void propagatesJdbcVerificationFailure() {
        Connection broken = proxy(Connection.class, (object, method, args) -> {
            throw new SQLException("No database evidence");
        });
        assertThrows(SQLException.class, () -> DatabaseIdentity.configured().verify(broken));
    }

    private static void set(String key, String value) {
        System.setProperty(PREFIX + key, value);
    }

    private static Connection connection(String actualUrl, boolean closed, int count, String... columns) {
        int[] cursor = {0};
        ResultSet result = proxy(ResultSet.class, (object, method, args) -> {
            switch (method.getName()) {
                case "next": return ++cursor[0] <= count;
                case "getString": return columns[(Integer) args[0] - 1];
                case "close": return null;
                default: throw new AssertionError("Unexpected ResultSet call: " + method);
            }
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (object, method, args) -> {
            switch (method.getName()) {
                case "executeQuery": return result;
                case "close": return null;
                default: throw new AssertionError("Unexpected statement call: " + method);
            }
        });
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (object, method, args) -> {
            assertEquals("getURL", method.getName());
            return actualUrl;
        });
        return proxy(Connection.class, (object, method, args) -> {
            switch (method.getName()) {
                case "isClosed": return closed;
                case "getMetaData": return metadata;
                case "prepareStatement":
                    assertEquals("SELECT inet_server_addr()::text, inet_server_port()::text,"
                            + " current_database(), session_user, current_user", args[0]);
                    return statement;
                default: throw new AssertionError("Unexpected connection call: " + method);
            }
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
