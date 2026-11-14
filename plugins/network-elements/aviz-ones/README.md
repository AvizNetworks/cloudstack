<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# Aviz ONES Network Plugin for Apache CloudStack

Multi-tenancy integration between Apache CloudStack and the **Aviz ONES Spectrum-X** fabric
manager (NVIDIA reference-architecture GPU clusters). A CloudStack **isolated guest network**
maps 1:1 to a **ONES tenant** — creating the network calls ONES's `Create Tenant` API, which
provisions East-West VXLAN isolation, a North-South VLAN, and a dedicated compute IP pool;
CloudStack surfaces that IP pool as the network's gateway/CIDR. As VMs land on hypervisor hosts
within that network, the plugin allocates those physical GPU servers to the tenant via ONES's
GPU-server allocate/deallocate API.

Scope of this version: **core multi-tenancy only** — tenant lifecycle (create/delete) and
GPU-server allocation to a tenant. HBN VF-interface assignment, UFM (InfiniBand), and NMX-C
(NVLink) integration from the ONES API are **not** implemented here.

## Architecture

Modeled on CloudStack's established SDN-controller plugin pattern (the same shape used by
NiciraNvp, BigSwitch, and Ovs on this branch, and by the newer upstream Netris plugin): the ONES
controller is registered as a physical-network-scoped **device**, represented internally as a
pseudo-agent host, with all southbound calls routed through the normal Agent Command/Answer
framework rather than called directly from business logic.

| Layer | Class | Role |
|---|---|---|
| DB | `com.cloud.network.OnesProviderVO` / `OnesProviderDao` | One row per registered ONES controller, scoped to a `physical_network_id` (table `ones_providers`) |
| API | `AddOnesProviderCmd` / `DeleteOnesProviderCmd` / `ListOnesProvidersCmd` | Register/remove/list the controller |
| Agent | `OnesResource` (`ServerResource`) | Represents the controller as an `L2Networking`-type pseudo-host; dispatches commands to the REST client |
| Agent | `CreateOnesTenantCommand`, `DeleteOnesTenantCommand`, `AllocateOnesServersCommand`, `DeallocateOnesServersCommand` (+ Answers) | The 4 southbound operations this plugin needs |
| REST | `OnesApiClient` / `OnesApiClientImpl` | mTLS + JWT client for the ONES tenant API (`java` `HttpClient` via Apache HttpClient 4.5, BouncyCastle for PEM cert/key loading) |
| Network SPI | `OnesGuestNetworkGuru` | On `implement()`: calls Create Tenant, sets the guest network's gateway/CIDR from ONES's returned IP pool. On `shutdown()`: deallocates any remaining hosts and calls Delete Tenant. |
| Network SPI | `OnesElement` | Registers the controller resource; on `prepare()` (VM placement) allocates the destination hypervisor host to the tenant if not already a member. |

**Key design point**: unlike most SDN guest network gurus (which self-allocate a VNI under a
CloudStack-chosen guest CIDR), ONES computes the VNI, VLAN, *and* the compute IP pool
(gateway/subnet) server-side as part of `Create Tenant`. `OnesGuestNetworkGuru.implement()`
treats the CIDR/gateway as externally assigned and only knows them once ONES responds — it does
not pick them itself.

Host↔tenant membership (which physical GPU servers are currently allocated to a network's
tenant) is tracked via `NetworkDetail` key/value entries (`onestenantname`,
`onesallocatedhosts`) rather than a dedicated join table, to keep the schema footprint minimal.

## This copy: ported to CloudStack 4.22.1.1 (lab)

Changes from the 4.19 original:
- **Provider**: `AvizOnes` is defined by the plugin (`OnesElement.AVIZ_ONES`), not CloudStack core.
- **Auth**: logs in with a ONES user and password (`POST https://<ones>/api/user/login`) and
  re-logs in on a 401; no static JWT. The password is stored encrypted in `ones_providers`.
- **Async**: every change is `Prefer: respond-async` plus polling `/operations/{id}`. FM sends
  tenant webhooks (what the edge gateway learns tenants from) only for async calls.
- **Tenant at network create** (guru `design`), so the network's CIDR and gateway are ONES's from
  the start; deleted on network delete (`trash`); hosts taken out on network `shutdown`.
- **VLAN, not VXLAN**: the guest network is `vlan://<tenant vlanId>`, the VLAN ONES trunks to
  the hypervisors' leaf ports; VXLAN stays inside the fabric.
- **Addresses**: CIDR = tenant subnet (`vlanSubnetCPU`); VM/VR addresses only from the
  compute pool (`tenantIpPool`), .10 up.
- **Hosts are attached `shared: true`** (one hypervisor carries many tenants).
- **Tenant names** `cs` + 10 hex (ONES allows at most 12 characters).
- **Gateway**: CloudStack requires `gateway=`/`netmask=` on createNetwork for an isolated
  network without SourceNat, before ONES has chosen the subnet. A gateway inside the subnet ONES
  assigns (the leaf SVI) is used; one outside it (e.g. `10.255.255.1`/`255.255.255.0` - CloudStack
  insists on RFC 1918) is a placeholder: the network takes ONES's subnet and reported gateway.
  With tenant IPAM off that reported gateway is a placeholder nothing answers on, and ONES does
  not expose the leaf SVI - east-west works, nothing routes out until ONES exposes it.
- **No orphan tenants**: if the network cannot be designed after ONES created the tenant, the
  plugin deletes it again.

**Edge, lab/demo (2026-11-10)**: `updateOnesProvider` adds the Aviz Gateway. Set by
`cs/ones-edge-setup.py <lab>` (re-run after a lab redeploy; `setup/upgrade-1-gateway.sql` once):
- `tenantgatewaysuffix` (`128.3`): a network's gateway is the tenant's leaf SVI 192.T.128.3, not
  ONES's placeholder - a lab rule until ONES exposes the gateway.
- `gatewayurl` / `gatewaytoken` / `gatewaycacertpath`: the plugin calls the gateway directly
  (ONES has no API for NAT): once a network's first host joined the tenant it waits for the
  gateway to have the tenant (ONES webhook -> edge configurator) and turns on internet access
  with one address from `publicips`; off again on shutdown/delete. The token is the gateway's
  first-boot token (full rights; demo-grade).
- Caveat: tenant subnets 192.T.0.0/16 are PUBLIC internet space (the lab's
  startingSubnetTenants "192.0"), so a tenant with internet that pings another tenant's address
  reaches a real host on the internet, not the other tenant.

Verified live 2026-11-10 (lab fabric SIM-CS01, 2 KVM hosts): create network -> tenant (VLAN 103,
pool 192.3.64.0/18) -> VMs on both hosts, hosts added shared, addresses from .10 -> reachable
across racks, isolated from other tenants -> delete -> hosts removed, tenant gone from the leaves.

Build: `./build.sh` (needs a copy of the management server's
`/usr/share/cloudstack-management/lib` in `../cs-lib`). Install: apply
`setup/create-ones-providers.sql` once, copy the jar into
`/usr/share/cloudstack-management/lib/`, restart `cloudstack-management`.

Register (TLS is verified: ONES's stock certificate names `ones-fm`, so map that name to the
ONES address in /etc/hosts and trust its certificate with `cacertpath`):

```bash
addNetworkServiceProvider name=AvizOnes physicalnetworkid=<uuid>   # then enable it
addOnesProvider physicalnetworkid=<uuid> name=ones1 url=https://ones-fm:8443 \
    fabricname=<fabric> username=<ones user> password=<password> cacertpath=/etc/cloudstack/ones-ca.pem
```

The physical network needs `isolationmethods=ONES`; the network offering is Isolated with
Connectivity=AvizOnes (and Dhcp/Dns/UserData=VirtualRouter, no SourceNat).

## Testing

(Lab copy: the original `OnesApiClientImplTest` targeted the removed JWT/mTLS flow and was
dropped; it needs rewriting for login + async.) The original test exercised the REST client end-to-end (mTLS handshake, JWT header, JSON
request/response shapes for create/delete tenant and allocate/deallocate servers) against a
local HTTPS server with a throwaway self-signed CA/client/server certificate chain generated at
test time with BouncyCastle — no real ONES controller is required to run it:

```bash
mvn -pl plugins/network-elements/aviz-ones test
```

To build the whole module (with checkstyle):

```bash
mvn -pl plugins/network-elements/aviz-ones -am clean install
```
