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
package com.cloud.api.commands;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.context.CallContext;

import com.cloud.api.response.OnesProviderResponse;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.OnesProviderVO;
import com.cloud.network.element.OnesElementService;
import com.cloud.utils.exception.CloudRuntimeException;

/** Edge (Aviz Gateway) settings of a registered ONES provider. An empty value clears a setting. */
@APICommand(name = "updateOnesProvider", responseObject = OnesProviderResponse.class,
        description = "Updates an Aviz ONES provider's edge settings (Aviz Gateway, public addresses, tenant gateway)",
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = false)
public class UpdateOnesProviderCmd extends BaseCmd {

    @Inject
    protected OnesElementService onesElementService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = OnesProviderResponse.class,
            required = true, description = "Aviz ONES provider ID")
    private Long id;

    @Parameter(name = "tenantgatewaysuffix", type = CommandType.STRING,
            description = "lab: the tenant gateway's tail in the tenant subnet, e.g. 128.3 (the leaf SVI); used when ONES runs no anycast gateway")
    private String tenantGatewaySuffix;

    @Parameter(name = "gatewayurl", type = CommandType.STRING, length = 1024,
            description = "Aviz Gateway REST API, e.g. https://gateway-00:8090 (a name its certificate carries)")
    private String gatewayUrl;

    @Parameter(name = "gatewaytoken", type = CommandType.STRING, length = 1024,
            description = "Aviz Gateway API token (role operator); stored encrypted")
    private String gatewayToken;

    @Parameter(name = "gatewaycacertpath", type = CommandType.STRING, length = 1024,
            description = "path on the management server to the certificate that verifies the gateway")
    private String gatewayCaCertPath;

    @Parameter(name = "publicips", type = CommandType.STRING, length = 1024,
            description = "public addresses for internet access, one per network: a.b.c.d-a.b.c.e and/or a comma list")
    private String publicIps;

    public Long getId() {
        return id;
    }

    public String getTenantGatewaySuffix() {
        return tenantGatewaySuffix;
    }

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public String getGatewayToken() {
        return gatewayToken;
    }

    public String getGatewayCaCertPath() {
        return gatewayCaCertPath;
    }

    public String getPublicIps() {
        return publicIps;
    }

    @Override
    public void execute() {
        try {
            OnesProviderVO vo = onesElementService.updateOnesProvider(this);
            OnesProviderResponse response = onesElementService.createOnesProviderResponse(vo);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (InvalidParameterValueException e) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, e.getMessage());
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
