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
package com.cloud.network.ones;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Minimal client for the Aviz Gateway REST API (/api/v1), for the edge services the plugin
 * drives directly: a tenant's internet access (source NAT to a public address). The tenant itself
 * reaches the gateway through ONES (FM tenant webhook -> ONES edge configurator); this only adds
 * what ONES has no API for. Bearer token (role operator); TLS verified against caCertPath.
 */
public class AvizGatewayClient {

    private final String base;
    private final String token;
    private final CloseableHttpClient http;

    public AvizGatewayClient(final String url, final String token, final String caCertPath) {
        this.base = (url.endsWith("/") ? url.substring(0, url.length() - 1) : url) + "/api/v1";
        this.token = token;
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            TrustManagerFactory tmf = null;
            if (caCertPath != null) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(null, null);
                try (InputStream in = new FileInputStream(caCertPath)) {
                    int i = 0;
                    for (java.security.cert.Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                        ks.setCertificateEntry("gw-" + i++, c);
                    }
                }
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
            }
            ssl.init(null, tmf != null ? tmf.getTrustManagers() : null, new SecureRandom());
            this.http = HttpClients.custom()
                    .setSSLSocketFactory(new SSLConnectionSocketFactory(ssl))
                    .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(10000).setSocketTimeout(60000).build())
                    .build();
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to set up the Aviz Gateway client: " + e.getMessage(), e);
        }
    }

    private int[] statusAndBody(final HttpRequestBase req, final StringBuilder body) throws IOException {
        req.setHeader("Authorization", "Bearer " + token);
        req.setHeader("Content-Type", "application/json");
        req.setHeader("Accept", "application/json");
        try (CloseableHttpResponse r = http.execute(req)) {
            HttpEntity e = r.getEntity();
            body.append(e != null ? EntityUtils.toString(e) : "");
            return new int[]{r.getStatusLine().getStatusCode()};
        }
    }

    /** True once the gateway has the tenant (ONES's webhook got there). */
    public boolean hasTenant(final String tenant) throws IOException {
        StringBuilder b = new StringBuilder();
        int status = statusAndBody(new HttpGet(base + "/tenants/" + tenant), b)[0];
        if (status == 200) {
            return true;
        }
        if (status == 404) {
            return false;
        }
        throw new IOException("gateway GET tenant " + tenant + ": HTTP " + status + ": " + abbreviate(b));
    }

    /** Internet access for the tenant, translating to this one public address. */
    public void enableInternet(final String tenant, final String publicIp) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", true);
        JsonArray pool = new JsonArray();
        pool.add(publicIp);
        body.add("pool", pool);
        body.addProperty("session_limit", 10000);
        HttpPut put = new HttpPut(base + "/tenants/" + tenant + "/internet-access");
        put.setEntity(new StringEntity(body.toString(), "UTF-8"));
        StringBuilder b = new StringBuilder();
        int status = statusAndBody(put, b)[0];
        if (status != 200 && status != 201) {
            throw new IOException("gateway internet access for " + tenant + ": HTTP " + status + ": " + abbreviate(b));
        }
    }

    /** Back to isolated; a tenant (or access) that is already gone is fine. */
    public void disableInternet(final String tenant) throws IOException {
        StringBuilder b = new StringBuilder();
        int status = statusAndBody(new HttpDelete(base + "/tenants/" + tenant + "/internet-access"), b)[0];
        if (status != 200 && status != 204 && status != 404) {
            throw new IOException("gateway remove internet access for " + tenant + ": HTTP " + status + ": " + abbreviate(b));
        }
    }

    private static String abbreviate(final CharSequence s) {
        return s.length() > 300 ? s.subSequence(0, 300) + "..." : s.toString();
    }
}
