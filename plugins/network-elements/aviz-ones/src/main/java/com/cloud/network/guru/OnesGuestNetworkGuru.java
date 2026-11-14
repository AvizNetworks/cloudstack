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
package com.cloud.network.guru;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CreateOnesTenantAnswer;
import com.cloud.agent.api.CreateOnesTenantCommand;
import com.cloud.agent.api.DeallocateOnesServersCommand;
import com.cloud.agent.api.DeleteOnesTenantCommand;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.State;
import com.cloud.network.NetworkProfile;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.OnesProviderVO;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetwork.IsolationMethod;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.OnesProviderDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.OnesElement;
import com.cloud.network.ones.OnesEdge;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

/**
 * One CloudStack isolated guest network = one ONES tenant.
 *
 * ONES, not CloudStack, chooses everything about a tenant (VLAN, VNIs, subnet, compute IP pool),
 * so the tenant is created when the network is created (design), and the network takes ONES's
 * values from the start:
 *
 *   broadcast   vlan://&lt;tenant vlanId&gt;: the VLAN ONES trunks to the hypervisors' leaf ports.
 *               The hypervisor's guest bridge tags it like any CloudStack VLAN network; the leaf
 *               maps it into the tenant's VRF/VNIs.
 *   cidr        the tenant subnet (ONES vlanSubnetCPU, e.g. 192.1.0.0/16)
 *   gateway     a gateway given with createNetwork if it lies in that subnet, else the gateway
 *               ONES reports (see below)
 *   VM addresses  only from the tenant's compute pool (ONES tenantIpPool, e.g. 192.1.64.0/18);
 *               the rest of the subnet is ONES's (leaf SVIs and the like)
 *
 * Gateway caveat: with tenant IPAM/DHCP off, ONES reports a placeholder gateway (a.b.255.253)
 * that nothing answers on, and does not expose the leaf SVI that actually routes the tenant.
 * Until ONES exposes it (or enables its anycast gateway for such tenants), pass gateway= and
 * netmask= (the tenant subnet's) to createNetwork.
 *
 * The network's lifecycle maps onto the tenant like this:
 *   design     create the tenant (async; waits for ONES)
 *   setup      record tenant name, VLAN, pool in network_details
 *   implement  put the network on the tenant's VLAN
 *   allocate   pick NIC addresses from the tenant pool
 *   (element prepare adds a VM's host to the tenant, shared)
 *   shutdown   take the hosts out of the tenant (the network has no running VMs)
 *   trash      delete the tenant
 */
public class OnesGuestNetworkGuru extends GuestNetworkGuru {

    public static final String DETAIL_VLAN = "onesvlan";
    public static final String DETAIL_POOL = "onesippool";
    public static final String DETAIL_L3VNI = "onesl3vni";

    /** ONES: ^[A-Za-z][A-Za-z0-9_-]{0,11}$ */
    private static final int TENANT_NAME_HEX = 10;
    private static final int ONES_TIMEOUT_S = 900;

    /** Tenant made in design(), waiting for setup() to know the network id. Keyed by CIDR. */
    private static final Map<String, CreateOnesTenantAnswer> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, String> PENDING_NAMES = new ConcurrentHashMap<>();

    @Inject
    protected OnesProviderDao onesProviderDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected NetworkDetailsDao networkDetailsDao;
    @Inject
    protected NetworkOfferingServiceMapDao ntwkOfferingSrvcDao;

    public OnesGuestNetworkGuru() {
        super();
        // Its own isolation-method tag: CloudStack picks a guru by the physical network's
        // isolation method, so a ONES physical network (isolationmethods=ONES) is never claimed
        // by the stock VLAN gurus, and ordinary VLAN physical networks are never claimed by this.
        _isolationMethods = new IsolationMethod[]{new IsolationMethod("ONES", OnesElement.AVIZ_ONES.getName())};
    }

    @Override
    protected boolean canHandle(final NetworkOffering offering, final NetworkType networkType, final PhysicalNetwork physicalNetwork) {
        return networkType == NetworkType.Advanced
                && isMyTrafficType(offering.getTrafficType())
                && offering.getGuestType() == GuestType.Isolated
                && isMyIsolationMethod(physicalNetwork)
                && ntwkOfferingSrvcDao.canProviderSupportServiceInNetworkOffering(offering.getId(), Network.Service.Connectivity,
                        OnesElement.AVIZ_ONES);
    }

    private OnesProviderVO findProviderOrThrow(final long physicalNetworkId) {
        List<OnesProviderVO> devices = onesProviderDao.listByPhysicalNetwork(physicalNetworkId);
        if (devices.isEmpty()) {
            throw new CloudRuntimeException("No Aviz ONES provider is configured on physical network " + physicalNetworkId);
        }
        return devices.get(0);
    }

    private String detail(final long networkId, final String key) {
        NetworkDetailVO d = networkDetailsDao.findDetail(networkId, key);
        return d != null ? d.getValue() : null;
    }

    static String newTenantName() {
        return "cs" + UUID.randomUUID().toString().replace("-", "").substring(0, TENANT_NAME_HEX);
    }

    @Override
    public Network design(final NetworkOffering offering, final DeploymentPlan plan, final Network userSpecified, final String name,
            final Long vpcId, final Account owner) {
        PhysicalNetworkVO physnet = _physicalNetworkDao.findById(plan.getPhysicalNetworkId());
        if (physnet == null || !canHandle(offering, _dcDao.findById(plan.getDataCenterId()).getNetworkType(), physnet)) {
            logger.debug("Aviz ONES guru is not designing network {}", name);
            return null;
        }
        OnesProviderVO device = findProviderOrThrow(physnet.getId());

        NetworkVO network = (NetworkVO) super.design(offering, plan, userSpecified, name, vpcId, owner);
        if (network == null) {
            return null;
        }

        String tenantName = newTenantName();
        CreateOnesTenantCommand cmd = new CreateOnesTenantCommand(device.getFabricName(), tenantName,
                "CloudStack network " + (name != null ? name : ""), -1);
        cmd.setWait(ONES_TIMEOUT_S);
        Answer raw = agentMgr.easySend(device.getHostId(), cmd);
        if (!(raw instanceof CreateOnesTenantAnswer) || !raw.getResult()) {
            throw new CloudRuntimeException("Aviz ONES could not create tenant " + tenantName + " for network " + name + ": "
                    + (raw != null ? raw.getDetails() : "no answer from the Aviz ONES resource"));
        }
        CreateOnesTenantAnswer t = (CreateOnesTenantAnswer) raw;
        String gateway;
        try {
            gateway = chooseGateway(tenantName, t, userSpecified != null ? userSpecified.getGateway() : null,
                    device.getTenantGatewaySuffix());
        } catch (RuntimeException e) {
            deleteTenant(device, tenantName);     // never leave an orphan tenant in ONES
            throw e;
        }
        String subnet = t.getSubnet();
        network.setBroadcastDomainType(BroadcastDomainType.Vlan);
        network.setCidr(subnet);
        network.setNetworkCidr(null);
        network.setGateway(gateway);
        PENDING.put(subnet, t);
        PENDING_NAMES.put(subnet, tenantName);
        logger.info("ONES tenant {} for network {}: vlan {}, subnet {}, pool {}, gateway {}, l3vni {}", tenantName, name,
                t.getVlanId(), subnet, t.getIpPool(), gateway, t.getL3Vni());
        return network;
    }

    /**
     * The network's gateway. CloudStack makes createNetwork carry gateway/netmask for an isolated
     * network without SourceNat - before ONES has chosen the tenant's subnet. So: a gateway INSIDE
     * the subnet ONES assigned (e.g. the leaf SVI) is used; one outside it is a placeholder
     * meaning "let ONES decide" (pass e.g. 10.255.255.1/255.255.255.0: CloudStack wants RFC 1918) and ONES's gateway is used.
     */
    private String chooseGateway(final String tenantName, final CreateOnesTenantAnswer t, final String requested,
            final String suffix) {
        if (t.getVlanId() == null || t.getSubnet() == null || t.getIpPool() == null) {
            throw new CloudRuntimeException("Aviz ONES tenant " + tenantName + " has no VLAN, subnet or compute IP pool: " + t.getDetails());
        }
        String subnet = t.getSubnet();
        if (requested != null && NetUtils.isIpWithInCidrRange(requested, subnet)) {
            return requested;
        }
        if (requested != null) {
            logger.info("gateway {} is outside ONES tenant {}'s subnet {}: taken as a placeholder, using ONES's gateway {}",
                    requested, tenantName, subnet, t.getGateway());
        }
        if (suffix != null && !suffix.isEmpty() && !t.isAnycastGateway()) {
            // lab/demo provider setting: the tenant's leaf SVI, e.g. 192.T + ".128.3"
            String[] head = subnet.split("/")[0].split("\\.");
            int keep = 4 - suffix.split("\\.").length;
            String g = String.join(".", java.util.Arrays.copyOfRange(head, 0, keep)) + "." + suffix;
            if (NetUtils.isValidIp4(g) && NetUtils.isIpWithInCidrRange(g, subnet)) {
                logger.info("ONES tenant {}: gateway {} from the provider's tenant gateway suffix {} (ONES reports {})",
                        tenantName, g, suffix, t.getGateway());
                return g;
            }
            logger.warn("tenant gateway suffix {} does not give an address in {}; ignored", suffix, subnet);
        }
        String gateway = t.getGateway();
        if (gateway == null || !NetUtils.isIpWithInCidrRange(gateway, subnet)) {
            throw new CloudRuntimeException("No usable gateway for ONES tenant " + tenantName + " (subnet " + subnet
                    + ", ONES gateway " + gateway + "); pass gateway= inside the subnet to createNetwork");
        }
        if (!t.isAnycastGateway()) {
            logger.warn("ONES tenant {} reports gateway {} but has no anycast gateway: that address is likely not configured "
                    + "on the fabric (east-west works, nothing routes out). Pass gateway= (the leaf SVI) to createNetwork.",
                    tenantName, gateway);
        }
        return gateway;
    }

    private void deleteTenant(final OnesProviderVO device, final String tenantName) {
        DeleteOnesTenantCommand cmd = new DeleteOnesTenantCommand(device.getFabricName(), tenantName);
        cmd.setWait(ONES_TIMEOUT_S);
        Answer a = agentMgr.easySend(device.getHostId(), cmd);
        if (a == null || !a.getResult()) {
            logger.error("Could not delete ONES tenant {}: {}", tenantName, a != null ? a.getDetails() : "no answer");
        }
    }

    @Override
    public void setup(final Network network, final long networkId) {
        super.setup(network, networkId);
        CreateOnesTenantAnswer t = network.getCidr() != null ? PENDING.remove(network.getCidr()) : null;
        String tenantName = network.getCidr() != null ? PENDING_NAMES.remove(network.getCidr()) : null;
        if (t == null || tenantName == null) {
            return;
        }
        networkDetailsDao.addDetail(networkId, OnesElement.DETAIL_TENANT_NAME, tenantName, false);
        networkDetailsDao.addDetail(networkId, DETAIL_VLAN, String.valueOf(t.getVlanId()), false);
        networkDetailsDao.addDetail(networkId, DETAIL_POOL, t.getIpPool(), false);
        if (t.getL3Vni() != null) {
            networkDetailsDao.addDetail(networkId, DETAIL_L3VNI, String.valueOf(t.getL3Vni()), false);
        }
    }

    @Override
    public Network implement(final Network network, final NetworkOffering offering, final DeployDestination dest, final ReservationContext context)
            throws InsufficientVirtualNetworkCapacityException {
        assert network.getState() == State.Implementing : "Why are we implementing " + network;
        String vlan = detail(network.getId(), DETAIL_VLAN);
        if (vlan == null) {
            throw new CloudRuntimeException("No ONES VLAN is on record for network " + network.getUuid());
        }
        NetworkVO implemented = new NetworkVO(network.getTrafficType(), network.getMode(), BroadcastDomainType.Vlan, network.getNetworkOfferingId(),
                State.Allocated, network.getDataCenterId(), network.getPhysicalNetworkId(), offering.isRedundantRouter());
        implemented.setCidr(network.getCidr());
        implemented.setGateway(network.getGateway());
        implemented.setBroadcastUri(BroadcastDomainType.Vlan.toUri(vlan));
        return implemented;
    }

    /** NIC addresses come from the tenant's compute pool, never from the rest of the subnet. */
    @Override
    public NicProfile allocate(final Network network, final NicProfile nic, final VirtualMachineProfile vm)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {
        String pool = detail(network.getId(), DETAIL_POOL);
        if (nic != null && nic.getRequestedIPv4() == null && pool != null && network.getGateway() != null) {
            nic.setRequestedIPv4(freeAddress(network, pool));
        }
        return super.allocate(network, nic, vm);
    }

    String freeAddress(final Network network, final String pool) throws InsufficientAddressCapacityException {
        Set<String> used = new HashSet<>(_nicDao.listIpAddressInNetwork(network.getId()));
        used.add(network.getGateway());
        String[] range = NetUtils.getIpRangeFromCidr(pool.split("/")[0], Integer.parseInt(pool.split("/")[1]));
        long first = NetUtils.ip2Long(range[0]);
        long last = NetUtils.ip2Long(range[1]);
        for (long ip = first + 9; ip <= last; ip++) {      // .10 up: leave the pool's bottom to the operator
            String a = NetUtils.long2Ip(ip);
            if (!used.contains(a)) {
                return a;
            }
        }
        throw new InsufficientAddressCapacityException("ONES tenant pool " + pool + " is full", Network.class, network.getId());
    }

    private List<String> allocatedHosts(final long networkId) {
        String v = detail(networkId, OnesElement.DETAIL_ALLOCATED_HOSTS);
        return v == null || v.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(v.split(",")));
    }

    /** Takes every host this network added out of the tenant. */
    private void removeHosts(final long networkId, final OnesProviderVO device, final String tenantName) {
        List<String> hosts = allocatedHosts(networkId);
        if (hosts.isEmpty()) {
            return;
        }
        DeallocateOnesServersCommand cmd = new DeallocateOnesServersCommand(device.getFabricName(), tenantName, hosts);
        cmd.setWait(ONES_TIMEOUT_S);
        Answer a = agentMgr.easySend(device.getHostId(), cmd);
        if (a == null || !a.getResult()) {
            logger.warn("Could not remove hosts {} from ONES tenant {}: {}", hosts, tenantName, a != null ? a.getDetails() : "no answer");
            return;
        }
        networkDetailsDao.removeDetail(networkId, OnesElement.DETAIL_ALLOCATED_HOSTS);
    }

    @Override
    public void shutdown(final NetworkProfile profile, final NetworkOffering offering) {
        String tenantName = detail(profile.getId(), OnesElement.DETAIL_TENANT_NAME);
        if (tenantName != null) {
            OnesProviderVO device = findProviderOrThrow(profile.getPhysicalNetworkId());
            OnesEdge.disable(device, profile.getId(), tenantName, networkDetailsDao);   // before the tenant leaves the gateway
            removeHosts(profile.getId(), device, tenantName);
        }
        super.shutdown(profile, offering);
    }

    @Override
    public boolean trash(final Network network, final NetworkOffering offering) {
        String tenantName = detail(network.getId(), OnesElement.DETAIL_TENANT_NAME);
        if (tenantName != null) {
            OnesProviderVO device = findProviderOrThrow(network.getPhysicalNetworkId());
            OnesEdge.disable(device, network.getId(), tenantName, networkDetailsDao);
            removeHosts(network.getId(), device, tenantName);
            DeleteOnesTenantCommand cmd = new DeleteOnesTenantCommand(device.getFabricName(), tenantName);
            cmd.setWait(ONES_TIMEOUT_S);
            Answer a = agentMgr.easySend(device.getHostId(), cmd);
            if (a == null || !a.getResult()) {
                logger.error("Could not delete ONES tenant {}: {}", tenantName, a != null ? a.getDetails() : "no answer");
                return false;
            }
            networkDetailsDao.removeDetails(network.getId());
        }
        return super.trash(network, offering);
    }
}
