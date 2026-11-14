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
package com.cloud.agent.api;

/**
 * What ONES allocated for a tenant, read back with GET /fabrics/{f}/tenants/{t} once the
 * (async) create operation has finished.
 *
 *   vlanId       the north-south VLAN: the VLAN the leaf ports trunk to the hypervisors, i.e.
 *                the VLAN CloudStack puts the guest network on (vlan://vlanId)
 *   l2Vni        east-west VXLAN VNI (ONES "vniId"), internal to the fabric
 *   l3Vni        the tenant VRF's L3 VNI (ONES "nsVniId")
 *   subnet       the tenant subnet (ONES "vlanSubnetCPU", e.g. 192.1.0.0/16): the guest CIDR
 *   ipPool       the part of the subnet meant for compute (ONES "tenantIpPool", e.g.
 *                192.1.64.0/18); VM addresses are taken from here only
 *   gateway      what ONES reports as the tenant gateway (bare IP)
 *   anycastGateway  whether ONES configured that gateway on the leaves (ONES only does so
 *                in its tenant-DHCP mode; otherwise the reported gateway is a placeholder)
 */
public class CreateOnesTenantAnswer extends Answer {

    private final Integer vlanId;
    private final Integer l2Vni;
    private final Integer l3Vni;
    private final String subnet;
    private final String ipPool;
    private final String gateway;
    private final boolean anycastGateway;

    public CreateOnesTenantAnswer(final Command command, final String details, final Integer vlanId, final Integer l2Vni,
            final Integer l3Vni, final String subnet, final String ipPool, final String gateway, final boolean anycastGateway) {
        super(command, true, details);
        this.vlanId = vlanId;
        this.l2Vni = l2Vni;
        this.l3Vni = l3Vni;
        this.subnet = subnet;
        this.ipPool = ipPool;
        this.gateway = gateway;
        this.anycastGateway = anycastGateway;
    }

    public CreateOnesTenantAnswer(final Command command, final Exception e) {
        super(command, e);
        this.vlanId = null;
        this.l2Vni = null;
        this.l3Vni = null;
        this.subnet = null;
        this.ipPool = null;
        this.gateway = null;
        this.anycastGateway = false;
    }

    public Integer getVlanId() {
        return vlanId;
    }

    public Integer getL2Vni() {
        return l2Vni;
    }

    public Integer getL3Vni() {
        return l3Vni;
    }

    public String getSubnet() {
        return subnet;
    }

    public String getIpPool() {
        return ipPool;
    }

    public String getGateway() {
        return gateway;
    }

    public boolean isAnycastGateway() {
        return anycastGateway;
    }
}
