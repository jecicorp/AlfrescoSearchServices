/*
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2005 - 2024 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.indexing.admin;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clean REST API for tracker admin operations.
 * Delegates all calls to {@link AdminService}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController
{
    private final AdminService adminService;

    public AdminController(AdminService adminService)
    {
        this.adminService = adminService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(required = false) String core)
    {
        return adminService.summary(core);
    }

    @GetMapping("/report")
    public Map<String, Object> report(
            @RequestParam(required = false) String core,
            @RequestParam(required = false) String coreName,
            @RequestParam(required = false) Long fromTime,
            @RequestParam(required = false) Long toTime)
    {
        String effectiveCore = core != null ? core : coreName;
        return adminService.report(effectiveCore, fromTime, toTime);
    }

    @GetMapping("/node-report")
    public Map<String, Object> nodeReport(
            @RequestParam(required = false) Long nodeid,
            @RequestParam(required = false) String core)
    {
        return adminService.nodeReport(nodeid, core);
    }

    @GetMapping("/acl-report")
    public Map<String, Object> aclReport(
            @RequestParam(required = false) Long aclid,
            @RequestParam(required = false) String core)
    {
        return adminService.aclReport(aclid, core);
    }

    @GetMapping("/tx-report")
    public Map<String, Object> txReport(
            @RequestParam(required = false) Long txid,
            @RequestParam(required = false) String core,
            @RequestParam(required = false) String coreName)
    {
        String effectiveCore = core != null ? core : coreName;
        return adminService.txReport(txid, effectiveCore);
    }

    @GetMapping("/acltx-report")
    public Map<String, Object> aclTxReport(
            @RequestParam(required = false) Long acltxid,
            @RequestParam(required = false) String core)
    {
        return adminService.aclTxReport(acltxid, core);
    }

    @GetMapping("/check")
    public Map<String, Object> check(
            @RequestParam(required = false) String core)
    {
        return adminService.check(core);
    }

    @PostMapping("/purge")
    public Map<String, Object> purge(
            @RequestParam(required = false) Long txid,
            @RequestParam(required = false) Long acltxid,
            @RequestParam(required = false) Long nodeid,
            @RequestParam(required = false) Long aclid,
            @RequestParam(required = false) String core)
    {
        return adminService.purge(txid, acltxid, nodeid, aclid, core);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex(
            @RequestParam(required = false) Long txid,
            @RequestParam(required = false) Long acltxid,
            @RequestParam(required = false) Long nodeid,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) Long aclid,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String core)
    {
        Long effectiveNodeId = nodeid != null ? nodeid : nodeId;
        return adminService.reindex(txid, acltxid, effectiveNodeId, aclid, query, core);
    }

    @PostMapping("/retry")
    public Map<String, Object> retry(
            @RequestParam(required = false) String core)
    {
        return adminService.retry(core);
    }

    @PostMapping("/index")
    public Map<String, Object> index(
            @RequestParam(required = false) Long txid,
            @RequestParam(required = false) Long acltxid,
            @RequestParam(required = false) Long nodeid,
            @RequestParam(required = false) Long aclid,
            @RequestParam(required = false) String core)
    {
        return adminService.index(txid, acltxid, nodeid, aclid, core);
    }

    @PostMapping("/log4j")
    public Map<String, Object> log4j(
            @RequestParam(required = false) String resource)
    {
        return adminService.log4j(resource);
    }

    @PostMapping("/new-core")
    public Map<String, Object> newCore(
            @RequestParam String coreName,
            @RequestParam String storeRef,
            @RequestParam String template)
    {
        return adminService.newCore(coreName, storeRef, template);
    }

    @PostMapping("/update-core")
    public Map<String, Object> updateCore(
            @RequestParam String coreName)
    {
        return adminService.updateCore(coreName);
    }

    @PostMapping("/update-shared")
    public Map<String, Object> updateShared()
    {
        return adminService.updateShared();
    }

    @PostMapping("/new-default-index")
    public Map<String, Object> newDefaultIndex(
            @RequestParam String coreName,
            @RequestParam String storeRef,
            @RequestParam String template)
    {
        return adminService.newDefaultIndex(coreName, storeRef, template);
    }

    @PostMapping("/remove-core")
    public Map<String, Object> removeCore(
            @RequestParam String coreName,
            @RequestParam String storeRef)
    {
        return adminService.removeCore(coreName, storeRef);
    }
}
