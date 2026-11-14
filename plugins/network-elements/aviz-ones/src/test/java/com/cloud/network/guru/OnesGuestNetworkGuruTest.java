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

package com.cloud.network.guru;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreateOnesTenantAnswer;
import com.cloud.agent.api.CreateOnesTenantCommand;
import com.cloud.agent.api.DeallocateOnesServersCommand;
import com.cloud.agent.api.DeleteOnesTenantCommand;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.NetworkProfile;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.Mode;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.OnesProviderVO;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.OnesProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.OnesElement;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;

@RunWith(MockitoJUnitRunner.Silent.class)
public class OnesGuestNetworkGuruTest {

    private static final long ZONE_ID = 1L;
    private static final long PHYSNET_ID = 200L;
    private static final long OFFERING_ID = 10L;
    private static final long ONES_HOST_ID = 5L;
    private static final long NETWORK_ID = 42L;
    private static final String FABRIC = "fab1";

    @Mock
    private OnesProviderDao onesProviderDao;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private NetworkDetailsDao networkDetailsDao;
    @Mock
    private NetworkOfferingServiceMapDao offeringServiceMapDao;
    @Mock
    private PhysicalNetworkDao physicalNetworkDao;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private NicDao nicDao;

    @Mock
    private OnesProviderVO provider;
    @Mock
    private PhysicalNetworkVO physicalNetwork;
    @Mock
    private DataCenterVO zone;
    @Mock
    private DeploymentPlan plan;
    @Mock
    private NetworkOffering offering;

    private OnesGuestNetworkGuru guru;

    @Before
    public void setUp() {
        guru = new OnesGuestNetworkGuru();
        guru.onesProviderDao = onesProviderDao;
        guru.agentMgr = agentMgr;
        guru.networkDetailsDao = networkDetailsDao;
        guru.ntwkOfferingSrvcDao = offeringServiceMapDao;
        guru._physicalNetworkDao = physicalNetworkDao;
        guru._dcDao = dataCenterDao;
        guru._nicDao = nicDao;

        Mockito.when(provider.getHostId()).thenReturn(ONES_HOST_ID);
        Mockito.when(provider.getFabricName()).thenReturn(FABRIC);
        Mockito.when(onesProviderDao.listByPhysicalNetwork(PHYSNET_ID)).thenReturn(Collections.singletonList(provider));

        Mockito.when(physicalNetwork.getId()).thenReturn(PHYSNET_ID);
        Mockito.when(physicalNetwork.getIsolationMethods()).thenReturn(Collections.singletonList("ONES"));
        Mockito.when(physicalNetworkDao.findById(PHYSNET_ID)).thenReturn(physicalNetwork);
        Mockito.when(zone.getNetworkType()).thenReturn(NetworkType.Advanced);
        Mockito.when(zone.getGuestNetworkCidr()).thenReturn("10.1.1.0/24");
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(zone);
        Mockito.when(plan.getPhysicalNetworkId()).thenReturn(PHYSNET_ID);
        Mockito.when(plan.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(offering.getId()).thenReturn(OFFERING_ID);
        Mockito.when(offering.getTrafficType()).thenReturn(TrafficType.Guest);
        Mockito.when(offering.getGuestType()).thenReturn(GuestType.Isolated);
        Mockito.when(offeringServiceMapDao.canProviderSupportServiceInNetworkOffering(OFFERING_ID, Network.Service.Connectivity,
                OnesElement.AVIZ_ONES)).thenReturn(true);
    }

    // ------------------------------------------------------------------ helpers

    private CreateOnesTenantAnswer onesSays(final String subnet, final String gateway, final boolean anycast) {
        CreateOnesTenantAnswer a = new CreateOnesTenantAnswer(new CreateOnesTenantCommand(FABRIC, "x", null, -1), "{}",
                1001, 20001, 30001, subnet, subnet.replace("0.0/16", "64.0/18"), gateway, anycast);
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(CreateOnesTenantCommand.class))).thenReturn(a);
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(DeleteOnesTenantCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1)));
        return a;
    }

    private <T extends Command> List<T> sent(final Class<T> type) {
        ArgumentCaptor<T> c = ArgumentCaptor.forClass(type);
        Mockito.verify(agentMgr, Mockito.atLeast(0)).easySend(ArgumentMatchers.eq(ONES_HOST_ID), c.capture());
        List<T> out = new java.util.ArrayList<>();
        for (Object o : c.getAllValues()) {
            if (type.isInstance(o)) {
                out.add(type.cast(o));
            }
        }
        return out;
    }

    private void detail(final long networkId, final String key, final String value) {
        NetworkDetailVO d = new NetworkDetailVO(networkId, key, value, false);
        Mockito.when(networkDetailsDao.findDetail(networkId, key)).thenReturn(d);
    }

    private static Network userSpecified(final String cidr, final String gateway) {
        Network n = Mockito.mock(Network.class);
        Mockito.when(n.getCidr()).thenReturn(cidr);
        Mockito.when(n.getGateway()).thenReturn(gateway);
        return n;
    }

    // ------------------------------------------------------------------ tenant names

    @Test
    public void tenantNamesFitOnesRules() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String name = OnesGuestNetworkGuru.newTenantName();
            Assert.assertTrue(name, name.matches("^cs[0-9a-f]{10}$"));
            Assert.assertTrue(name, name.matches("^[A-Za-z][A-Za-z0-9_-]{0,11}$"));     // ONES's own pattern
            Assert.assertTrue("duplicate tenant name " + name, seen.add(name));
        }
    }

    // ------------------------------------------------------------------ canHandle / design

    @Test
    public void ignoresPhysicalNetworksWithoutTheOnesIsolationMethod() {
        Mockito.when(physicalNetwork.getIsolationMethods()).thenReturn(Collections.singletonList("VLAN"));

        Assert.assertNull(guru.design(offering, plan, null, "n1", null, null));
        Mockito.verifyNoInteractions(agentMgr);
    }

    @Test
    public void ignoresOfferingsWithoutAvizOnesConnectivity() {
        Mockito.when(offeringServiceMapDao.canProviderSupportServiceInNetworkOffering(OFFERING_ID, Network.Service.Connectivity,
                OnesElement.AVIZ_ONES)).thenReturn(false);

        Assert.assertNull(guru.design(offering, plan, null, "n1", null, null));
        Mockito.verifyNoInteractions(agentMgr);
    }

    @Test
    public void ignoresSharedAndL2Offerings() {
        Assert.assertFalse(guru.canHandle(offering, NetworkType.Basic, physicalNetwork));
        Mockito.when(offering.getGuestType()).thenReturn(GuestType.Shared);
        Assert.assertFalse(guru.canHandle(offering, NetworkType.Advanced, physicalNetwork));
        Mockito.when(offering.getGuestType()).thenReturn(GuestType.Isolated);
        Assert.assertTrue(guru.canHandle(offering, NetworkType.Advanced, physicalNetwork));
    }

    @Test
    public void designCreatesTheTenantAndTakesItsSubnetAndGateway() {
        onesSays("192.1.0.0/16", "192.1.0.1", true);

        Network n = guru.design(offering, plan, null, "n1", null, null);

        List<CreateOnesTenantCommand> creates = sent(CreateOnesTenantCommand.class);
        Assert.assertEquals(1, creates.size());
        CreateOnesTenantCommand cmd = creates.get(0);
        Assert.assertEquals(FABRIC, cmd.getFabricName());
        Assert.assertTrue(cmd.getTenantName(), cmd.getTenantName().matches("^cs[0-9a-f]{10}$"));
        Assert.assertEquals(-1, cmd.getMaxGpusAllowed());
        Assert.assertEquals(900, cmd.getWait());
        Assert.assertEquals("192.1.0.0/16", n.getCidr());
        Assert.assertEquals("192.1.0.1", n.getGateway());
        Assert.assertNull(n.getNetworkCidr());
        Assert.assertEquals(BroadcastDomainType.Vlan, n.getBroadcastDomainType());
        Assert.assertTrue(sent(DeleteOnesTenantCommand.class).isEmpty());
    }

    @Test
    public void designKeepsARequestedGatewayInsideTheTenantSubnet() {
        onesSays("192.2.0.0/16", "192.2.255.253", false);

        Network n = guru.design(offering, plan, userSpecified("192.2.0.0/16", "192.2.128.3"), "n1", null, null);

        Assert.assertEquals("192.2.0.0/16", n.getCidr());
        Assert.assertEquals("192.2.128.3", n.getGateway());
    }

    @Test
    public void designTreatsARequestedGatewayOutsideTheSubnetAsAPlaceholder() {
        onesSays("192.3.0.0/16", "192.3.0.1", true);

        Network n = guru.design(offering, plan, userSpecified("10.255.255.0/24", "10.255.255.1"), "n1", null, null);

        Assert.assertEquals("192.3.0.0/16", n.getCidr());
        Assert.assertEquals("192.3.0.1", n.getGateway());
    }

    @Test
    public void designUsesTheProvidersTenantGatewaySuffix() {
        Mockito.when(provider.getTenantGatewaySuffix()).thenReturn("128.3");
        onesSays("192.4.0.0/16", "192.4.255.253", false);

        Network n = guru.design(offering, plan, null, "n1", null, null);

        Assert.assertEquals("192.4.128.3", n.getGateway());
    }

    @Test
    public void designDeletesTheTenantWhenThereIsNoUsableGateway() {
        onesSays("192.5.0.0/16", "10.9.9.9", false);       // ONES's gateway is outside its own subnet

        try {
            guru.design(offering, plan, null, "n1", null, null);
            Assert.fail("designed a network with no usable gateway");
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("No usable gateway"));
        }
        List<CreateOnesTenantCommand> creates = sent(CreateOnesTenantCommand.class);
        List<DeleteOnesTenantCommand> deletes = sent(DeleteOnesTenantCommand.class);
        Assert.assertEquals(1, deletes.size());
        Assert.assertEquals(creates.get(0).getTenantName(), deletes.get(0).getTenantName());
    }

    @Test
    public void designDeletesTheTenantWhenOnesGaveNoVlan() {
        CreateOnesTenantAnswer noVlan = new CreateOnesTenantAnswer(new CreateOnesTenantCommand(FABRIC, "x", null, -1), "{}",
                null, null, null, "192.6.0.0/16", "192.6.64.0/18", "192.6.0.1", true);
        onesSays("192.6.0.0/16", "192.6.0.1", true);
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(CreateOnesTenantCommand.class))).thenReturn(noVlan);

        Assert.assertThrows(CloudRuntimeException.class, () -> guru.design(offering, plan, null, "n1", null, null));
        Assert.assertEquals(1, sent(DeleteOnesTenantCommand.class).size());
    }

    @Test
    public void designFailsWhenOnesCannotCreateTheTenant() {
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(CreateOnesTenantCommand.class)))
                .thenAnswer(i -> new CreateOnesTenantAnswer(i.getArgument(1), new Exception("fabric busy")));

        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class,
                () -> guru.design(offering, plan, null, "n1", null, null));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("could not create tenant"));
        Assert.assertTrue(sent(DeleteOnesTenantCommand.class).isEmpty());
    }

    @Test
    public void designFailsWithoutAProvider() {
        Mockito.when(onesProviderDao.listByPhysicalNetwork(PHYSNET_ID)).thenReturn(Collections.emptyList());

        Assert.assertThrows(CloudRuntimeException.class, () -> guru.design(offering, plan, null, "n1", null, null));
        Mockito.verifyNoInteractions(agentMgr);
    }

    // ------------------------------------------------------------------ setup / implement

    @Test
    public void setupRecordsTheTenantOnTheNetwork() {
        onesSays("192.7.0.0/16", "192.7.0.1", true);
        Network designed = guru.design(offering, plan, null, "n1", null, null);
        String tenant = sent(CreateOnesTenantCommand.class).get(0).getTenantName();

        guru.setup(designed, NETWORK_ID);

        Mockito.verify(networkDetailsDao).addDetail(NETWORK_ID, OnesElement.DETAIL_TENANT_NAME, tenant, false);
        Mockito.verify(networkDetailsDao).addDetail(NETWORK_ID, OnesGuestNetworkGuru.DETAIL_VLAN, "1001", false);
        Mockito.verify(networkDetailsDao).addDetail(NETWORK_ID, OnesGuestNetworkGuru.DETAIL_POOL, "192.7.64.0/18", false);
        Mockito.verify(networkDetailsDao).addDetail(NETWORK_ID, OnesGuestNetworkGuru.DETAIL_L3VNI, "30001", false);

        // a second setup of the same CIDR (e.g. a retry) finds nothing pending
        Mockito.clearInvocations(networkDetailsDao);
        guru.setup(designed, NETWORK_ID + 1);
        Mockito.verifyNoInteractions(networkDetailsDao);
    }

    @Test
    public void setupOfANetworkThisGuruDidNotDesignDoesNothing() {
        NetworkVO other = new NetworkVO(TrafficType.Guest, Mode.Dhcp, BroadcastDomainType.Vlan, OFFERING_ID,
                Network.State.Allocated, ZONE_ID, PHYSNET_ID, false);
        other.setCidr("172.16.0.0/24");

        guru.setup(other, NETWORK_ID);

        Mockito.verifyNoInteractions(networkDetailsDao);
    }

    private Network implementing() {
        Network n = Mockito.mock(Network.class);
        Mockito.when(n.getId()).thenReturn(NETWORK_ID);
        Mockito.when(n.getUuid()).thenReturn("net-uuid");
        Mockito.when(n.getState()).thenReturn(Network.State.Implementing);
        Mockito.when(n.getTrafficType()).thenReturn(TrafficType.Guest);
        Mockito.when(n.getMode()).thenReturn(Mode.Dhcp);
        Mockito.when(n.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        Mockito.when(n.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(n.getPhysicalNetworkId()).thenReturn(PHYSNET_ID);
        Mockito.when(n.getCidr()).thenReturn("192.1.0.0/16");
        Mockito.when(n.getGateway()).thenReturn("192.1.128.3");
        return n;
    }

    @Test
    public void implementPutsTheNetworkOnTheTenantVlan() throws Exception {
        detail(NETWORK_ID, OnesGuestNetworkGuru.DETAIL_VLAN, "1001");

        Network n = guru.implement(implementing(), offering, null, null);

        Assert.assertEquals("vlan://1001", n.getBroadcastUri().toString());
        Assert.assertEquals(BroadcastDomainType.Vlan, n.getBroadcastDomainType());
        Assert.assertEquals("192.1.0.0/16", n.getCidr());
        Assert.assertEquals("192.1.128.3", n.getGateway());
    }

    @Test
    public void implementFailsWithoutARecordedVlan() {
        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class,
                () -> guru.implement(implementing(), offering, null, null));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("net-uuid"));
    }

    // ------------------------------------------------------------------ NIC addresses

    @Test
    public void nicAddressesStartAtDotTenOfThePoolAndSkipUsedOnes() throws Exception {
        Network n = implementing();
        Mockito.when(nicDao.listIpAddressInNetwork(NETWORK_ID)).thenReturn(Arrays.asList("192.1.64.10", "192.1.64.11"));

        Assert.assertEquals("192.1.64.12", guru.freeAddress(n, "192.1.64.0/18"));
    }

    @Test
    public void nicAddressesNeverTakeTheGateway() throws Exception {
        Network n = implementing();
        Mockito.when(n.getGateway()).thenReturn("192.1.64.10");
        Mockito.when(nicDao.listIpAddressInNetwork(NETWORK_ID)).thenReturn(Collections.emptyList());

        Assert.assertEquals("192.1.64.11", guru.freeAddress(n, "192.1.64.0/18"));
    }

    @Test
    public void aFullPoolIsReported() {
        Network n = implementing();
        Mockito.when(nicDao.listIpAddressInNetwork(NETWORK_ID))
                .thenReturn(Arrays.asList("10.0.0.10", "10.0.0.11", "10.0.0.12", "10.0.0.13", "10.0.0.14"));

        Assert.assertThrows(InsufficientAddressCapacityException.class, () -> guru.freeAddress(n, "10.0.0.0/28"));
    }

    // ------------------------------------------------------------------ shutdown / trash

    private NetworkProfile profile() {
        NetworkProfile p = Mockito.mock(NetworkProfile.class);
        Mockito.when(p.getId()).thenReturn(NETWORK_ID);
        Mockito.when(p.getPhysicalNetworkId()).thenReturn(PHYSNET_ID);
        return p;
    }

    @Test
    public void shutdownTakesTheNetworksHostsOutOfTheTenant() {
        detail(NETWORK_ID, OnesElement.DETAIL_TENANT_NAME, "cs0123456789");
        detail(NETWORK_ID, OnesElement.DETAIL_ALLOCATED_HOSTS, "gpu-01,gpu-02");
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(DeallocateOnesServersCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1)));

        guru.shutdown(profile(), offering);

        DeallocateOnesServersCommand cmd = sent(DeallocateOnesServersCommand.class).get(0);
        Assert.assertEquals(FABRIC, cmd.getFabricName());
        Assert.assertEquals("cs0123456789", cmd.getTenantName());
        Assert.assertEquals(Arrays.asList("gpu-01", "gpu-02"), cmd.getServerNames());
        Mockito.verify(networkDetailsDao).removeDetail(NETWORK_ID, OnesElement.DETAIL_ALLOCATED_HOSTS);
        Assert.assertTrue(sent(DeleteOnesTenantCommand.class).isEmpty());      // the tenant stays until trash
    }

    @Test
    public void shutdownKeepsTheHostListWhenOnesFails() {
        detail(NETWORK_ID, OnesElement.DETAIL_TENANT_NAME, "cs0123456789");
        detail(NETWORK_ID, OnesElement.DETAIL_ALLOCATED_HOSTS, "gpu-01");
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(DeallocateOnesServersCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1), false, "fabric busy"));

        guru.shutdown(profile(), offering);

        Mockito.verify(networkDetailsDao, Mockito.never()).removeDetail(NETWORK_ID, OnesElement.DETAIL_ALLOCATED_HOSTS);
    }

    @Test
    public void shutdownWithNoHostsSendsNothing() {
        detail(NETWORK_ID, OnesElement.DETAIL_TENANT_NAME, "cs0123456789");

        guru.shutdown(profile(), offering);

        Mockito.verifyNoInteractions(agentMgr);
    }

    private Network trashed() {
        Network n = Mockito.mock(Network.class);
        Mockito.when(n.getId()).thenReturn(NETWORK_ID);
        Mockito.when(n.getPhysicalNetworkId()).thenReturn(PHYSNET_ID);
        return n;
    }

    @Test
    public void trashDeletesTheTenant() {
        detail(NETWORK_ID, OnesElement.DETAIL_TENANT_NAME, "cs0123456789");
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(DeleteOnesTenantCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1)));

        Assert.assertTrue(guru.trash(trashed(), offering));

        DeleteOnesTenantCommand cmd = sent(DeleteOnesTenantCommand.class).get(0);
        Assert.assertEquals(FABRIC, cmd.getFabricName());
        Assert.assertEquals("cs0123456789", cmd.getTenantName());
        Mockito.verify(networkDetailsDao).removeDetails(NETWORK_ID);
    }

    @Test
    public void trashFailsAndKeepsTheDetailsWhenOnesCannotDelete() {
        detail(NETWORK_ID, OnesElement.DETAIL_TENANT_NAME, "cs0123456789");
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(DeleteOnesTenantCommand.class)))
                .thenReturn(null);

        Assert.assertFalse(guru.trash(trashed(), offering));
        Mockito.verify(networkDetailsDao, Mockito.never()).removeDetails(NETWORK_ID);
    }

    @Test
    public void trashOfANetworkWithoutATenantSendsNothing() {
        Assert.assertTrue(guru.trash(trashed(), offering));
        Mockito.verifyNoInteractions(agentMgr);
    }
}
