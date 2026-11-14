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

package com.cloud.api.response;

import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import com.cloud.network.OnesProviderVO;
import com.cloud.serializer.Param;

@EntityReference(value = OnesProviderVO.class)
public class OnesProviderResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "device id of the Aviz ONES provider")
    private String id;

    @SerializedName(ApiConstants.PHYSICAL_NETWORK_ID)
    @Param(description = "the physical network this Aviz ONES provider belongs to")
    private String physicalNetworkId;

    @SerializedName(ApiConstants.PROVIDER)
    @Param(description = "name of the provider")
    private String providerName;

    @SerializedName("devicename")
    @Param(description = "device name")
    private String deviceName;

    @SerializedName("url")
    @Param(description = "the Aviz ONES controller URL")
    private String url;

    @SerializedName("fabricname")
    @Param(description = "the Aviz ONES fabric name this provider orchestrates tenants on")
    private String fabricName;

    @SerializedName("tenantgatewaysuffix")
    @Param(description = "lab: tenant gateway tail in the tenant subnet")
    private String tenantGatewaySuffix;

    @SerializedName("gatewayurl")
    @Param(description = "Aviz Gateway REST API")
    private String gatewayUrl;

    @SerializedName("publicips")
    @Param(description = "public addresses for internet access")
    private String publicIps;

    @SerializedName("gatewaytokenset")
    @Param(description = "whether a gateway API token is stored")
    private Boolean gatewayTokenSet;

    public void setTenantGatewaySuffix(final String v) {
        tenantGatewaySuffix = v;
    }

    public void setGatewayUrl(final String v) {
        gatewayUrl = v;
    }

    public void setPublicIps(final String v) {
        publicIps = v;
    }

    public void setGatewayTokenSet(final Boolean v) {
        gatewayTokenSet = v;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public void setPhysicalNetworkId(final String physicalNetworkId) {
        this.physicalNetworkId = physicalNetworkId;
    }

    public void setProviderName(final String providerName) {
        this.providerName = providerName;
    }

    public void setDeviceName(final String deviceName) {
        this.deviceName = deviceName;
    }

    public void setUrl(final String url) {
        this.url = url;
    }

    public void setFabricName(final String fabricName) {
        this.fabricName = fabricName;
    }
}
