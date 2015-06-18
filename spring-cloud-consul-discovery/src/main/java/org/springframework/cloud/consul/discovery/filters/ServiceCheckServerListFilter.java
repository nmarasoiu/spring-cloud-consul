/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.consul.discovery.filters;

import static com.ecwid.consul.v1.health.model.Check.CheckStatus.*;

import java.util.*;

import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.map.DefaultedMap;
import org.springframework.cloud.consul.discovery.ConsulServer;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.health.model.Check;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerListFilter;

/**
 * Created by nicu on 12.03.2015.
 */
@Slf4j
@AllArgsConstructor
public class ServiceCheckServerListFilter implements ServerListFilter<Server> {
    private ConsulClient client;

    private FilteringAgentClient filteringAgentClient;

    public ServiceCheckServerListFilter(ConsulClient client) {
        this.client = client;
        this.filteringAgentClient = new FilteringAgentClient(client);
    }

    /**
     * - get health checks (for service instances) - queried by service name & filtered by
     * nodes' health - make sure serf status is also filtered by - extract service
     * instances which do not have any critical checks, and, by config, do not have any
     * warn/unknown, have at least one check defined, etc; most filtering: all checks
     * passing & at least 1 check - cache them
     *
     * @param servers
     * @return
     */

    @Override
    public List<Server> getFilteredListOfServers(List<Server> servers) {
        log.debug("Before filtering: servers={}", servers);
        if (servers.isEmpty())
            return servers;

        final Map<String, HealthLevel> nodesHealth = nodesByHealth(extractNodes(servers));
        final Map<String, HealthLevel> instancesHealth = serviceInstancesByHealth(cast(
                servers.get(0)).getMetaInfo().getAppName());
        log.debug("nodesHealth={},instancesHealth={}", nodesHealth, instancesHealth);

        @ToString
        @AllArgsConstructor
        class Instance implements Comparable<Instance> {
            private final Server server;
            private final HealthLevel healthLevel;

            @Override
            public int compareTo(Instance other) {
                return healthLevel.compareTo(other.healthLevel);
            }
        }
        class InstancesUtil {
            private HealthLevel health(Server server) {
                HealthLevel instanceHealth = instancesHealth.get(server.getMetaInfo().getInstanceId());
                HealthLevel nodeHealth = nodesHealth.get(cast(server).getNode());
                log.debug("Instance health {} and node health {} for {}", instanceHealth, nodeHealth, server.getMetaInfo().getInstanceId());
                return min(instanceHealth, nodeHealth);
            }

            private Collection<Instance> toInstances(List<Server> servers) {
                return new Mapper<Server, Instance>() {
                    @Override
                    protected Instance map(Server server) {
                        return new Instance(server, health(server));
                    }
                }.map(servers);
            }

            private List<Server> toServers(List<Instance> instances) {
                return new Mapper<Instance, Server>() {
                    @Override
                    protected Server map(Instance instance) {
                        return instance.server;
                    }
                }.map(instances);
            }
        }
        InstancesUtil conversionUtils = new InstancesUtil();

        PriorityQueue<Instance> heap = new PriorityQueue<>(12,
                new Comparator<Instance>() {
                    @Override
                    public int compare(Instance o1, Instance o2) {
                        return o2.compareTo(o1);
                    }
                });
        heap.addAll(conversionUtils.toInstances(servers));
        log.debug("heap={}", heap);

        List<Instance> topInstances = new ArrayList<>();
        Instance topInstance = heap.peek();
        if (topInstance != null
                && topInstance.healthLevel.compareTo(HealthLevel.FAILED) > 0) {
            while (heap.peek() != null && topInstance.compareTo(heap.peek()) == 0) {
                topInstances.add(heap.remove());
            }
        }
        return conversionUtils.toServers(topInstances);
    }

    /**
     * Given a service name, get all the service instances which have checks defined,
     * together with their min-level status check.
     *
     * @return a map from service instance to the health level which is the minimum health
     * level for all defined service checks for the instance.
     */
    private Map<String, HealthLevel> serviceInstancesByHealth(String serviceName) {
        List<Check> checks = client.getHealthChecksForService(serviceName,
                consistencyMode()).getValue();
        return new Indexer() {
            String id(Check check) {
                return check.getServiceId();
            }
        }.indexByMinHealth(checks);
    }

    /**
     * Given a list of nodes (servers) (like, servers hosting a service), query/compute
     * the health of each node (server). The aggregation is composed of 2 phases: 1.
     * filter the nodes (servers) by serf status. Since we are only interested in live
     * nodes. 2. Index the health level of each server. That is the health level which is
     * the minimum health level for all defined node' checks.
     */
    private Map<String, HealthLevel> nodesByHealth(List<String> nodes) {
        List<String> liveNodes = getLiveNodes(nodes);
        List<Check> checks = new ArrayList<>();
        for (String liveNode : liveNodes) {
            checks.addAll(systemNodeChecks(liveNode));
        }
        return new Indexer() {
            String id(Check check) {
                return check.getNode();
            }
        }.indexByMinHealth(checks);
    }

    // yes, in fact nodes is mutated, an ugly optimization
    private List<String> getLiveNodes(List<String> nodes) {
        List<String> liveNodes = nodes;
        liveNodes.retainAll(filteringAgentClient.getAliveAgentsNodes());
        return liveNodes;
    }

    private abstract class Indexer {

        Map<String, HealthLevel> indexByMinHealth(List<Check> checks) {
            Map<String, HealthLevel> instancesHealth = mapForMin();
            // now we min-aggregate health levels by service instances'
            for (Check check : checks) {
                String id = id(check);
                instancesHealth.put(
                        id,
                        min(instancesHealth.get(id),
                                HealthLevel.forCheckStatus(check.getStatus())));
            }
            // and we left-join with all service instances (including those not having
            // defined
            // any checks)
            return mapWithMissingHealthLevel(instancesHealth);
        }

        abstract String id(Check check);

    }

    private Map mapWithMissingHealthLevel(Map<String, HealthLevel> instancesHealth) {
        return DefaultedMap.decorate(instancesHealth, HealthLevel.NO_CHECK_DEFINED);
    }

    private <T> Map<T, HealthLevel> mapForMin() {
        return new DefaultedMap(HealthLevel.INFINITY);
    }

    private HealthLevel min(HealthLevel healthLevel0, HealthLevel healthLevel1) {
        return healthLevel0.compareTo(healthLevel1) <= 0 ? healthLevel0 : healthLevel1;
    }

    private enum HealthLevel {
        MINUS_INFINITY(null), FAILED(CRITICAL), UNDETERMINED(UNKNOWN), NO_CHECK_DEFINED(
                null), WARN(WARNING), GREEN(PASSING), INFINITY(null);

        static private Map<Check.CheckStatus, HealthLevel> indexByCheckStatus = getCheckStatusHealthLevelMap();

        private Check.CheckStatus status;

        HealthLevel(Check.CheckStatus status) {
            this.status = status;
        }

        public static HealthLevel forCheckStatus(Check.CheckStatus checkStatus) {
            return indexByCheckStatus.get(checkStatus);
        }

        private static Map<Check.CheckStatus, HealthLevel> getCheckStatusHealthLevelMap() {
            Map<Check.CheckStatus, HealthLevel> m = new HashMap<>();
            for (HealthLevel healthLevel : values()) {
                if(healthLevel.status!=null){
                    m.put(healthLevel.status, healthLevel);
                }
            }
            return m;
        }

    }

    private QueryParams consistencyMode() {
        return QueryParams.DEFAULT;
    }

    private List<Check> systemNodeChecks(String node) {
        List<Check> systemNodeChecks = new LinkedList<>();
        for (Check check : client.getHealthChecksForNode(node, consistencyMode())
                .getValue()) {
            if (check.getServiceId().isEmpty()) {
                systemNodeChecks.add(check);
            }
        }
        return systemNodeChecks;
    }

    private ConsulServer cast(Server server) {
        return ConsulServer.class.cast(server);
    }

    private List<String> extractNodes(List<Server> servers) {
        return new Mapper<Server, String>() {
            @Override
            protected String map(Server server) {
                ConsulServer consulServer = ConsulServer.class.cast(server);
                return consulServer.getNode();
            }
        }.map(servers);
    }

    private abstract class Mapper<X, Y> {
        List<Y> map(List<X> src) {
            List<Y> dest = new ArrayList<>();
            for (X elem : src) {
                dest.add(map(elem));
            }
            return dest;
        }

        protected abstract Y map(X elem);
    }

}
