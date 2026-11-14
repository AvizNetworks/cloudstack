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

package com.cloud.network;

import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.apache.cloudstack.api.InternalIdentity;

import com.cloud.utils.db.Encrypt;

/**
 * Represents one registered Aviz ONES Spectrum-X fabric controller, bound to a physical
 * network (mirrors how NiciraNvp/BigSwitch/Ovs controllers are registered in CloudStack 4.19),
 * and represented internally as a pseudo-agent host ({@link com.cloud.network.resource.OnesResource}).
 */
@Entity
@Table(name = "ones_providers")
public class OnesProviderVO implements InternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "host_id")
    private long hostId;

    @Column(name = "physical_network_id")
    private long physicalNetworkId;

    @Column(name = "provider_name")
    private String providerName;

    @Column(name = "device_name")
    private String deviceName;

    /** Base URL of the ONES Fabric Manager API, e.g. https://ones-fm:8443 */
    @Column(name = "url")
    private String url;

    /** Name of the ONES fabric this provider orchestrates tenants on. */
    @Column(name = "fabric_name")
    private String fabricName;

    /** ONES login endpoint, e.g. https://ones-fm/api/user/login. */
    @Column(name = "login_url", length = 1024)
    private String loginUrl;

    /** ONES user the plugin logs in as. */
    @Column(name = "username")
    private String username;

    /** That user's password, encrypted at rest with CloudStack's database key. */
    @Column(name = "password", length = 1024)
    @Encrypt
    private String password;

    /** Local filesystem paths to the mTLS client cert/key and CA bundle used to reach the controller. */
    @Column(name = "client_cert_path", length = 1024)
    private String clientCertPath;

    @Column(name = "client_key_path", length = 1024)
    private String clientKeyPath;

    @Column(name = "ca_cert_path", length = 1024)
    private String caCertPath;

    /**
     * Lab/demo: where the tenant's gateway is when ONES does not say. The tail of the address in
     * the tenant subnet, e.g. "128.3" -> 192.T.128.3 (the leaf SVI ONES configures with tenant
     * IPAM off). Null = use ONES's reported gateway.
     */
    @Column(name = "tenant_gateway_suffix")
    private String tenantGatewaySuffix;

    /** Aviz Gateway REST API (e.g. https://gateway-00:8090); null = no internet access handling. */
    @Column(name = "gateway_url", length = 1024)
    private String gatewayUrl;

    /** Its API token (role operator), encrypted at rest. */
    @Column(name = "gateway_token", length = 1024)
    @Encrypt
    private String gatewayToken;

    @Column(name = "gateway_ca_cert_path", length = 1024)
    private String gatewayCaCertPath;

    /** Public addresses handed to networks for internet access (one each), "a.b.c.d-a.b.c.e" or a comma list. */
    @Column(name = "public_ips", length = 1024)
    private String publicIps;

    public OnesProviderVO() {
        uuid = UUID.randomUUID().toString();
    }

    public OnesProviderVO(final long hostId, final long physicalNetworkId, final String providerName, final String deviceName,
            final String url, final String fabricName, final String loginUrl, final String username, final String password,
            final String clientCertPath, final String clientKeyPath, final String caCertPath) {
        super();
        this.hostId = hostId;
        this.physicalNetworkId = physicalNetworkId;
        this.providerName = providerName;
        this.deviceName = deviceName;
        this.url = url;
        this.fabricName = fabricName;
        this.loginUrl = loginUrl;
        this.username = username;
        this.password = password;
        this.clientCertPath = clientCertPath;
        this.clientKeyPath = clientKeyPath;
        this.caCertPath = caCertPath;
        uuid = UUID.randomUUID().toString();
    }

    @Override
    public long getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public long getHostId() {
        return hostId;
    }

    public long getPhysicalNetworkId() {
        return physicalNetworkId;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getUrl() {
        return url;
    }

    public String getFabricName() {
        return fabricName;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
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

    public String getTenantGatewaySuffix() {
        return tenantGatewaySuffix;
    }

    public void setTenantGatewaySuffix(final String tenantGatewaySuffix) {
        this.tenantGatewaySuffix = tenantGatewaySuffix;
    }

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public void setGatewayUrl(final String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
    }

    public String getGatewayToken() {
        return gatewayToken;
    }

    public void setGatewayToken(final String gatewayToken) {
        this.gatewayToken = gatewayToken;
    }

    public String getGatewayCaCertPath() {
        return gatewayCaCertPath;
    }

    public void setGatewayCaCertPath(final String gatewayCaCertPath) {
        this.gatewayCaCertPath = gatewayCaCertPath;
    }

    public String getPublicIps() {
        return publicIps;
    }

    public void setPublicIps(final String publicIps) {
        this.publicIps = publicIps;
    }
}
