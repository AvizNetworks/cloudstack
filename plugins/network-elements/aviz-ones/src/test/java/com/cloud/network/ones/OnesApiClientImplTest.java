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

package com.cloud.network.ones;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.cloud.agent.api.AllocateOnesServersCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CreateOnesTenantAnswer;
import com.cloud.agent.api.CreateOnesTenantCommand;
import com.cloud.agent.api.DeallocateOnesServersCommand;
import com.cloud.agent.api.DeleteOnesTenantCommand;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

/**
 * Runs OnesApiClientImpl against a fake ONES (UI login + Fabric Manager tenant API) served over
 * mutual TLS, with a throwaway CA, server and client certificate made at test time. The fake
 * follows the contract the client relies on: a JWT from POST /api/user/login, Bearer auth on FM
 * (401 once the token is no longer current), "Prefer: respond-async" answered with 202 and an
 * operationId, and GET /operations/{id} reporting the operation's status.
 */
public class OnesApiClientImplTest {

    private static final String FABRIC = "fab1";
    private static final String TENANT = "cs0123456789";

    private static KeyPair caKeys;
    private static X509Certificate caCert;
    private static File pemDir;
    private static File clientCert;
    private static File clientKey;
    private static File caPem;

    private HttpsServer server;
    private String fmUrl;
    private String loginUrl;

    // fake ONES state
    private final Map<String, JsonObject> tenants = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> asyncRequests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Deque<String> operationStatuses = new ArrayDeque<>();
    private volatile String currentToken;
    private volatile int logins;
    private volatile int operations;
    private volatile int operationPolls;
    private volatile boolean loginRejected;
    private volatile boolean fmRejectsEveryToken;
    private volatile boolean asyncSupported = true;

    @BeforeClass
    public static void makeCertificates() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        caKeys = keyPair();
        caCert = certificate(caKeys, null, caKeys.getPublic(), "CN=Test ONES CA", true, false);
        KeyPair clientKeys = keyPair();
        X509Certificate client = certificate(caKeys, caCert, clientKeys.getPublic(), "CN=cloudstack", false, false);

        pemDir = Files.createTempDirectory("ones-test-").toFile();
        clientCert = pem(new File(pemDir, "client.crt"), client);
        clientKey = pem(new File(pemDir, "client.key"), clientKeys.getPrivate());
        caPem = pem(new File(pemDir, "ca.crt"), caCert);
    }

    @Before
    public void startFakeOnes() throws Exception {
        KeyPair serverKeys = keyPair();
        X509Certificate serverCert = certificate(caKeys, caCert, serverKeys.getPublic(), "CN=localhost", false, true);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", serverKeys.getPrivate(), "changeit".toCharArray(), new X509Certificate[]{serverCert, caCert});
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", caCert);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        final SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override
            public void configure(final HttpsParameters params) {
                SSLParameters p = ssl.getDefaultSSLParameters();
                p.setNeedClientAuth(true);
                params.setSSLParameters(p);
            }
        });
        server.createContext("/", this::handle);
        server.start();
        String base = "https://127.0.0.1:" + server.getAddress().getPort();
        loginUrl = base + "/api/user/login";
        fmUrl = base + "/fm/";      // trailing slash: the client must strip it
    }

    @After
    public void stopFakeOnes() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ fake ONES

    private void handle(final HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(method + " " + path);
        bodies.put(method + " " + path, body);

        if ("/api/user/login".equals(path)) {
            JsonObject creds = JsonParser.parseString(body).getAsJsonObject();
            if (loginRejected || !"admin".equals(creds.get("username").getAsString())
                    || !"secret".equals(creds.get("password").getAsString())) {
                reply(ex, 401, "{\"success\":false}");
                return;
            }
            logins++;
            currentToken = "jwt-" + logins;
            reply(ex, 200, "{\"success\":true,\"data\":{\"token\":\"" + currentToken + "\"}}");
            return;
        }

        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (fmRejectsEveryToken || auth == null || !auth.equals("Bearer " + currentToken)) {
            reply(ex, 401, "{\"message\":\"token expired\"}");
            return;
        }
        boolean async = "respond-async".equals(ex.getRequestHeaders().getFirst("Prefer"));
        if (async) {
            asyncRequests.add(method + " " + path);
        }

        String fm = path.substring("/fm".length());
        String tenantsPrefix = "/fabrics/" + FABRIC + "/tenants";
        if ("GET".equals(method) && "/getAllFabrics".equals(fm)) {
            reply(ex, 200, "{\"data\":[{\"fabricName\":\"" + FABRIC + "\"}]}");
        } else if ("POST".equals(method) && tenantsPrefix.equals(fm)) {
            String name = JsonParser.parseString(body).getAsJsonObject().get("tenantName").getAsString();
            tenants.put(name, tenantRecord(name));
            accepted(ex, async);
        } else if (fm.startsWith(tenantsPrefix + "/")) {
            String name = fm.substring(tenantsPrefix.length() + 1);
            JsonObject t = tenants.get(name);
            if ("GET".equals(method)) {
                if (t == null) {
                    reply(ex, 404, "{\"message\":\"no such tenant\"}");
                } else {
                    // FM sends data as a JSON-encoded string: the client must decode it
                    JsonObject wrapped = new JsonObject();
                    wrapped.add("tenant", t);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", true);
                    r.addProperty("data", wrapped.toString());
                    reply(ex, 200, r.toString());
                }
            } else if (t == null) {
                reply(ex, 404, "{\"message\":\"no such tenant\"}");
            } else if ("DELETE".equals(method)) {
                tenants.remove(name);
                accepted(ex, async);
            } else if ("PATCH".equals(method)) {
                accepted(ex, async);
            } else {
                reply(ex, 405, "{}");
            }
        } else if ("GET".equals(method) && fm.startsWith("/operations/")) {
            operationPolls++;
            String status;
            synchronized (operationStatuses) {
                status = operationStatuses.isEmpty() ? "SUCCESS" : operationStatuses.poll();
            }
            JsonObject op = new JsonObject();
            op.addProperty("operationId", fm.substring("/operations/".length()));
            op.addProperty("status", status);
            if ("FAILED".equals(status)) {
                op.addProperty("errorMessage", "VLAN pool exhausted");
            }
            reply(ex, 200, "{\"data\":" + op + "}");
        } else {
            reply(ex, 404, "{}");
        }
    }

    private void accepted(final HttpExchange ex, final boolean async) throws IOException {
        if (async && asyncSupported) {
            operations++;
            reply(ex, 202, "{\"success\":true,\"data\":{\"operationId\":\"op-" + operations + "\"}}");
        } else {
            reply(ex, 200, "{\"success\":true,\"message\":\"done\"}");
        }
    }

    private static JsonObject tenantRecord(final String name) {
        JsonObject pool = new JsonObject();
        pool.addProperty("gateway", "192.1.255.253/16");
        pool.addProperty("subnet", "192.1.0.0/16");
        JsonObject vnets = new JsonObject();
        vnets.add("tenant IP Pool", pool);
        JsonObject t = new JsonObject();
        t.addProperty("tenantName", name);
        t.addProperty("vlanId", 1001);
        t.addProperty("vniId", 20001);
        t.addProperty("nsVniId", 30001);
        t.addProperty("vlanSubnetCPU", "192.1.0.0/16");
        t.addProperty("tenantIpPool", "192.1.64.0/18");
        t.addProperty("anycastGatewayEnabled", true);
        t.add("vnets", vnets);
        return t;
    }

    private static void reply(final HttpExchange ex, final int status, final String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private OnesApiClientImpl client() {
        return client("secret", clientCert, clientKey, caPem);
    }

    private OnesApiClientImpl client(final String password, final File cert, final File key, final File ca) {
        OnesApiClientImpl c = new OnesApiClientImpl(fmUrl, loginUrl, "admin", password,
                cert != null ? cert.getAbsolutePath() : null, key != null ? key.getAbsolutePath() : null,
                ca != null ? ca.getAbsolutePath() : null);
        c.operationPollMs = 10;
        return c;
    }

    // ------------------------------------------------------------------ login and 401

    @Test
    public void logsInOnceAndReusesTheToken() {
        OnesApiClientImpl c = client();
        Assert.assertTrue(c.isReachable());
        Assert.assertTrue(c.isReachable());
        Assert.assertEquals(1, logins);
        Assert.assertEquals(Arrays.asList("POST /api/user/login", "GET /fm/getAllFabrics", "GET /fm/getAllFabrics"), requests);
    }

    @Test
    public void expiredTokenLogsInAgainAndRetriesOnce() {
        OnesApiClientImpl c = client();
        Assert.assertTrue(c.isReachable());
        currentToken = "rotated";            // ONES no longer accepts jwt-1

        Assert.assertTrue(c.isReachable());
        Assert.assertEquals(2, logins);
        Assert.assertEquals(Arrays.asList("POST /api/user/login", "GET /fm/getAllFabrics",
                "GET /fm/getAllFabrics", "POST /api/user/login", "GET /fm/getAllFabrics"), requests);
    }

    @Test
    public void persistent401DoesNotLoop() {
        fmRejectsEveryToken = true;
        Assert.assertFalse(client().isReachable());
        Assert.assertEquals(2, logins);      // first login + exactly one re-login
    }

    @Test
    public void failedLoginFailsTheCommandWithoutAnFmCall() {
        CreateOnesTenantCommand cmd = new CreateOnesTenantCommand(FABRIC, TENANT, "net", -1);
        CreateOnesTenantAnswer a = client("wrong", clientCert, clientKey, caPem).createTenant(cmd);

        Assert.assertFalse(a.getResult());
        Assert.assertTrue(a.getDetails(), a.getDetails().contains("login"));
        Assert.assertEquals(Collections.singletonList("POST /api/user/login"), requests);
        Assert.assertTrue(tenants.isEmpty());
    }

    // ------------------------------------------------------------------ tenants

    @Test
    public void createTenantIsAsyncWaitsForTheOperationAndReturnsOnesValues() {
        synchronized (operationStatuses) {
            operationStatuses.addAll(Arrays.asList("PENDING", "RUNNING", "SUCCESS"));
        }
        CreateOnesTenantCommand cmd = new CreateOnesTenantCommand(FABRIC, TENANT, "CloudStack network n1", -1);
        CreateOnesTenantAnswer a = client().createTenant(cmd);

        Assert.assertTrue(a.getDetails(), a.getResult());
        Assert.assertEquals(Integer.valueOf(1001), a.getVlanId());
        Assert.assertEquals(Integer.valueOf(20001), a.getL2Vni());
        Assert.assertEquals(Integer.valueOf(30001), a.getL3Vni());
        Assert.assertEquals("192.1.0.0/16", a.getSubnet());
        Assert.assertEquals("192.1.64.0/18", a.getIpPool());
        Assert.assertEquals("192.1.255.253", a.getGateway());       // prefix length stripped
        Assert.assertTrue(a.isAnycastGateway());

        Assert.assertEquals(Collections.singletonList("POST /fm/fabrics/" + FABRIC + "/tenants"), asyncRequests);
        Assert.assertEquals(3, operationPolls);
        JsonObject sent = JsonParser.parseString(bodies.get("POST /fm/fabrics/" + FABRIC + "/tenants")).getAsJsonObject();
        Assert.assertEquals(TENANT, sent.get("tenantName").getAsString());
        Assert.assertEquals(-1, sent.get("maxGpusAllowed").getAsInt());
        Assert.assertFalse("tenant-level shared is refused by ONES", sent.has("shared"));
    }

    @Test
    public void createTenantThatAlreadyExistsDoesNotCreateItAgain() {
        tenants.put(TENANT, tenantRecord(TENANT));
        CreateOnesTenantAnswer a = client().createTenant(new CreateOnesTenantCommand(FABRIC, TENANT, null, -1));

        Assert.assertTrue(a.getResult());
        Assert.assertEquals(Integer.valueOf(1001), a.getVlanId());
        Assert.assertFalse(requests.contains("POST /fm/fabrics/" + FABRIC + "/tenants"));
    }

    @Test
    public void createTenantFailsWhenTheOperationFails() {
        synchronized (operationStatuses) {
            operationStatuses.addAll(Arrays.asList("RUNNING", "FAILED"));
        }
        CreateOnesTenantAnswer a = client().createTenant(new CreateOnesTenantCommand(FABRIC, TENANT, "n", -1));

        Assert.assertFalse(a.getResult());
        Assert.assertTrue(a.getDetails(), a.getDetails().contains("FAILED"));
        Assert.assertTrue(a.getDetails(), a.getDetails().contains("VLAN pool exhausted"));
        Assert.assertNull(a.getVlanId());
    }

    @Test
    public void createTenantAcceptsASynchronousReply() {
        asyncSupported = false;      // FM answers 200 with no operationId: nothing to poll
        CreateOnesTenantAnswer a = client().createTenant(new CreateOnesTenantCommand(FABRIC, TENANT, "n", -1));

        Assert.assertTrue(a.getResult());
        Assert.assertEquals(0, operationPolls);
    }

    @Test
    public void deleteTenantIsAsyncAndRemovesIt() {
        tenants.put(TENANT, tenantRecord(TENANT));
        Answer a = client().deleteTenant(new DeleteOnesTenantCommand(FABRIC, TENANT));

        Assert.assertTrue(a.getResult());
        Assert.assertTrue(tenants.isEmpty());
        Assert.assertEquals(Collections.singletonList("DELETE /fm/fabrics/" + FABRIC + "/tenants/" + TENANT), asyncRequests);
        Assert.assertEquals(1, operationPolls);
    }

    @Test
    public void deletingAMissingTenantSucceeds() {
        Answer a = client().deleteTenant(new DeleteOnesTenantCommand(FABRIC, TENANT));

        Assert.assertTrue(a.getResult());
        Assert.assertEquals("already gone", a.getDetails());
    }

    // ------------------------------------------------------------------ servers

    @Test
    public void allocateServersPatchesAddWithSharedServers() {
        tenants.put(TENANT, tenantRecord(TENANT));
        AllocateOnesServersCommand cmd = new AllocateOnesServersCommand(FABRIC, TENANT, Arrays.asList("gpu-01", "gpu-02"), true);
        Answer a = client().allocateServers(cmd);

        Assert.assertTrue(a.getDetails(), a.getResult());
        String key = "PATCH /fm/fabrics/" + FABRIC + "/tenants/" + TENANT;
        Assert.assertEquals(Collections.singletonList(key), asyncRequests);
        JsonObject sent = JsonParser.parseString(bodies.get(key)).getAsJsonObject();
        Assert.assertEquals("ADD", sent.get("operation").getAsString());
        JsonArray servers = sent.getAsJsonArray("servers");
        Assert.assertEquals(2, servers.size());
        Assert.assertEquals("gpu-01", servers.get(0).getAsJsonObject().get("serverName").getAsString());
        Assert.assertTrue(servers.get(0).getAsJsonObject().get("shared").getAsBoolean());
        Assert.assertEquals(1, operationPolls);
    }

    @Test
    public void deallocateServersPatchesDelete() {
        tenants.put(TENANT, tenantRecord(TENANT));
        Answer a = client().deallocateServers(new DeallocateOnesServersCommand(FABRIC, TENANT, Collections.singletonList("gpu-01")));

        Assert.assertTrue(a.getResult());
        JsonObject sent = JsonParser.parseString(bodies.get("PATCH /fm/fabrics/" + FABRIC + "/tenants/" + TENANT)).getAsJsonObject();
        Assert.assertEquals("DELETE", sent.get("operation").getAsString());
    }

    @Test
    public void allocateServersOnAMissingTenantFails() {
        Answer a = client().allocateServers(new AllocateOnesServersCommand(FABRIC, TENANT, Collections.singletonList("gpu-01"), true));

        Assert.assertFalse(a.getResult());
        Assert.assertTrue(a.getDetails(), a.getDetails().contains("HTTP 404"));
    }

    // ------------------------------------------------------------------ TLS

    @Test
    public void clientCertificateFromAnotherCaIsRejected() throws Exception {
        KeyPair rogueCaKeys = keyPair();
        X509Certificate rogueCa = certificate(rogueCaKeys, null, rogueCaKeys.getPublic(), "CN=Rogue CA", true, false);
        KeyPair rogueKeys = keyPair();
        X509Certificate rogue = certificate(rogueCaKeys, rogueCa, rogueKeys.getPublic(), "CN=rogue", false, false);
        File cert = pem(new File(pemDir, "rogue.crt"), rogue);
        File key = pem(new File(pemDir, "rogue.key"), rogueKeys.getPrivate());

        Assert.assertFalse(client("secret", cert, key, caPem).isReachable());
        Assert.assertEquals(0, logins);
    }

    @Test
    public void serverCertificateFromAnUntrustedCaIsRejected() throws Exception {
        KeyPair otherCaKeys = keyPair();
        X509Certificate otherCa = certificate(otherCaKeys, null, otherCaKeys.getPublic(), "CN=Other CA", true, false);
        File otherCaPem = pem(new File(pemDir, "other-ca.crt"), otherCa);

        Assert.assertFalse(client("secret", clientCert, clientKey, otherCaPem).isReachable());
        Assert.assertEquals(0, logins);
    }

    // ------------------------------------------------------------------ JSON helpers

    @Test
    public void jsonHelpers() {
        Assert.assertEquals("10.0.0.1", OnesApiClientImpl.stripPrefix("10.0.0.1/24"));
        Assert.assertEquals("10.0.0.1", OnesApiClientImpl.stripPrefix("10.0.0.1"));
        Assert.assertNull(OnesApiClientImpl.stripPrefix(null));
        Assert.assertNull(OnesApiClientImpl.parse("not json {"));
        JsonObject o = OnesApiClientImpl.asObject(JsonParser.parseString("{\"data\":\"{\\\"a\\\":1}\"}"), "data");
        Assert.assertEquals(Integer.valueOf(1), OnesApiClientImpl.integer(o, "a"));
        Assert.assertNull(OnesApiClientImpl.str(o, "missing"));
        Assert.assertNull(OnesApiClientImpl.asObject(JsonParser.parseString("{\"data\":[1]}"), "data"));
    }

    // ------------------------------------------------------------------ certificates

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static X509Certificate certificate(final KeyPair signer, final X509Certificate signerCert, final PublicKey subjectKey,
            final String subject, final boolean ca, final boolean server) throws Exception {
        X500Name issuer = signerCert != null ? new X500Name(signerCert.getSubjectX500Principal().getName()) : new X500Name(subject);
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(System.nanoTime()),
                new Date(System.currentTimeMillis() - 60_000L), new Date(System.currentTimeMillis() + 3_600_000L),
                new X500Name(subject), subjectKey);
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        if (ca) {
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        }
        if (server) {
            b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                new GeneralName(GeneralName.iPAddress, "127.0.0.1"), new GeneralName(GeneralName.dNSName, "localhost")}));
        }
        ContentSigner s = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(signer.getPrivate());
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(b.build(s));
    }

    private static File pem(final File file, final Object object) throws Exception {
        try (JcaPEMWriter w = new JcaPEMWriter(new FileWriter(file))) {
            w.writeObject(object);
        }
        return file;
    }
}
