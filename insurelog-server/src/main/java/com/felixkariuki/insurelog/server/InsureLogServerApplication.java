package com.felixkariuki.insurelog.server;

import com.felixkariuki.insurelog.server.sim.TrafficSimulator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Central InsureLog server.
 *
 * <pre>
 * java -jar insurelog-server-2.0.0.jar                       start the server (port 8080)
 * java -jar insurelog-server-2.0.0.jar simulate --rate 10    send demo traffic to a running server
 * </pre>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class InsureLogServerApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "simulate".equals(args[0])) {
            TrafficSimulator.main(args);
            return;
        }
        SpringApplication.run(InsureLogServerApplication.class, args);
    }
}
