/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.utils;

import com.strapdata.strapkop.OperatorConfig;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.discovery.event.ServiceShutdownEvent;
import io.micronaut.discovery.event.ServiceStartedEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

@Singleton
@Infrastructure
public class JmxmpServerProvider {

    private static final Logger logger = LoggerFactory.getLogger(JmxmpServerProvider.class);

    private JMXConnectorServer jmxServer = null;

    @Inject
    OperatorConfig operatorConfig;

    public void createJMXMPServer()  throws IOException
    {
        final Integer jmxmpPort = operatorConfig.getJmxmpPort();
        if (jmxmpPort != null) {
            Map<String, Object> env = new HashMap<>();
            // Mark the JMX server as a permanently exported object. This allows the JVM to exit with the
            // server running and also exempts it from the distributed GC scheduler which otherwise would
            // potentially attempt a full GC every `sun.rmi.dgc.server.gcInterval` millis (default is 3600000ms)
            // For more background see:
            //   - CASSANDRA-2967
            //   - https://www.jclarity.com/2015/01/27/rmi-system-gc-unplugged/
            //   - https://bugs.openjdk.java.net/browse/JDK-6760712
            env.put("jmx.remote.x.daemon", "true");
            JMXServiceURL url = new JMXServiceURL("jmxmp", null, jmxmpPort);
            jmxServer = JMXConnectorServerFactory.newJMXConnectorServer(url, env, ManagementFactory.getPlatformMBeanServer());

            jmxServer.start();
            logger.info("JMXMP started server=" + url.toString());
        }
    }

    public void close() {
        if (jmxServer != null) {
            try {
                jmxServer.stop();
            } catch (IOException e) {
                logger.warn("Error during JMXMP server stop : {}", e.getMessage());
            }
        }
    }

    @EventListener
    public void onStartEvent(final ServiceStartedEvent event) {
        try {
            createJMXMPServer();
        } catch (IOException e) {
            logger.warn("Unable to start JMXMP server : {}", e.getMessage(), e);
        }
    }

    @EventListener
    public void onStopEvent(final ServiceShutdownEvent event) {
        close();
    }

    public static void main(String[] args) throws IOException{
        JmxmpServerProvider jmxmpServerProvider = new JmxmpServerProvider();
        jmxmpServerProvider.createJMXMPServer();
        System.out.println("Waiting any character to stop...");
        System.in.read();
        jmxmpServerProvider.close();

        Completable.complete().andThen(Completable.fromAction(() -> {
            throw new RuntimeException("");
        })).andThen(Completable.fromAction(() -> {
            System.out.println("Pouey");
        })).doFinally(() -> System.err.println("Finally")).blockingGet();
    }
}
