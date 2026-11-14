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

package com.cloud.agent.api;

import java.util.List;

/**
 * Maps to ONES "Allocate Servers" (operation=ADD):
 * PATCH /fabrics/{fabricName}/tenants/{tenantName}
 */
public class AllocateOnesServersCommand extends Command {

    private final String fabricName;
    private final String tenantName;
    private final List<String> serverNames;
    private final boolean shared;

    public AllocateOnesServersCommand(final String fabricName, final String tenantName, final List<String> serverNames, final boolean shared) {
        this.fabricName = fabricName;
        this.tenantName = tenantName;
        this.serverNames = serverNames;
        this.shared = shared;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }

    public String getFabricName() {
        return fabricName;
    }

    public String getTenantName() {
        return tenantName;
    }

    public List<String> getServerNames() {
        return serverNames;
    }

    public boolean isShared() {
        return shared;
    }
}
