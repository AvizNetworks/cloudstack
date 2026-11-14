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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.AllocateOnesServersCommand;
import com.cloud.agent.api.Answer;
import com.cloud.api.commands.ListOnesProvidersCmd;
import com.cloud.api.commands.UpdateOnesProviderCmd;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.OnesProviderVO;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.OnesProviderDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.Silent.class)
public class OnesElementTest {

    private static final long NETWORK_ID = 42L;
    private static final long PHYSNET_ID = 200L;
    private static final long ONES_HOST_ID = 5L;
    private static final String TENANT = "cs0123456789";

    @Mock
    private OnesProviderDao onesProviderDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private NetworkDetailsDao networkDetailsDao;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private NetworkServiceMapDao ntwkSrvcDao;

    @Mock
    private OnesProviderVO provider;
    @Mock
    private HostVO onesHost;
    @Mock
    private Network network;
    @Mock
    private DeployDestination dest;
    @Mock
    private Host gpuHost;

    private OnesElement element;

    @Before
    public void setUp() {
        element = new OnesElement();
        element.onesProviderDao = onesProviderDao;
        element.hostDao = hostDao;
        element.agentMgr = agentMgr;
        element.networkDetailsDao = networkDetailsDao;
        element.networkModel = networkModel;
        element.ntwkSrvcDao = ntwkSrvcDao;

        Mockito.when(network.getId()).thenReturn(NETWORK_ID);
        Mockito.when(network.getPhysicalNetworkId()).thenReturn(PHYSNET_ID);
        Mockito.when(networkModel.isProviderForNetwork(OnesElement.AVIZ_ONES, NETWORK_ID)).thenReturn(true);
        Mockito.when(ntwkSrvcDao.canProviderSupportServiceInNetwork(NETWORK_ID, Service.Connectivity, OnesElement.AVIZ_ONES)).thenReturn(true);
        Mockito.when(provider.getHostId()).thenReturn(ONES_HOST_ID);
        Mockito.when(provider.getFabricName()).thenReturn("fab1");
        Mockito.when(onesProviderDao.listByPhysicalNetwork(PHYSNET_ID)).thenReturn(Collections.singletonList(provider));
        Mockito.when(onesHost.getId()).thenReturn(ONES_HOST_ID);
        Mockito.when(hostDao.findById(ONES_HOST_ID)).thenReturn(onesHost);
        Mockito.when(gpuHost.getName()).thenReturn("gpu-02");
        Mockito.when(dest.getHost()).thenReturn(gpuHost);
        detail(OnesElement.DETAIL_TENANT_NAME, TENANT);
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(AllocateOnesServersCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1)));
    }

    private void detail(final String key, final String value) {
        Mockito.when(networkDetailsDao.findDetail(NETWORK_ID, key)).thenReturn(new NetworkDetailVO(NETWORK_ID, key, value, false));
    }

    private AllocateOnesServersCommand allocateSent() {
        ArgumentCaptor<AllocateOnesServersCommand> c = ArgumentCaptor.forClass(AllocateOnesServersCommand.class);
        Mockito.verify(agentMgr).easySend(ArgumentMatchers.eq(ONES_HOST_ID), c.capture());
        return c.getValue();
    }

    // ------------------------------------------------------------------ prepare: hosts join the tenant

    @Test
    public void theFirstVmOnAHostAddsTheHostToTheTenantShared() throws Exception {
        Assert.assertTrue(element.prepare(network, null, null, dest, null));

        AllocateOnesServersCommand cmd = allocateSent();
        Assert.assertEquals("fab1", cmd.getFabricName());
        Assert.assertEquals(TENANT, cmd.getTenantName());
        Assert.assertEquals(Collections.singletonList("gpu-02"), cmd.getServerNames());
        Assert.assertTrue(cmd.isShared());
        Assert.assertEquals(900, cmd.getWait());
        Mockito.verify(networkDetailsDao).removeDetail(NETWORK_ID, OnesElement.DETAIL_ALLOCATED_HOSTS);
        Mockito.verify(networkDetailsDao).addDetail(NETWORK_ID, OnesElement.DETAIL_ALLOCATED_HOSTS, "gpu-02", false);
    }

    @Test
    public void aSecondHostIsAppended() throws Exception {
        detail(OnesElement.DETAIL_ALLOCATED_HOSTS, "gpu-01");

        Assert.assertTrue(element.prepare(network, null, null, dest, null));

        Assert.assertEquals(Collections.singletonList("gpu-02"), allocateSent().getServerNames());
        Mockito.verify(networkDetailsDao).addDetail(NETWORK_ID, OnesElement.DETAIL_ALLOCATED_HOSTS, "gpu-01,gpu-02", false);
    }

    @Test
    public void aHostAlreadyInTheTenantIsNotAddedAgain() throws Exception {
        detail(OnesElement.DETAIL_ALLOCATED_HOSTS, "gpu-01,gpu-02");

        Assert.assertTrue(element.prepare(network, null, null, dest, null));

        Mockito.verifyNoInteractions(agentMgr);
        Mockito.verify(networkDetailsDao, Mockito.never()).addDetail(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
    }

    @Test
    public void aRefusedAllocationFailsTheDeploymentAndRecordsNothing() {
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(AllocateOnesServersCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1), false, "server not in fabric"));

        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class,
                () -> element.prepare(network, null, null, dest, null));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("server not in fabric"));
        Mockito.verify(networkDetailsDao, Mockito.never()).addDetail(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
    }

    @Test
    public void noAnswerFromTheResourceFailsTheDeployment() {
        Mockito.when(agentMgr.easySend(ArgumentMatchers.eq(ONES_HOST_ID), ArgumentMatchers.any(AllocateOnesServersCommand.class)))
                .thenReturn(null);

        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class,
                () -> element.prepare(network, null, null, dest, null));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("no answer"));
    }

    @Test
    public void aNetworkWithoutATenantRecordFails() {
        Mockito.when(networkDetailsDao.findDetail(NETWORK_ID, OnesElement.DETAIL_TENANT_NAME)).thenReturn(null);

        Assert.assertThrows(CloudRuntimeException.class, () -> element.prepare(network, null, null, dest, null));
        Mockito.verifyNoInteractions(agentMgr);
    }

    @Test
    public void networksOfOtherProvidersAreLeftAlone() throws Exception {
        Mockito.when(networkModel.isProviderForNetwork(OnesElement.AVIZ_ONES, NETWORK_ID)).thenReturn(false);

        Assert.assertFalse(element.prepare(network, null, null, dest, null));
        Assert.assertFalse(element.implement(network, null, null, null));
        Assert.assertFalse(element.release(network, null, null, null));
        Mockito.verifyNoInteractions(agentMgr);
    }

    @Test
    public void aPlacementWithoutAHostDoesNothing() throws Exception {
        Mockito.when(dest.getHost()).thenReturn(null);

        Assert.assertFalse(element.prepare(network, null, null, dest, null));
        Mockito.verifyNoInteractions(agentMgr);
    }

    @Test
    public void releasingANicNeverTakesTheHostOut() throws Exception {
        detail(OnesElement.DETAIL_ALLOCATED_HOSTS, "gpu-02");

        Assert.assertTrue(element.release(network, null, null, null));
        Assert.assertTrue(element.shutdown(network, null, true));
        Assert.assertTrue(element.destroy(network, null));
        Mockito.verifyNoInteractions(agentMgr);
    }

    // ------------------------------------------------------------------ provider description

    @Test
    public void providesConnectivityOnly() {
        Assert.assertEquals("AvizOnes", element.getProvider().getName());
        Assert.assertEquals(Collections.singleton(Service.Connectivity), element.getCapabilities().keySet());
        Assert.assertTrue(element.verifyServicesCombination(new HashSet<>(Arrays.asList(Service.Connectivity, Service.Dhcp))));
        Assert.assertFalse(element.verifyServicesCombination(Collections.singleton(Service.Dhcp)));
        Assert.assertEquals(4, element.getCommands().size());
    }

    // ------------------------------------------------------------------ provider API

    private UpdateOnesProviderCmd update(final String publicIps) {
        UpdateOnesProviderCmd cmd = Mockito.mock(UpdateOnesProviderCmd.class);
        Mockito.when(cmd.getId()).thenReturn(7L);
        Mockito.when(cmd.getPublicIps()).thenReturn(publicIps);
        return cmd;
    }

    @Test
    public void updateValidatesAndStoresThePublicAddressList() {
        OnesProviderVO vo = new OnesProviderVO();
        ReflectionTestUtils.setField(vo, "id", 7L);       // as the DAO would return it
        Mockito.when(onesProviderDao.findById(7L)).thenReturn(vo);

        element.updateOnesProvider(update(" 203.0.113.10-203.0.113.12 "));
        Assert.assertEquals("203.0.113.10-203.0.113.12", vo.getPublicIps());
        Mockito.verify(onesProviderDao).update(7L, vo);

        element.updateOnesProvider(update(null));           // not given: unchanged
        Assert.assertEquals("203.0.113.10-203.0.113.12", vo.getPublicIps());

        element.updateOnesProvider(update(""));             // blank: cleared
        Assert.assertNull(vo.getPublicIps());
    }

    @Test
    public void updateRejectsABadPublicAddressList() {
        Mockito.when(onesProviderDao.findById(7L)).thenReturn(new OnesProviderVO());

        Assert.assertThrows(InvalidParameterValueException.class, () -> element.updateOnesProvider(update("203.0.113.x")));
        Mockito.verify(onesProviderDao, Mockito.never()).update(ArgumentMatchers.anyLong(), ArgumentMatchers.any());
    }

    @Test
    public void updateOfAnUnknownProviderFails() {
        Assert.assertThrows(InvalidParameterValueException.class, () -> element.updateOnesProvider(update(null)));
    }

    @Test
    public void listNeedsAnIdOrAPhysicalNetwork() {
        ListOnesProvidersCmd cmd = Mockito.mock(ListOnesProvidersCmd.class);
        Assert.assertThrows(InvalidParameterValueException.class, () -> element.listOnesProviders(cmd));

        Mockito.when(cmd.getId()).thenReturn(7L);
        Mockito.when(onesProviderDao.findById(7L)).thenReturn(provider);
        Assert.assertEquals(Collections.singletonList(provider), element.listOnesProviders(cmd));
    }
}
