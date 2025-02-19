package io.quarkus.ts.messaging.kafka;

import io.quarkus.test.bootstrap.RestService;
import io.quarkus.test.scenarios.OpenShiftScenario;
import io.quarkus.test.services.Operator;
import io.quarkus.test.services.QuarkusApplication;
import io.quarkus.test.services.operator.KafkaInstance;

@OpenShiftScenario
public class OperatorOpenShiftAmqStreamsKafkaStreamIT extends BaseKafkaStreamTest {
    @Operator(name = "amq-streams", source = "redhat-operators")
    static KafkaInstance kafka = new KafkaInstance();

    @QuarkusApplication
    static RestService app = new RestService()
            .withProperty("kafka.bootstrap.servers", kafka::getBootstrapUrl)
            .withProperty("quarkus.kafka-streams.bootstrap-servers", kafka::getBootstrapUrl);

    @Override
    protected String getAppUrl() {
        return app.getHost() + ":" + app.getPort();
    }
}
