package io.quarkus.qe.vertx.sql.dbpool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.http.HttpStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.client.predicate.ResponsePredicate;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.RowSet;

@QuarkusTest
@TestProfile(PostgresqlTestProfile.class)
@TestMethodOrder(OrderAnnotation.class)
public class PostgresPoolTest {

    private static final int EVENTS = 25000;
    private static final int TIMEOUT_SEC = 60;

    @Inject
    PgPool postgresql;

    @ConfigProperty(name = "quarkus.http.test.port")
    int port;

    @ConfigProperty(name = "quarkus.datasource.reactive.max-size")
    int datasourceMaxSize;

    @ConfigProperty(name = "quarkus.datasource.reactive.idle-timeout")
    int idle;

    WebClient httpClient;

    @BeforeEach
    public void setup() {
        httpClient = WebClient.create(Vertx.vertx(), new WebClientOptions());
    }

    @Test
    @DisplayName("DB connections are re-used")
    @Order(1)
    public void checkDbPoolTurnover() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(EVENTS);

        for (int i = 0; i < EVENTS; i++) {
            Uni<Boolean> connectionsReUsed = makeHttpReqAsJson(httpClient, "airlines/", HttpStatus.SC_OK)
                    .flatMap(body -> activeConnections()
                            .onItem().ifNull()
                            .failWith(() -> new RuntimeException("Oh No! no postgres active connections found!"))
                            .onItem().ifNotNull().transform(this::checkDbActiveConnections));

            connectionsReUsed.subscribe().with(reUsed -> {
                assertTrue(reUsed, "More postgres SQL connections than pool max-size property");
                done.countDown();
            });
        }

        done.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        assertEquals(0, done.getCount(), String.format("Missing %d events.", EVENTS - done.getCount()));
    }

    @Test
    @Order(2)
    @DisplayName("IDLE remove expiration time")
    public void checkIdleExpirationTime() throws InterruptedException {
        // push Db pool to the limit in order to raise the number of active connections
        CountDownLatch done = new CountDownLatch(EVENTS);

        for (int i = 0; i < EVENTS; i++) {
            Uni<Long> activeConnectionsAmount = makeHttpReqAsJson(httpClient, "airlines/", HttpStatus.SC_OK)
                    .flatMap(body -> activeConnections()
                            .onItem().ifNull()
                            .failWith(() -> new RuntimeException("Oh No! no postgres active connections found!"))
                            .onItem().ifNotNull().transformToUni(resp -> activeConnections()));

            activeConnectionsAmount.subscribe().with(amount -> {
                // be sure that you have more than 1 connections
                assertThat(amount, greaterThanOrEqualTo(1L));
                done.countDown();
            });
        }

        done.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        assertEquals(0, done.getCount(), String.format("Missing %d events.", EVENTS - done.getCount()));

        // Make just one extra query and Hold "Idle + 1 sec" in order to release inactive connections.
        Uni<Long> activeConnectionAmount = postgresql.preparedQuery("SELECT CURRENT_TIMESTAMP").execute()
                .onItem().delayIt().by(Duration.ofSeconds(idle + 1))
                .onItem().transformToUni(resp -> activeConnections());

        CountDownLatch doneIdleExpired = new CountDownLatch(1);
        activeConnectionAmount.subscribe().with(connectionsAmount -> {
            // At this point you should just have one connection -> SELECT CURRENT_TIMESTAMP
            assertEquals(1, connectionsAmount, "Idle doesn't remove IDLE expired connections!.");
            if (connectionsAmount == 1) {
                doneIdleExpired.countDown();
            }
        });

        doneIdleExpired.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        assertEquals(0, doneIdleExpired.getCount(), "Missing doneIdleExpired query.");
    }

    private Uni<Long> activeConnections() {
        return postgresql.query(
                "SELECT count(*) as active_con FROM pg_stat_activity where application_name like '%vertx%'")
                .execute()
                .onItem().transform(RowSet::iterator).onItem()
                .transform(iterator -> iterator.hasNext() ? iterator.next().getLong("active_con") : null);
    }

    private Uni<JsonArray> makeHttpReqAsJson(WebClient httpClient, String path, int expectedStatus) {
        return makeHttpReq(httpClient, path, expectedStatus).map(HttpResponse::bodyAsJsonArray);
    }

    private Uni<HttpResponse<Buffer>> makeHttpReq(WebClient httpClient, String path, int expectedStatus) {
        return httpClient.getAbs(getAppEndpoint() + path)
                .expect(ResponsePredicate.status(expectedStatus))
                .send();
    }

    private String getAppEndpoint() {
        return String.format("http://localhost:%d/", port);
    }

    private boolean checkDbActiveConnections(long active) {
        return active <= datasourceMaxSize + (7); // TODO: double check this condition ... this magical number is scary!.
    }
}
