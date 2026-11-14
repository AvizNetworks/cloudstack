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

import com.cloud.agent.api.AllocateOnesServersCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CreateOnesTenantAnswer;
import com.cloud.agent.api.CreateOnesTenantCommand;
import com.cloud.agent.api.DeallocateOnesServersCommand;
import com.cloud.agent.api.DeleteOnesTenantCommand;

/**
 * Thin REST client for the Aviz ONES Spectrum-X multi-tenancy API (see ONES_API.pdf):
 * mutual-TLS + JWT bearer auth over HTTPS on the fabric controller's API port (8089 by default).
 */
public interface OnesApiClient {

    CreateOnesTenantAnswer createTenant(CreateOnesTenantCommand cmd);

    Answer deleteTenant(DeleteOnesTenantCommand cmd);

    Answer allocateServers(AllocateOnesServersCommand cmd);

    Answer deallocateServers(DeallocateOnesServersCommand cmd);

    /**
     * GET /fabrics - used as a lightweight reachability/credential check when a provider is registered.
     */
    boolean isReachable();
}
