package db.migration;

import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class DevelopRenewalHistoryBridgeTests {
    @Test void scriptDelimitersInsideCommentsAndQuotedValuesArePreserved() throws Exception {
        var connection=mock(Connection.class);
        var statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.getUpdateCount()).thenReturn(-1);
        DevelopRenewalHistoryBridge.execute(connection,
            "-- Preserve old data; only demo rows qualify.\nCREATE TABLE demo (value VARCHAR(20));\n"
            +"INSERT INTO demo VALUES ('a;b');");
        verify(statement).execute("CREATE TABLE demo (value VARCHAR(20))");
        verify(statement).execute("INSERT INTO demo VALUES ('a;b')");
        verify(statement,times(2)).execute(anyString());
    }
}
