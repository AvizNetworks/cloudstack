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

package com.cloud.api.commands;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.PhysicalNetworkResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.api.response.OnesProviderResponse;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.OnesProviderVO;
import com.cloud.network.element.OnesElementService;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Registers an Aviz ONES Spectrum-X fabric controller against a physical network, the same
 * way addNiciraNvpDevice/addBigSwitchBcfDevice register their SDN controllers.
 */
@APICommand(name = "addOnesProvider", responseObject = OnesProviderResponse.class, description = "Adds an Aviz ONES Spectrum-X fabric provider",
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = false)
public class AddOnesProviderCmd extends BaseCmd {

    @Inject
    protected OnesElementService onesElementService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.PHYSICAL_NETWORK_ID,
               type = CommandType.UUID,
               entityType = PhysicalNetworkResponse.class,
               required = true,
               description = "the Physical Network ID")
    private Long physicalNetworkId;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "Name of this Aviz ONES provider")
    private String name;

    @Parameter(name = "url", type = CommandType.STRING, required = true, description = "Base URL of the ONES Fabric Manager API, e.g. https://ones-fm:8443 (use a host name the ONES certificate names)")
    private String url;

    @Parameter(name = "fabricname", type = CommandType.STRING, required = true, description = "Name of the ONES fabric this provider orchestrates tenants on")
    private String fabricName;

    @Parameter(name = ApiConstants.USERNAME, type = CommandType.STRING, required = true,
            description = "ONES user the plugin logs in as")
    private String username;

    @Parameter(name = ApiConstants.PASSWORD, type = CommandType.STRING, required = true,
            description = "password of that ONES user; stored encrypted")
    private String password;

    @Parameter(name = "loginurl", type = CommandType.STRING, length = 1024,
            description = "ONES login endpoint; default https://<host of url>/api/user/login")
    private String loginUrl;

    @Parameter(name = "clientcertpath", type = CommandType.STRING, length = 1024,
            description = "Optional: local filesystem path (on the management server) to an mTLS client certificate, if the ONES controller requires mutual TLS in addition to the JWT token")
    private String clientCertPath;

    @Parameter(name = "clientkeypath", type = CommandType.STRING, length = 1024,
            description = "Optional: local filesystem path (on the management server) to the mTLS client private key. Required if clientcertpath is set")
    private String clientKeyPath;

    @Parameter(name = "cacertpath", type = CommandType.STRING, length = 1024,
            description = "Optional: local filesystem path (on the management server) to a CA bundle used to verify the ONES controller. If omitted, the JVM's default trust store is used")
    private String caCertPath;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getPhysicalNetworkId() {
        return physicalNetworkId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getFabricName() {
        return fabricName;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getLoginUrl() {
        if (loginUrl != null && !loginUrl.isEmpty()) {
            return loginUrl;
        }
        java.net.URI u = java.net.URI.create(url);
        return u.getScheme() + "://" + u.getHost() + "/api/user/login";
    }

    public String getClientCertPath() {
        return clientCertPath;
    }

    public String getClientKeyPath() {
        return clientKeyPath;
    }

    public String getCaCertPath() {
        return caCertPath;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        try {
            OnesProviderVO onesProviderVO = onesElementService.addOnesProvider(this);
            if (onesProviderVO != null) {
                OnesProviderResponse response = onesElementService.createOnesProviderResponse(onesProviderVO);
                response.setObjectName("onesprovider");
                response.setResponseName(getCommandName());
                setResponseObject(response);
            } else {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to add Aviz ONES provider due to internal error.");
            }
        } catch (InvalidParameterValueException invalidParamExcp) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, invalidParamExcp.getMessage());
        } catch (CloudRuntimeException runtimeExcp) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, runtimeExcp.getMessage());
        }
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
