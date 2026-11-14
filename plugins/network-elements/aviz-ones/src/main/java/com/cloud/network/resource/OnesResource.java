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

import java.util.HashMap;
import java.util.Map;

import javax.naming.ConfigurationException;

import com.cloud.agent.IAgentControl;
import com.cloud.agent.api.AllocateOnesServersCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreateOnesTenantCommand;
import com.cloud.agent.api.DeallocateOnesServersCommand;
import com.cloud.agent.api.DeleteOnesTenantCommand;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupOnesCommand;
import com.cloud.host.Host;
import com.cloud.network.OnesProviderVO;
import com.cloud.network.dao.OnesProviderDao;
import com.cloud.network.ones.OnesApiClient;
import com.cloud.network.ones.OnesApiClientImpl;
import com.cloud.resource.ServerResource;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Represents one Aviz ONES Spectrum-X fabric controller as a pseudo-agent host, mirroring how
 * NiciraNvpResource/BigSwitchBcfResource represent their SDN controllers. All ONES REST calls are
 * routed through the normal agent Command/Answer framework via {@link #executeRequest(Command)}.
 */
public class OnesResource implements ServerResource {

    protected String name;
    protected String guid;
    protected String zoneId;
    protected String url;
    protected String loginUrl;
    protected String username;
    protected String password;
    protected String clientCertPath;
    protected String clientKeyPath;
    protected String caCertPath;

    protected OnesApiClient onesApiClient;
    protected String physicalNetworkId;
    private IAgentControl agentControl;

    /**
     * ServerResources are re-created by plain reflection when the management server restarts, so
     * they get no Spring injection (and ComponentContext only sees the root context, not this
     * plugin's module context). The Spring-managed OnesElement hands us the DAO here instead -
     * the same static-holder pattern HypervDirectConnectResource uses.
     */
    private static volatile OnesProviderDao s_providerDao;

    public static void setProviderDao(final OnesProviderDao dao) {
        s_providerDao = dao;
    }

    /**
     * Builds the REST client on first use. After a management-server restart the resource is
     * re-created from host_details, which deliberately does not hold the password; it is read
     * from the (encrypted) ones_providers row instead.
     */
    private synchronized OnesApiClient getApiClient() {
        if (onesApiClient == null) {
            if (password == null && physicalNetworkId != null && s_providerDao != null) {
                for (OnesProviderVO provider : s_providerDao.listByPhysicalNetwork(Long.parseLong(physicalNetworkId))) {
                    username = provider.getUsername();
                    password = provider.getPassword();
                    loginUrl = provider.getLoginUrl();
                    break;
                }
            }
            if (password == null) {
                throw new CloudRuntimeException("No Aviz ONES login is on record for physical network " + physicalNetworkId);
            }
            onesApiClient = new OnesApiClientImpl(url, loginUrl, username, password, clientCertPath, clientKeyPath, caCertPath);
        }
        return onesApiClient;
    }

    @Override
    public Host.Type getType() {
        return Host.Type.L2Networking;
    }

    @Override
    public StartupCommand[] initialize() {
        StartupOnesCommand sc = new StartupOnesCommand();
        sc.setGuid(guid);
        sc.setName(name);
        sc.setDataCenter(zoneId != null ? zoneId : "");
        sc.setPod("");
        sc.setPrivateIpAddress("");
        sc.setStorageIpAddress("");
        sc.setVersion("");
        return new StartupCommand[]{sc};
    }

    @Override
    public PingCommand getCurrentStatus(final long id) {
        return new PingCommand(Host.Type.L2Networking, id);
    }

    @Override
    public Answer executeRequest(final Command cmd) {
        if (cmd instanceof ReadyCommand) {
            return new Answer(cmd);
        }
        try {
            if (cmd instanceof CheckHealthCommand) {
                return new Answer(cmd, getApiClient().isReachable(), "");
            } else if (cmd instanceof CreateOnesTenantCommand) {
                return getApiClient().createTenant((CreateOnesTenantCommand) cmd);
            } else if (cmd instanceof DeleteOnesTenantCommand) {
                return getApiClient().deleteTenant((DeleteOnesTenantCommand) cmd);
            } else if (cmd instanceof AllocateOnesServersCommand) {
                return getApiClient().allocateServers((AllocateOnesServersCommand) cmd);
            } else if (cmd instanceof DeallocateOnesServersCommand) {
                return getApiClient().deallocateServers((DeallocateOnesServersCommand) cmd);
            }
        } catch (CloudRuntimeException e) {
            return new Answer(cmd, e);
        }
        return Answer.createUnsupportedCommandAnswer(cmd);
    }

    @Override
    public void disconnected() {
        // Do nothing
    }

    @Override
    public IAgentControl getAgentControl() {
        return agentControl;
    }

    @Override
    public void setAgentControl(final IAgentControl agentControl) {
        this.agentControl = agentControl;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(final String name) {
        this.name = name;
    }

    @Override
    public void setConfigParams(final Map<String, Object> params) {
        // Do nothing
    }

    @Override
    public Map<String, Object> getConfigParams() {
        return new HashMap<>();
    }

    @Override
    public int getRunLevel() {
        return 0;
    }

    @Override
    public void setRunLevel(final int level) {
        // Do nothing
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        this.name = (String) params.get("name");
        if (this.name == null) {
            throw new ConfigurationException("Unable to find the Aviz ONES provider name");
        }

        guid = (String) params.get("guid");
        if (guid == null) {
            throw new ConfigurationException("Unable to find the Aviz ONES resource guid");
        }

        zoneId = (String) params.get("zoneId");

        url = (String) params.get("url");
        if (url == null) {
            throw new ConfigurationException("Missing Aviz ONES controller URL from params: " + params);
        }

        // The password is only here on the first configure (addOnesProvider); on a restart it
        // is resolved lazily from ones_providers via the physical network id.
        loginUrl = (String) params.get("loginUrl");
        username = (String) params.get("username");
        password = (String) params.get("password");
        physicalNetworkId = (String) params.get("physicalNetworkId");
        if (password == null && physicalNetworkId == null) {
            throw new ConfigurationException("Missing the Aviz ONES login and the physical network id");
        }

        // mTLS is optional - some ONES deployments authenticate purely via the JWT token.
        clientCertPath = (String) params.get("clientCertPath");
        clientKeyPath = (String) params.get("clientKeyPath");
        caCertPath = (String) params.get("caCertPath");
        if ((clientCertPath == null) != (clientKeyPath == null)) {
            throw new ConfigurationException("clientCertPath and clientKeyPath must both be set, or both omitted: " + params);
        }

        if (password != null) {
            onesApiClient = new OnesApiClientImpl(url, loginUrl, username, password, clientCertPath, clientKeyPath, caCertPath);
        }
        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }
}
