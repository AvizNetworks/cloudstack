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
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import com.cloud.agent.api.AllocateOnesServersCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreateOnesTenantAnswer;
import com.cloud.agent.api.CreateOnesTenantCommand;
import com.cloud.agent.api.DeallocateOnesServersCommand;
import com.cloud.agent.api.DeleteOnesTenantCommand;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for the ONES Fabric Manager (FM) tenant API.
 *
 * Auth: logs in with a user name and password at the ONES UI's login endpoint
 * (POST https://&lt;ones&gt;/api/user/login, the same login the ONES UI and ones-gfx-sdk use) and
 * sends the returned JWT as a Bearer token to FM (https://&lt;ones&gt;:8443). ONES JWTs are short
 * lived, so on a 401 the client logs in again and retries once.
 *
 * Every state-changing call is made ASYNC ("Prefer: respond-async") and the client then polls
 * GET /operations/{id} until it finishes. Besides not holding an HTTP request open for minutes,
 * this matters for correctness: FM sends its tenant webhooks (which is how the edge gateway
 * learns about a tenant) only for async operations.
 */
public class OnesApiClientImpl implements OnesApiClient {

    private static final Logger LOG = LogManager.getLogger(OnesApiClientImpl.class);

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int SOCKET_TIMEOUT_MS = 120000;
    static final long OPERATION_TIMEOUT_MS = 900_000;
    static final long OPERATION_POLL_MS = 5_000;
    private static final char[] KEYSTORE_PASSWORD = "avizones".toCharArray();

    private final String fmUrl;
    private final String loginUrl;
    private final String username;
    private final String password;
    private final CloseableHttpClient httpClient;
    private volatile String token;
    long operationPollMs = OPERATION_POLL_MS;

    public OnesApiClientImpl(final String fmUrl, final String loginUrl, final String username, final String password,
            final String clientCertPath, final String clientKeyPath, final String caCertPath) {
        this.fmUrl = stripSlash(fmUrl);
        this.loginUrl = loginUrl;
        this.username = username;
        this.password = password;
        this.httpClient = buildHttpClient(clientCertPath, clientKeyPath, caCertPath);
    }

    private static String stripSlash(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ---------------------------------------------------------------- TLS

    private CloseableHttpClient buildHttpClient(final String clientCertPath, final String clientKeyPath, final String caCertPath) {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            // Optional mTLS client certificate; optional CA bundle (else the JVM trust store).
            // Host names are always verified: ONES's stock certificate names "ones-fm" and
            // "localhost", not an IP, so point the URLs at a name the certificate carries.
            KeyManager[] keyManagers = (clientCertPath != null && clientKeyPath != null)
                    ? buildKeyManagers(clientCertPath, clientKeyPath) : null;
            TrustManager[] trustManagers = (caCertPath != null) ? buildTrustManagers(caCertPath) : null;

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, new SecureRandom());

            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setSocketTimeout(SOCKET_TIMEOUT_MS)
                    .build();
            return HttpClients.custom()
                    .setSSLSocketFactory(new SSLConnectionSocketFactory(sslContext))
                    .setDefaultRequestConfig(requestConfig)
                    .build();
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to initialize the HTTPS client for Aviz ONES: " + e.getMessage(), e);
        }
    }

    private KeyManager[] buildKeyManagers(final String certPath, final String keyPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate clientCert;
        try (InputStream in = new FileInputStream(certPath)) {
            clientCert = (X509Certificate) cf.generateCertificate(in);
        }
        PrivateKey privateKey;
        try (PEMParser pemParser = new PEMParser(new FileReader(keyPath))) {
            Object obj = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof PEMKeyPair) {
                privateKey = converter.getKeyPair((PEMKeyPair) obj).getPrivate();
            } else if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo) {
                privateKey = converter.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo) obj);
            } else {
                throw new CloudRuntimeException("Unsupported (or encrypted) private key format in " + keyPath);
            }
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("ones-client", privateKey, KEYSTORE_PASSWORD, new X509Certificate[]{clientCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        return kmf.getKeyManagers();
    }

    private TrustManager[] buildTrustManagers(final String caCertPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        try (InputStream in = new FileInputStream(caCertPath)) {
            int i = 0;
            for (java.security.cert.Certificate c : cf.generateCertificates(in)) {
                trustStore.setCertificateEntry("ones-ca-" + i++, c);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf.getTrustManagers();
    }

    // ---------------------------------------------------------------- HTTP

    /** HTTP status + body. */
    static final class Reply {
        final int status;
        final String body;

        Reply(final int status, final String body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private Reply send(final HttpRequestBase request) throws IOException {
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            HttpEntity entity = response.getEntity();
            return new Reply(response.getStatusLine().getStatusCode(), entity != null ? EntityUtils.toString(entity) : "");
        }
    }

    private synchronized String login() throws IOException {
        HttpPost post = new HttpPost(loginUrl);
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        post.setEntity(new StringEntity(body.toString(), "UTF-8"));
        Reply r = send(post);
        if (!r.ok()) {
            throw new IOException("ONES login at " + loginUrl + " as " + username + " failed: HTTP " + r.status);
        }
        JsonObject data = asObject(parse(r.body), "data");
        String t = data != null ? str(data, "token") : null;
        if (t == null) {
            throw new IOException("ONES login at " + loginUrl + " returned no token");
        }
        token = t;
        return t;
    }

    /** One FM call with the Bearer token; logs in first if needed and once more on a 401. */
    Reply call(final HttpRequestBase request, final boolean async) throws IOException {
        if (async) {
            request.setHeader("Prefer", "respond-async");
        }
        for (int attempt = 0; ; attempt++) {
            String t = token != null ? token : login();
            request.setHeader("Authorization", "Bearer " + t);
            Reply r = send(request);
            if (r.status == 401 && attempt == 0) {
                token = null;
                continue;
            }
            return r;
        }
    }

    private Reply callOk(final HttpRequestBase request, final boolean async, final String what) throws IOException {
        Reply r = call(request, async);
        if (!r.ok()) {
            throw new IOException(what + ": ONES returned HTTP " + r.status + ": " + abbreviate(r.body));
        }
        return r;
    }

    /**
     * Waits for the async operation an FM reply names, if any. FM wraps replies as
     * {success, message, data} where data is an object or a JSON-encoded string.
     */
    void awaitOperation(final Reply reply, final String what) throws IOException {
        String op = findOperationId(parse(reply.body));
        if (op == null) {
            return;    // FM answered synchronously
        }
        long deadline = System.currentTimeMillis() + OPERATION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Reply r = callOk(new HttpGet(fmUrl + "/operations/" + op), false, what + " (operation " + op + ")");
            JsonElement root = parse(r.body);
            JsonObject o = asObject(root, "data");
            if (o == null && root != null && root.isJsonObject()) {
                o = root.getAsJsonObject();
            }
            String status = o != null ? str(o, "status") : null;
            if ("SUCCESS".equals(status)) {
                return;
            }
            if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                throw new IOException(what + ": ONES operation " + op + " " + status + ": " + abbreviate(String.valueOf(str(o, "errorMessage"))));
            }
            try {
                Thread.sleep(operationPollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(what + ": interrupted waiting for ONES operation " + op);
            }
        }
        throw new IOException(what + ": ONES operation " + op + " did not finish in " + OPERATION_TIMEOUT_MS / 1000 + " s");
    }

    private static String findOperationId(final JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        JsonObject o = root.getAsJsonObject();
        String id = str(o, "operationId");
        if (id != null) {
            return id;
        }
        JsonObject data = asObject(root, "data");
        return data != null ? str(data, "operationId") : null;
    }

    // ---------------------------------------------------------------- API

    @Override
    public boolean isReachable() {
        try {
            callOk(new HttpGet(fmUrl + "/getAllFabrics"), false, "list fabrics");
            return true;
        } catch (Exception e) {
            LOG.warn("Aviz ONES at {} is not reachable: {}", fmUrl, e.getMessage());
            return false;
        }
    }

    @Override
    public CreateOnesTenantAnswer createTenant(final CreateOnesTenantCommand cmd) {
        String what = "create ONES tenant " + cmd.getTenantName() + " on fabric " + cmd.getFabricName();
        try {
            JsonObject tenant = getTenant(cmd.getFabricName(), cmd.getTenantName());
            if (tenant == null) {
                JsonObject body = new JsonObject();
                body.addProperty("tenantName", cmd.getTenantName());
                body.addProperty("description", cmd.getDescription() != null ? cmd.getDescription() : "");
                body.addProperty("maxGpusAllowed", cmd.getMaxGpusAllowed());
                // no tenant-level "shared": ONES refuses it unless the fabric is externally
                // controlled; sharing is per server (see patchServers)
                HttpPost post = new HttpPost(fmUrl + "/fabrics/" + cmd.getFabricName() + "/tenants");
                post.setEntity(new StringEntity(body.toString(), "UTF-8"));
                awaitOperation(callOk(post, true, what), what);
                tenant = getTenant(cmd.getFabricName(), cmd.getTenantName());
            }
            if (tenant == null) {
                throw new IOException(what + ": the tenant is not there after the create finished");
            }
            String gateway = null;
            String pool = str(tenant, "tenantIpPool");
            JsonObject vnets = asObject(tenant, "vnets");
            JsonObject ipPool = vnets != null ? asObject(vnets, "tenant IP Pool") : null;
            if (ipPool != null) {
                gateway = stripPrefix(str(ipPool, "gateway"));
                pool = pool != null ? pool : str(ipPool, "subnet");
            }
            Boolean anycast = tenant.has("anycastGatewayEnabled") && !tenant.get("anycastGatewayEnabled").isJsonNull()
                    ? tenant.get("anycastGatewayEnabled").getAsBoolean() : Boolean.FALSE;
            return new CreateOnesTenantAnswer(cmd, tenant.toString(), integer(tenant, "vlanId"), integer(tenant, "vniId"),
                    integer(tenant, "nsVniId"), str(tenant, "vlanSubnetCPU"), pool, gateway, anycast);
        } catch (Exception e) {
            LOG.error("Failed to {}: {}", what, e.getMessage());
            return new CreateOnesTenantAnswer(cmd, e);
        }
    }

    /** GET /fabrics/{f}/tenants/{t}: the tenant record, or null if there is none. */
    JsonObject getTenant(final String fabric, final String tenant) throws IOException {
        Reply r = call(new HttpGet(fmUrl + "/fabrics/" + fabric + "/tenants/" + tenant), false);
        if (r.status == 404) {
            return null;
        }
        if (!r.ok()) {
            throw new IOException("get ONES tenant " + tenant + ": HTTP " + r.status + ": " + abbreviate(r.body));
        }
        JsonElement root = parse(r.body);
        JsonObject t = asObject(root, "tenant");
        if (t == null) {
            JsonObject data = asObject(root, "data");
            t = data != null ? asObject(data, "tenant") : null;
        }
        return t;
    }

    @Override
    public Answer deleteTenant(final DeleteOnesTenantCommand cmd) {
        String what = "delete ONES tenant " + cmd.getTenantName() + " on fabric " + cmd.getFabricName();
        try {
            Reply r = call(new HttpDelete(fmUrl + "/fabrics/" + cmd.getFabricName() + "/tenants/" + cmd.getTenantName()), true);
            if (r.status == 404) {
                return new Answer(cmd, true, "already gone");
            }
            if (!r.ok()) {
                throw new IOException(what + ": HTTP " + r.status + ": " + abbreviate(r.body));
            }
            awaitOperation(r, what);
            return new Answer(cmd, true, r.body);
        } catch (Exception e) {
            LOG.error("Failed to {}: {}", what, e.getMessage());
            return new Answer(cmd, e);
        }
    }

    @Override
    public Answer allocateServers(final AllocateOnesServersCommand cmd) {
        return patchServers(cmd, cmd.getFabricName(), cmd.getTenantName(), "ADD", cmd.getServerNames());
    }

    @Override
    public Answer deallocateServers(final DeallocateOnesServersCommand cmd) {
        return patchServers(cmd, cmd.getFabricName(), cmd.getTenantName(), "DELETE", cmd.getServerNames());
    }

    /**
     * PATCH a tenant's servers. Always "shared": a hypervisor carries many guest networks, so
     * ONES must trunk each tenant's VLAN on its leaf port rather than give it the port.
     */
    private Answer patchServers(final Command cmd, final String fabric, final String tenant, final String operation,
            final List<String> serverNames) {
        String what = operation + " servers " + serverNames + " on ONES tenant " + tenant;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("operation", operation);
            JsonArray servers = new JsonArray();
            for (String serverName : serverNames) {
                JsonObject server = new JsonObject();
                server.addProperty("serverName", serverName);
                server.addProperty("shared", true);
                servers.add(server);
            }
            body.add("servers", servers);
            HttpPatch patch = new HttpPatch(fmUrl + "/fabrics/" + fabric + "/tenants/" + tenant);
            patch.setEntity(new StringEntity(body.toString(), "UTF-8"));
            Reply r = callOk(patch, true, what);
            awaitOperation(r, what);
            return new Answer(cmd, true, r.body);
        } catch (Exception e) {
            LOG.error("Failed to {}: {}", what, e.getMessage());
            return new Answer(cmd, e);
        }
    }

    // ---------------------------------------------------------------- JSON helpers

    static JsonElement parse(final String body) {
        try {
            return JsonParser.parseString(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** obj[field] as an object, decoding it first if FM sent it as a JSON-encoded string. */
    static JsonObject asObject(final JsonElement root, final String field) {
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        JsonElement e = root.getAsJsonObject().get(field);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            e = parse(e.getAsString());
        }
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static String str(final JsonObject o, final String field) {
        return o != null && o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    static Integer integer(final JsonObject o, final String field) {
        return o != null && o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsInt() : null;
    }

    static String stripPrefix(final String cidr) {
        if (cidr == null) {
            return null;
        }
        int slash = cidr.indexOf('/');
        return slash >= 0 ? cidr.substring(0, slash) : cidr;
    }

    private static String abbreviate(final String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
