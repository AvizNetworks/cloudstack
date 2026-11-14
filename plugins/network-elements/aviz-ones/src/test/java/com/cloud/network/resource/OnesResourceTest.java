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

package com.cloud.network.resource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.AllocateOnesServersCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CreateOnesTenantAnswer;
import com.cloud.agent.api.CreateOnesTenantCommand;
import com.cloud.agent.api.DeallocateOnesServersCommand;
import com.cloud.agent.api.DeleteOnesTenantCommand;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupOnesCommand;
import com.cloud.agent.api.UnsupportedAnswer;
import com.cloud.host.Host;
import com.cloud.network.OnesProviderVO;
import com.cloud.network.dao.OnesProviderDao;
import com.cloud.network.ones.OnesApiClient;

@RunWith(MockitoJUnitRunner.class)
public class OnesResourceTest {

    @Mock
    private OnesApiClient client;
    @Mock
    private OnesProviderDao providerDao;

    private OnesResource resource;

    private static Map<String, Object> params() {
        Map<String, Object> p = new HashMap<>();
        p.put("name", "ones1");
        p.put("guid", "guid-1");
        p.put("zoneId", "1");
        p.put("url", "https://ones-fm:8443");
        p.put("loginUrl", "https://ones-fm/api/user/login");
        p.put("username", "admin");
        p.put("password", "secret");
        p.put("physicalNetworkId", "200");
        return p;
    }

    @Before
    public void setUp() throws ConfigurationException {
        resource = new OnesResource();
        Assert.assertTrue(resource.configure("ones1", params()));
        resource.onesApiClient = client;
    }

    @After
    public void tearDown() {
        OnesResource.setProviderDao(null);
    }

    // ------------------------------------------------------------------ configure

    private static void assertConfigureFails(final Map<String, Object> p) {
        try {
            new OnesResource().configure("ones1", p);
            Assert.fail("configure accepted " + p);
        } catch (ConfigurationException expected) {
            // ok
        }
    }

    @Test
    public void configureRequiresNameGuidAndUrl() {
        for (String key : new String[]{"name", "guid", "url"}) {
            Map<String, Object> p = params();
            p.remove(key);
            assertConfigureFails(p);
        }
    }

    @Test
    public void configureRequiresAPasswordOrAPhysicalNetwork() throws ConfigurationException {
        Map<String, Object> p = params();
        p.remove("password");
        p.remove("physicalNetworkId");
        assertConfigureFails(p);

        // after a restart: no password in host_details, resolved later from ones_providers
        p.put("physicalNetworkId", "200");
        OnesResource r = new OnesResource();
        Assert.assertTrue(r.configure("ones1", p));
        Assert.assertNull(r.onesApiClient);
    }

    @Test
    public void configureRequiresClientCertAndKeyTogether() {
        Map<String, Object> p = params();
        p.put("clientCertPath", "/etc/ones/client.crt");
        assertConfigureFails(p);
        p.remove("clientCertPath");
        p.put("clientKeyPath", "/etc/ones/client.key");
        assertConfigureFails(p);
    }

    @Test
    public void initializeReportsAnL2NetworkingHost() {
        StartupCommand[] startup = resource.initialize();

        Assert.assertEquals(1, startup.length);
        Assert.assertTrue(startup[0] instanceof StartupOnesCommand);
        Assert.assertEquals("guid-1", startup[0].getGuid());
        Assert.assertEquals("ones1", startup[0].getName());
        Assert.assertEquals("1", startup[0].getDataCenter());
        Assert.assertEquals(Host.Type.L2Networking, resource.getType());
        Assert.assertEquals(Host.Type.L2Networking, resource.getCurrentStatus(5L).getHostType());
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    public void createTenantGoesToTheClient() {
        CreateOnesTenantCommand cmd = new CreateOnesTenantCommand("fab1", "cs0123456789", "n", -1);
        CreateOnesTenantAnswer expected = new CreateOnesTenantAnswer(cmd, "{}", 1001, 1, 2, "192.1.0.0/16", "192.1.64.0/18", "192.1.0.1", true);
        Mockito.when(client.createTenant(cmd)).thenReturn(expected);

        Assert.assertSame(expected, resource.executeRequest(cmd));
    }

    @Test
    public void deleteTenantGoesToTheClient() {
        DeleteOnesTenantCommand cmd = new DeleteOnesTenantCommand("fab1", "cs0123456789");
        Answer expected = new Answer(cmd);
        Mockito.when(client.deleteTenant(cmd)).thenReturn(expected);

        Assert.assertSame(expected, resource.executeRequest(cmd));
    }

    @Test
    public void allocateAndDeallocateGoToTheClient() {
        AllocateOnesServersCommand add = new AllocateOnesServersCommand("fab1", "t", Collections.singletonList("gpu-01"), true);
        DeallocateOnesServersCommand remove = new DeallocateOnesServersCommand("fab1", "t", Collections.singletonList("gpu-01"));
        Answer added = new Answer(add);
        Answer removed = new Answer(remove);
        Mockito.when(client.allocateServers(add)).thenReturn(added);
        Mockito.when(client.deallocateServers(remove)).thenReturn(removed);

        Assert.assertSame(added, resource.executeRequest(add));
        Assert.assertSame(removed, resource.executeRequest(remove));
    }

    @Test
    public void healthCheckAsksWhetherOnesIsReachable() {
        Mockito.when(client.isReachable()).thenReturn(true, false);

        Assert.assertTrue(resource.executeRequest(new CheckHealthCommand()).getResult());
        Assert.assertFalse(resource.executeRequest(new CheckHealthCommand()).getResult());
    }

    @Test
    public void readyNeedsNoClientAndOtherCommandsAreUnsupported() {
        Assert.assertTrue(resource.executeRequest(new ReadyCommand(1L)).getResult());
        Answer a = resource.executeRequest(new MaintainCommand());
        Assert.assertTrue(a instanceof UnsupportedAnswer);
        Assert.assertFalse(a.getResult());
        Mockito.verifyNoInteractions(client);
    }

    // ------------------------------------------------------------------ lazy client after a restart

    private OnesResource restartedResource() throws ConfigurationException {
        Map<String, Object> p = params();
        p.remove("password");
        OnesResource r = new OnesResource();
        r.configure("ones1", p);
        return r;
    }

    @Test
    public void afterARestartTheLoginComesFromTheProviderRow() throws ConfigurationException {
        OnesProviderVO provider = Mockito.mock(OnesProviderVO.class);
        Mockito.when(provider.getUsername()).thenReturn("admin");
        Mockito.when(provider.getPassword()).thenReturn("secret");
        Mockito.when(provider.getLoginUrl()).thenReturn("https://127.0.0.1:1/api/user/login");
        Mockito.when(providerDao.listByPhysicalNetwork(200L)).thenReturn(Collections.singletonList(provider));
        OnesResource.setProviderDao(providerDao);
        OnesResource r = restartedResource();

        // ReadyCommand is answered without building a client
        Assert.assertTrue(r.executeRequest(new ReadyCommand(1L)).getResult());
        Assert.assertNull(r.onesApiClient);
        // any ONES command builds it from the ones_providers row (nothing listens: not reachable)
        r.executeRequest(new CheckHealthCommand());
        Assert.assertNotNull(r.onesApiClient);
        Assert.assertEquals("secret", r.password);
        Mockito.verify(providerDao).listByPhysicalNetwork(200L);
    }

    @Test
    public void afterARestartWithoutAProviderRowCommandsFailCleanly() throws ConfigurationException {
        Mockito.when(providerDao.listByPhysicalNetwork(200L)).thenReturn(Collections.emptyList());
        OnesResource.setProviderDao(providerDao);
        OnesResource r = restartedResource();

        Answer a = r.executeRequest(new DeleteOnesTenantCommand("fab1", "t"));
        Assert.assertFalse(a.getResult());
        Assert.assertTrue(a.getDetails(), a.getDetails().contains("No Aviz ONES login is on record for physical network 200"));
    }
}
