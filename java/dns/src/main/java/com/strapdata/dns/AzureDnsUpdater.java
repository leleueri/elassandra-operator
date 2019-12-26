package com.strapdata.dns;


import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.credentials.AzureTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.rest.LogLevel;
import hu.akarnokd.rxjava.interop.RxJavaInterop;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceStartedEvent;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;

/**
 * Manage public DNS records on Azure
 */
@Singleton
public class AzureDnsUpdater extends DnsUpdater implements ApplicationEventListener<ServiceStartedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(AzureDnsUpdater.class);

    Azure azure;
    String resourceGroup;

    public AzureDnsUpdater(String dnsDomain, int dnsTtl) throws IOException {
        super(dnsDomain, dnsTtl);

        String clientId = System.getenv("DNS_AZURE_CLIENT_ID");
        String tenantId = System.getenv("DNS_AZURE_TENANT_ID");
        String clientSecret = System.getenv("DNS_AZURE_CLIENT_SECRET");
        String subscriptionId = System.getenv("DNS_AZURE_SUBSCRIPTION_ID");
        this.resourceGroup = System.getenv("DNS_AZURE_RESOURCE_GROUP");
        try {
            AzureEnvironment environment = new AzureEnvironment(new HashMap<String, String>());
            environment.endpoints().putAll(AzureEnvironment.AZURE.endpoints());

            AzureTokenCredentials credentials = new ApplicationTokenCredentials(
                    clientId,
                    tenantId,
                    clientSecret,
                    environment).withDefaultSubscriptionId(subscriptionId);

            this.azure = Azure.configure()
                    .withLogLevel(LogLevel.BASIC)
                    .authenticate(credentials)
                    .withDefaultSubscription();

            // Print selected subscription
            logger.info("Selected clientId: " + clientId);
            logger.info("Selected subscription: " + azure.subscriptionId());
            logger.info("Selected resourceGroup: " + resourceGroup);
        } catch(Exception e) {
            logger.error("Azure authentication failed with clientId={} tenanId={} subcriptionId={} resourceGroup={}", clientId, tenantId, subscriptionId, resourceGroup);
            this.azure = null;
        }
    }

    /**
     * Add public DNS records for traefik
     * @param name
     * @param externalIp
     * @return
     */
    public Completable updateDnsARecord(String name, String externalIp) {
        if (azure == null)
            throw new IllegalStateException("Azure authentication failed");
        return RxJavaInterop.toV2Observable(azure.dnsZones().getByResourceGroupAsync(this.resourceGroup, dnsDomain)
                .flatMap(zone -> {
                    logger.debug("creating DNS records {} = {}", name, externalIp);
                    return zone.update()
                            // A name = externalIP
                            .defineARecordSet(name)
                            .withIPv4Address(externalIp)
                            .withTimeToLive(this.dnsTtl).attach()
                            .applyAsync();
                })).ignoreElements();
    }

    /**
     * Delete public DNS records for traefik.
     * @param name
     * @return
     */
    public Completable deleteDnsARecord(String name) {
        logger.debug("deleting DNS record name={}", name);
        if (azure == null)
            throw new IllegalStateException("Azure authentication failed");
        return RxJavaInterop.toV2Observable(azure.dnsZones().getByResourceGroupAsync(resourceGroup, dnsDomain)
                .flatMap(zone -> {
                    logger.debug("deleting DNS records {}", name);
                    return zone.update()
                            .withoutARecordSet(name)
                            .applyAsync();
                })).ignoreElements();
    }

}

