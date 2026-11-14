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

/**
 * Maps to ONES "Create Tenant": POST /fabrics/{fabricName}/tenants
 */
public class CreateOnesTenantCommand extends Command {

    private final String fabricName;
    private final String tenantName;
    private final String description;
    private final int maxGpusAllowed;

    public CreateOnesTenantCommand(final String fabricName, final String tenantName, final String description, final int maxGpusAllowed) {
        this.fabricName = fabricName;
        this.tenantName = tenantName;
        this.description = description;
        this.maxGpusAllowed = maxGpusAllowed;
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

    public String getDescription() {
        return description;
    }

    public int getMaxGpusAllowed() {
        return maxGpusAllowed;
    }
}
