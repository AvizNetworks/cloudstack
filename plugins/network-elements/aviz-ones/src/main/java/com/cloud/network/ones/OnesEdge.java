//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.ones;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.network.OnesProviderVO;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.utils.net.NetUtils;

/**
 * The edge side of a ONES network (lab/demo): internet access on the Aviz Gateway.
 *
 * enable(): after the network's first host joined the tenant. ONES's tenant webhook makes the
 * edge configurator put the tenant on the gateway a few seconds later, so this waits for the
 * gateway to know it, then gives it one free public address from the provider's list. Never
 * fails the VM deployment: the network just stays east-west only, and the log says why.
 * disable(): when the network shuts down or is deleted.
 */
public final class OnesEdge {

    private static final Logger LOG = LogManager.getLogger(OnesEdge.class);
    public static final String DETAIL_PUBLIC_IP = "onespublicip";
    static final long TENANT_WAIT_MS = 180_000;
    static final long TENANT_POLL_MS = 5_000;

    private OnesEdge() {
    }

    public static boolean configured(final OnesProviderVO p) {
        return p.getGatewayUrl() != null && p.getGatewayToken() != null && p.getPublicIps() != null;
    }

    private static AvizGatewayClient client(final OnesProviderVO p) {
        return new AvizGatewayClient(p.getGatewayUrl(), p.getGatewayToken(), p.getGatewayCaCertPath());
    }

    /** "a.b.c.d-a.b.c.e" and/or comma-separated addresses, in order. */
    public static List<String> expandAddresses(final String spec) {
        List<String> out = expand(spec);
        for (String ip : out) {
            if (!NetUtils.isValidIp4(ip)) {
                throw new IllegalArgumentException("not an IPv4 address: " + ip);
            }
        }
        return out;
    }

    static List<String> expand(final String spec) {
        List<String> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.contains("-")) {
                long a = NetUtils.ip2Long(part.split("-")[0].trim());
                long b = NetUtils.ip2Long(part.split("-")[1].trim());
                for (long ip = a; ip <= b; ip++) {
                    out.add(NetUtils.long2Ip(ip));
                }
            } else {
                out.add(part);
            }
        }
        return out;
    }

    static String freeAddress(final OnesProviderVO p, final NetworkDetailsDao details) {
        Set<String> used = new HashSet<>();
        for (NetworkDetailVO d : details.findDetails(DETAIL_PUBLIC_IP)) {
            used.add(d.getValue());
        }
        for (String ip : expand(p.getPublicIps())) {
            if (!used.contains(ip)) {
                return ip;
            }
        }
        return null;
    }

    public static synchronized void enable(final OnesProviderVO p, final long networkId, final String tenant,
            final NetworkDetailsDao details) {
        if (!configured(p) || details.findDetail(networkId, DETAIL_PUBLIC_IP) != null) {
            return;
        }
        String ip = freeAddress(p, details);
        if (ip == null) {
            LOG.warn("No free public address left in {} for ONES tenant {}: no internet access", p.getPublicIps(), tenant);
            return;
        }
        try {
            AvizGatewayClient gw = client(p);
            long deadline = System.currentTimeMillis() + TENANT_WAIT_MS;
            while (!gw.hasTenant(tenant)) {
                if (System.currentTimeMillis() > deadline) {
                    LOG.warn("The Aviz Gateway never got ONES tenant {} (ONES webhook / edge configurator?): no internet access", tenant);
                    return;
                }
                Thread.sleep(TENANT_POLL_MS);
            }
            gw.enableInternet(tenant, ip);
            details.addDetail(networkId, DETAIL_PUBLIC_IP, ip, true);
            LOG.info("ONES tenant {}: internet access on the Aviz Gateway via public address {}", tenant, ip);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("Could not give ONES tenant {} internet access on the Aviz Gateway: {}", tenant, e.getMessage());
        }
    }

    public static void disable(final OnesProviderVO p, final long networkId, final String tenant, final NetworkDetailsDao details) {
        if (details.findDetail(networkId, DETAIL_PUBLIC_IP) == null) {
            return;
        }
        try {
            if (configured(p)) {
                client(p).disableInternet(tenant);
            }
            LOG.info("ONES tenant {}: internet access removed from the Aviz Gateway", tenant);
        } catch (Exception e) {
            LOG.warn("Could not remove ONES tenant {}'s internet access from the Aviz Gateway: {}", tenant, e.getMessage());
        }
        details.removeDetail(networkId, DETAIL_PUBLIC_IP);
    }
}
