/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Pristy software, developed by Jeci SARL.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
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
            @RequestParam(value = "core", required = false) String core,
            @RequestParam(value = "cores", required = false) String cores,
            @RequestParam(value = "metrics", required = false) String metrics)
    {
        return adminService.summary(core, cores, metrics);
    }

    @GetMapping("/report")
    public Map<String, Object> report(
            @RequestParam(value = "core", required = false) String core,
            @RequestParam(value = "coreName", required = false) String coreName,
            @RequestParam(value = "fromTime", required = false) Long fromTime,
            @RequestParam(value = "toTime", required = false) Long toTime)
    {
        String effectiveCore = core != null ? core : coreName;
        return adminService.report(effectiveCore, fromTime, toTime);
    }

    @GetMapping("/node-report")
    public Map<String, Object> nodeReport(
            @RequestParam(value = "nodeid", required = false) Long nodeid,
            @RequestParam(value = "core", required = false) String core)
    {
        return adminService.nodeReport(nodeid, core);
    }

    @GetMapping("/acl-report")
    public Map<String, Object> aclReport(
            @RequestParam(value = "aclid", required = false) Long aclid,
            @RequestParam(value = "core", required = false) String core)
    {
        return adminService.aclReport(aclid, core);
    }

    @GetMapping("/tx-report")
    public Map<String, Object> txReport(
            @RequestParam(value = "txid", required = false) Long txid,
            @RequestParam(value = "core", required = false) String core,
            @RequestParam(value = "coreName", required = false) String coreName)
    {
        String effectiveCore = core != null ? core : coreName;
        return adminService.txReport(txid, effectiveCore);
    }

    @GetMapping("/acltx-report")
    public Map<String, Object> aclTxReport(
            @RequestParam(value = "acltxid", required = false) Long acltxid,
            @RequestParam(value = "core", required = false) String core)
    {
        return adminService.aclTxReport(acltxid, core);
    }

    @GetMapping("/check")
    public Map<String, Object> check(
            @RequestParam(value = "core", required = false) String core)
    {
        return adminService.check(core);
    }

    @PostMapping("/purge")
    public Map<String, Object> purge(
            @RequestParam(value = "txid", required = false) Long txid,
            @RequestParam(value = "acltxid", required = false) Long acltxid,
            @RequestParam(value = "nodeid", required = false) Long nodeid,
            @RequestParam(value = "aclid", required = false) Long aclid,
            @RequestParam(value = "core", required = false) String core)
    {
        return adminService.purge(txid, acltxid, nodeid, aclid, core);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex(
            @RequestParam(value = "txid", required = false) Long txid,
            @RequestParam(value = "acltxid", required = false) Long acltxid,
            @RequestParam(value = "nodeid", required = false) Long nodeid,
            @RequestParam(value = "nodeId", required = false) Long nodeId,
            @RequestParam(value = "aclid", required = false) Long aclid,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "core", required = false) String core)
    {
        Long effectiveNodeId = nodeid != null ? nodeid : nodeId;
        return adminService.reindex(txid, acltxid, effectiveNodeId, aclid, query, core);
    }

    @PostMapping("/retry")
    public Map<String, Object> retry(
            @RequestParam(value = "core", required = false) String core)
    {
        return adminService.retry(core);
    }

    @PostMapping("/index")
    public Map<String, Object> index(
            @RequestParam(value = "txid", required = false) Long txid,
            @RequestParam(value = "acltxid", required = false) Long acltxid,
            @RequestParam(value = "nodeid", required = false) Long nodeid,
            @RequestParam(value = "aclid", required = false) Long aclid,
            @RequestParam(value = "core", required = false) String core)
    {
        return adminService.index(txid, acltxid, nodeid, aclid, core);
    }

    @PostMapping("/log4j")
    public Map<String, Object> log4j(
            @RequestParam(value = "resource", required = false) String resource)
    {
        return adminService.log4j(resource);
    }

    @PostMapping("/new-core")
    public Map<String, Object> newCore(
            @RequestParam("coreName") String coreName,
            @RequestParam("storeRef") String storeRef,
            @RequestParam("template") String template)
    {
        return adminService.newCore(coreName, storeRef, template);
    }

    @PostMapping("/update-core")
    public Map<String, Object> updateCore(
            @RequestParam("coreName") String coreName)
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
            @RequestParam("coreName") String coreName,
            @RequestParam("storeRef") String storeRef,
            @RequestParam("template") String template)
    {
        return adminService.newDefaultIndex(coreName, storeRef, template);
    }

    @PostMapping("/remove-core")
    public Map<String, Object> removeCore(
            @RequestParam("coreName") String coreName,
            @RequestParam("storeRef") String storeRef)
    {
        return adminService.removeCore(coreName, storeRef);
    }
}
