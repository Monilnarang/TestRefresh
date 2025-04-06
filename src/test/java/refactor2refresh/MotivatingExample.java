package refactor2refresh;

import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MotivatingExample {

    class DatabaseClient {
        private int port;
        private final String type;

        public DatabaseClient(int port, String type) {
            this.port = port;
            this.type = type;
        }

        public String buildConnectionString() {
            return "jdbc:" + type + "://localhost:" + port;
        }

        public DatabaseClient(String type) {
            this.type = type;
        }

        public int getPort() {
            return port;
        }
        public String getType() {
            return type;
        }
    }


    @Test
    public void test() {
        final DatabaseClient testClient1 = new DatabaseClient("Snowflake");
        final DatabaseClient testClient2 = new DatabaseClient("Elasticsearch");
        String snowflake = testClient1.buildConnectionString();
        String elastic = testClient2.buildConnectionString();
        assertNotEquals(snowflake, elastic);
        assertNotEquals(testClient1.getType(), testClient2.getType());
        assertNotEquals(testClient1.getPort(), testClient2.getPort());
        assertEquals(443, testClient1.getPort());
        assertEquals("Snowflake", testClient1.getType());
        assertEquals("ElasticSearch", testClient2.getType());

    }

    @Test
    public void testGetPort() {
        final DatabaseClient testClient = new DatabaseClient("MySQL");
        final DatabaseClient testClient2 = new DatabaseClient("PostgreSQL");
        final DatabaseClient testClient3 = new DatabaseClient("MongoDB");
        assertEquals(3306, testClient.getPort());
        assertEquals(5432, testClient2.getPort());
        assertEquals(27017, testClient3.getPort());
    }

    @Test
    public void testGetType() {
        final DatabaseClient mysqlClient = new DatabaseClient("MySQL");
        final DatabaseClient postgreSQLClient = new DatabaseClient("PostgreSQL");
        final DatabaseClient mongoDBClient = new DatabaseClient("MongoDB");
        assertEquals(3306, mysqlClient.getPort());
        assertEquals(5432, postgreSQLClient.getPort());
        assertEquals(27017, mongoDBClient.getPort());
    }

}
