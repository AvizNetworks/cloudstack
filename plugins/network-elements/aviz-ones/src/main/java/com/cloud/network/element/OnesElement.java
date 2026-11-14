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
//

package com.cloud.network.element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.AllocateOnesServersCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupOnesCommand;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.commands.AddOnesProviderCmd;
import com.cloud.api.commands.DeleteOnesProviderCmd;
import com.cloud.api.commands.ListOnesProvidersCmd;
import com.cloud.api.commands.UpdateOnesProviderCmd;
import com.cloud.api.response.OnesProviderResponse;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.OnesProviderVO;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.OnesProviderDao;
import com.cloud.network.ones.OnesEdge;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.resource.OnesResource;
import com.cloud.offering.NetworkOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.resource.ResourceStateAdapter;
import com.cloud.resource.ServerResource;
import com.cloud.resource.UnableDeleteHostException;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

/**
 * NetworkElement for the Aviz ONES Spectrum-X fabric: registers/manages the ONES controller as
 * a pseudo-agent host (see {@link OnesResource}), and drives per-host GPU-server tenant
 * membership as VMs are placed on/removed from hypervisor hosts within a ONES-backed guest
 * network. Tenant creation/CIDR assignment itself happens in {@link com.cloud.network.guru.OnesGuestNetworkGuru},
 * since ONES computes the guest IP pool server-side as part of "Create Tenant".
 */
@Component
public class OnesElement extends AdapterBase implements ConnectivityProvider, OnesElementService, ResourceStateAdapter {

    /**
     * The network service provider this plugin adds. CloudStack core has no "AvizOnes" constant;
     * creating a Provider registers it in Provider's list of supported providers, so
     * addNetworkServiceProvider name=AvizOnes works once this class is loaded.
     */
    public static final Provider AVIZ_ONES = new Provider("AvizOnes", false);

    public static final String DETAIL_TENANT_NAME = "onestenantname";
    public static final String DETAIL_ALLOCATED_HOSTS = "onesallocatedhosts";

    private static final Map<Service, Map<Capability, String>> CAPABILITIES = setCapabilities();

    @Inject
    protected ResourceManager resourceMgr;
    @Inject
    protected PhysicalNetworkDao physicalNetworkDao;
    @Inject
    protected PhysicalNetworkServiceProviderDao physicalNetworkServiceProviderDao;
    @Inject
    protected OnesProviderDao onesProviderDao;
    @Inject
    protected HostDetailsDao hostDetailsDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected NetworkDetailsDao networkDetailsDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected NetworkServiceMapDao ntwkSrvcDao;

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public Provider getProvider() {
        return AVIZ_ONES;
    }

    private static Map<Service, Map<Capability, String>> setCapabilities() {
        Map<Service, Map<Capability, String>> capabilities = new HashMap<>();
        // ONES only provides fabric-level (E-W/N-S/Storage) network isolation; it does not
        // provide DHCP, firewall, NAT, or load-balancing services on top of that.
        capabilities.put(Service.Connectivity, null);
        return capabilities;
    }

    protected boolean canHandle(final Network network, final Service service) {
        if (!networkModel.isProviderForNetwork(getProvider(), network.getId())) {
            return false;
        }
        return ntwkSrvcDao.canProviderSupportServiceInNetwork(network.getId(), service, AVIZ_ONES);
    }

    private OnesProviderVO findProviderOrThrow(final Network network) {
        List<OnesProviderVO> devices = onesProviderDao.listByPhysicalNetwork(network.getPhysicalNetworkId());
        if (devices.isEmpty()) {
            throw new CloudRuntimeException("No Aviz ONES provider configured on physical network " + network.getPhysicalNetworkId());
        }
        return devices.get(0);
    }

    private String getTenantName(final Network network) {
        NetworkDetailVO detail = networkDetailsDao.findDetail(network.getId(), DETAIL_TENANT_NAME);
        if (detail == null) {
            throw new CloudRuntimeException("No Aviz ONES tenant is on record for network " + network.getUuid()
                    + " - was it implemented through the Aviz ONES guest network guru?");
        }
        return detail.getValue();
    }

    private List<String> getAllocatedHosts(final long networkId) {
        NetworkDetailVO detail = networkDetailsDao.findDetail(networkId, DETAIL_ALLOCATED_HOSTS);
        if (detail == null || detail.getValue() == null || detail.getValue().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(detail.getValue().split(",")));
    }

    private void setAllocatedHosts(final long networkId, final List<String> hostNames) {
        networkDetailsDao.removeDetail(networkId, DETAIL_ALLOCATED_HOSTS);
        networkDetailsDao.addDetail(networkId, DETAIL_ALLOCATED_HOSTS, String.join(",", hostNames), false);
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        resourceMgr.registerResourceStateAdapter(name, this);
        // OnesResource instances are re-created reflectively on restart and get no injection.
        OnesResource.setProviderDao(onesProviderDao);
        return true;
    }

    @Override
    public boolean implement(final Network network, final NetworkOffering offering, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        // Tenant creation and CIDR/gateway assignment happens in OnesGuestNetworkGuru.implement(),
        // since ONES - not CloudStack - computes the guest IP pool. Nothing further to do here.
        return canHandle(network, Service.Connectivity);
    }

    @Override
    public boolean prepare(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final DeployDestination dest,
            final ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (!canHandle(network, Service.Connectivity) || dest.getHost() == null) {
            return false;
        }

        OnesProviderVO device = findProviderOrThrow(network);
        HostVO onesHost = hostDao.findById(device.getHostId());
        String tenantName = getTenantName(network);
        String serverName = dest.getHost().getName();

        List<String> allocatedHosts = getAllocatedHosts(network.getId());
        if (allocatedHosts.contains(serverName)) {
            return true;
        }

        // shared: a hypervisor carries many guest networks, so ONES trunks the tenant's VLAN on
        // the host's leaf port instead of dedicating the port to this tenant.
        AllocateOnesServersCommand cmd = new AllocateOnesServersCommand(device.getFabricName(), tenantName, Arrays.asList(serverName), true);
        cmd.setWait(900);
        Answer answer = agentMgr.easySend(onesHost.getId(), cmd);
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException("Failed to add host " + serverName + " to Aviz ONES tenant " + tenantName + ": "
                    + (answer != null ? answer.getDetails() : "no answer from Aviz ONES resource"));
        }

        boolean firstHost = allocatedHosts.isEmpty();
        allocatedHosts.add(serverName);
        setAllocatedHosts(network.getId(), allocatedHosts);
        if (firstHost) {
            // the tenant is now on the gateway's way (ONES webhook): give it the internet
            OnesEdge.enable(device, network.getId(), tenantName, networkDetailsDao);
        }
        return true;
    }

    @Override
    public boolean release(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        if (!canHandle(network, Service.Connectivity)) {
            return false;
        }
        // Deallocation of a no-longer-used host from the tenant happens in destroy(), once we can
        // be sure no other NICs/VMs on this network are still active on that host; per-NIC release
        // is intentionally a no-op to avoid pulling a host out from under a sibling VM on the same
        // network and host.
        return true;
    }

    @Override
    public boolean shutdown(final Network network, final ReservationContext context, final boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException {
        return canHandle(network, Service.Connectivity);
    }

    @Override
    public boolean destroy(final Network network, final ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        // Tenant deletion happens in OnesGuestNetworkGuru.shutdown(), mirroring how
        // NiciraNvpElement/NiciraNvpGuestNetworkGuru split whole-network teardown to the guru.
        return canHandle(network, Service.Connectivity);
    }

    @Override
    public boolean isReady(final PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(final PhysicalNetworkServiceProvider provider, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return true;
    }

    @Override
    public boolean verifyServicesCombination(final Set<Service> services) {
        if (!services.contains(Service.Connectivity)) {
            logger.warn("Unable to provide services without Connectivity service enabled for the Aviz ONES element");
            return false;
        }
        return true;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(AddOnesProviderCmd.class);
        cmdList.add(DeleteOnesProviderCmd.class);
        cmdList.add(ListOnesProvidersCmd.class);
        cmdList.add(UpdateOnesProviderCmd.class);
        return cmdList;
    }

    @Override
    @DB
    public OnesProviderVO addOnesProvider(final AddOnesProviderCmd cmd) {
        final Long physicalNetworkId = cmd.getPhysicalNetworkId();
        PhysicalNetworkVO physicalNetwork = physicalNetworkDao.findById(physicalNetworkId);
        if (physicalNetwork == null) {
            throw new InvalidParameterValueException("Could not find physical network with ID: " + physicalNetworkId);
        }
        long zoneId = physicalNetwork.getDataCenterId();

        final PhysicalNetworkServiceProviderVO ntwkSvcProvider =
                physicalNetworkServiceProviderDao.findByServiceProvider(physicalNetwork.getId(), AVIZ_ONES.getName());
        if (ntwkSvcProvider == null) {
            throw new CloudRuntimeException("Network Service Provider " + AVIZ_ONES.getName() + " is not enabled on physical network "
                    + physicalNetworkId + " - enable it first via addNetworkServiceProvider");
        } else if (ntwkSvcProvider.getState() == PhysicalNetworkServiceProvider.State.Shutdown) {
            throw new CloudRuntimeException("Network Service Provider " + ntwkSvcProvider.getProviderName() + " is in Shutdown state on physical network "
                    + physicalNetworkId);
        }

        if (!onesProviderDao.listByPhysicalNetwork(physicalNetworkId).isEmpty()) {
            throw new CloudRuntimeException("An Aviz ONES provider is already configured on this physical network");
        }

        final String deviceName = AVIZ_ONES.getName();
        final String name = cmd.getName();
        final String url = cmd.getUrl();
        final String fabricName = cmd.getFabricName();
        final String loginUrl = cmd.getLoginUrl();
        final String username = cmd.getUsername();
        final String password = cmd.getPassword();
        final String clientCertPath = cmd.getClientCertPath();
        final String clientKeyPath = cmd.getClientKeyPath();
        final String caCertPath = cmd.getCaCertPath();

        Map<String, String> params = new HashMap<>();
        params.put("guid", UUID.randomUUID().toString());
        params.put("zoneId", String.valueOf(zoneId));
        params.put("physicalNetworkId", String.valueOf(physicalNetworkId));
        params.put("name", name);
        params.put("url", url);
        params.put("loginUrl", loginUrl);
        params.put("username", username);
        // The password is deliberately NOT in `params`: that map is persisted in clear text in
        // the generic host_details table. It goes only to the in-memory configure() below and
        // into ones_providers (encrypted); after a restart OnesResource reads it from there.
        // mTLS is optional: only pass these through when supplied, since ONES deployments that
        // authenticate purely via JWT won't have them, and CloudStack's host-details persistence
        // does not tolerate null values in this map.
        if (clientCertPath != null) {
            params.put("clientCertPath", clientCertPath);
        }
        if (clientKeyPath != null) {
            params.put("clientKeyPath", clientKeyPath);
        }
        if (caCertPath != null) {
            params.put("caCertPath", caCertPath);
        }

        Map<String, Object> hostDetails = new HashMap<>(params);
        hostDetails.put("password", password);
        ServerResource resource = new OnesResource();

        try {
            resource.configure(name, hostDetails);
            final Host host = resourceMgr.addHost(zoneId, resource, resource.getType(), params);
            if (host == null) {
                throw new CloudRuntimeException("Failed to add Aviz ONES provider due to internal error.");
            }
            return Transaction.execute((TransactionCallback<OnesProviderVO>) (TransactionStatus status) -> {
                OnesProviderVO onesProviderVO = new OnesProviderVO(host.getId(), physicalNetworkId, ntwkSvcProvider.getProviderName(), deviceName,
                        url, fabricName, loginUrl, username, password, clientCertPath, clientKeyPath, caCertPath);
                onesProviderDao.persist(onesProviderVO);

                DetailVO detail = new DetailVO(host.getId(), "onesproviderid", String.valueOf(onesProviderVO.getId()));
                hostDetailsDao.persist(detail);

                return onesProviderVO;
            });
        } catch (ConfigurationException e) {
            throw new CloudRuntimeException(e.getMessage());
        }
    }

    @Override
    public OnesProviderResponse createOnesProviderResponse(final OnesProviderVO onesProviderVO) {
        OnesProviderResponse response = new OnesProviderResponse();
        response.setId(onesProviderVO.getUuid());
        PhysicalNetwork pnw = ApiDBUtils.findPhysicalNetworkById(onesProviderVO.getPhysicalNetworkId());
        if (pnw != null) {
            response.setPhysicalNetworkId(pnw.getUuid());
        }
        response.setProviderName(onesProviderVO.getProviderName());
        response.setDeviceName(onesProviderVO.getDeviceName());
        response.setUrl(onesProviderVO.getUrl());
        response.setFabricName(onesProviderVO.getFabricName());
        response.setTenantGatewaySuffix(onesProviderVO.getTenantGatewaySuffix());
        response.setGatewayUrl(onesProviderVO.getGatewayUrl());
        response.setPublicIps(onesProviderVO.getPublicIps());
        response.setGatewayTokenSet(onesProviderVO.getGatewayToken() != null);
        response.setObjectName("onesprovider");
        return response;
    }

    @Override
    public boolean deleteOnesProvider(final DeleteOnesProviderCmd cmd) {
        OnesProviderVO device = onesProviderDao.findById(cmd.getId());
        if (device == null) {
            throw new InvalidParameterValueException("Could not find an Aviz ONES provider with id " + cmd.getId());
        }

        Long physicalNetworkId = device.getPhysicalNetworkId();
        List<NetworkVO> networkList = networkDao.listByPhysicalNetwork(physicalNetworkId);
        if (networkList != null) {
            for (NetworkVO network : networkList) {
                if (networkModel.isProviderForNetwork(AVIZ_ONES, network.getId())
                        && network.getState() != Network.State.Shutdown && network.getState() != Network.State.Destroy) {
                    throw new CloudRuntimeException("This Aviz ONES provider cannot be deleted - one or more guest networks are still provisioned on it.");
                }
            }
        }

        HostVO onesHost = hostDao.findById(device.getHostId());
        if (onesHost != null) {
            onesHost.setResourceState(ResourceState.Maintenance);
            hostDao.update(onesHost.getId(), onesHost);
            resourceMgr.deleteHost(onesHost.getId(), false, false);
        }

        onesProviderDao.remove(device.getId());
        return true;
    }

    private static String orNull(final String v, final String current) {
        return v == null ? current : (v.trim().isEmpty() ? null : v.trim());
    }

    @Override
    public OnesProviderVO updateOnesProvider(final UpdateOnesProviderCmd cmd) {
        OnesProviderVO vo = onesProviderDao.findById(cmd.getId());
        if (vo == null) {
            throw new InvalidParameterValueException("Could not find an Aviz ONES provider with id " + cmd.getId());
        }
        vo.setTenantGatewaySuffix(orNull(cmd.getTenantGatewaySuffix(), vo.getTenantGatewaySuffix()));
        vo.setGatewayUrl(orNull(cmd.getGatewayUrl(), vo.getGatewayUrl()));
        vo.setGatewayToken(orNull(cmd.getGatewayToken(), vo.getGatewayToken()));
        vo.setGatewayCaCertPath(orNull(cmd.getGatewayCaCertPath(), vo.getGatewayCaCertPath()));
        vo.setPublicIps(orNull(cmd.getPublicIps(), vo.getPublicIps()));
        if (vo.getPublicIps() != null) {
            try {
                if (OnesEdge.expandAddresses(vo.getPublicIps()).isEmpty()) {
                    throw new InvalidParameterValueException("publicips names no address");
                }
            } catch (RuntimeException e) {
                throw new InvalidParameterValueException("publicips '" + vo.getPublicIps() + "' is not a.b.c.d-a.b.c.e or a comma list");
            }
        }
        onesProviderDao.update(vo.getId(), vo);
        return onesProviderDao.findById(vo.getId());
    }

    @Override
    public List<OnesProviderVO> listOnesProviders(final ListOnesProvidersCmd cmd) {
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        Long id = cmd.getId();
        List<OnesProviderVO> responseList = new ArrayList<>();

        if (physicalNetworkId == null && id == null) {
            throw new InvalidParameterValueException("Either physical network Id or Aviz ONES provider Id must be specified");
        }

        if (id != null) {
            OnesProviderVO device = onesProviderDao.findById(id);
            if (device == null) {
                throw new InvalidParameterValueException("Could not find an Aviz ONES provider with id: " + id);
            }
            responseList.add(device);
        } else {
            PhysicalNetworkVO physicalNetwork = physicalNetworkDao.findById(physicalNetworkId);
            if (physicalNetwork == null) {
                throw new InvalidParameterValueException("Could not find a physical network with id: " + physicalNetworkId);
            }
            responseList = onesProviderDao.listByPhysicalNetwork(physicalNetworkId);
        }

        return responseList;
    }

    @Override
    public HostVO createHostVOForConnectedAgent(final HostVO host, final StartupCommand[] cmd) {
        return null;
    }

    @Override
    public HostVO createHostVOForDirectConnectAgent(final HostVO host, final StartupCommand[] startup, final ServerResource resource,
            final Map<String, String> details, final List<String> hostTags) {
        if (!(startup[0] instanceof StartupOnesCommand)) {
            return null;
        }
        host.setType(Host.Type.L2Networking);
        return host;
    }

    @Override
    public DeleteHostAnswer deleteHost(final HostVO host, final boolean isForced, final boolean isForceDeleteStorage) throws UnableDeleteHostException {
        if (host.getType() != Host.Type.L2Networking) {
            return null;
        }
        return new DeleteHostAnswer(true);
    }
}
