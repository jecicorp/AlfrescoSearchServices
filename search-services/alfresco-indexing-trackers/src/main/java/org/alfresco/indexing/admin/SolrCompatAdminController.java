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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solr-compatible admin endpoint that mimics the original
 * {@code /solr/admin/cores?action=...} interface used by
 * Alfresco Repository and Share admin console.
 *
 * <p>Dispatches to {@link AdminService} based on the {@code action} parameter
 * and wraps responses in the Solr response format.</p>
 */
@RestController
public class SolrCompatAdminController
{
    private final AdminService adminService;

    public SolrCompatAdminController(AdminService adminService)
    {
        this.adminService = adminService;
    }

    @GetMapping("/solr/admin/cores")
    public Map<String, Object> dispatch(
            @RequestParam("action") String action,
            @RequestParam(value = "core", required = false) String core,
            @RequestParam(value = "coreName", required = false) String coreName,
            @RequestParam(value = "cores", required = false) String cores,
            @RequestParam(value = "metrics", required = false) String metrics,
            @RequestParam(value = "txid", required = false) Long txid,
            @RequestParam(value = "acltxid", required = false) Long acltxid,
            @RequestParam(value = "nodeid", required = false) Long nodeid,
            @RequestParam(value = "nodeId", required = false) Long nodeId,
            @RequestParam(value = "aclid", required = false) Long aclid,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "storeRef", required = false) String storeRef,
            @RequestParam(value = "template", required = false) String template,
            @RequestParam(value = "resource", required = false) String resource,
            @RequestParam(value = "fromTime", required = false) Long fromTime,
            @RequestParam(value = "toTime", required = false) Long toTime,
            @RequestParam(value = "wt", required = false) String wt)
    {
        long startTime = System.currentTimeMillis();

        try
        {
            String upperAction = action.toUpperCase();
            Map<String, Object> data = executeAction(upperAction, core, coreName, cores, metrics,
                    txid, acltxid, nodeid, nodeId, aclid, query,
                    storeRef, template, resource, fromTime, toTime);

            String responseKey = resolveResponseKey(upperAction);
            long qTime = System.currentTimeMillis() - startTime;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("responseHeader", buildHeader(0, qTime));
            response.put(responseKey, data);
            return response;
        }
        catch (Exception e)
        {
            long qTime = System.currentTimeMillis() - startTime;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("responseHeader", buildHeader(1, qTime));
            response.put("error", e.getMessage());
            return response;
        }
    }

    private Map<String, Object> executeAction(String upperAction,
            String core, String coreName, String cores, String metrics,
            Long txid, Long acltxid, Long nodeid, Long nodeId, Long aclid,
            String query, String storeRef, String template, String resource,
            Long fromTime, Long toTime)
    {
        String effectiveCore = core != null ? core : coreName;
        Long effectiveNodeId = nodeid != null ? nodeid : nodeId;

        return switch (upperAction)
        {
            case "SUMMARY" -> adminService.summary(effectiveCore, cores, metrics);
            case "REPORT" -> adminService.report(effectiveCore, fromTime, toTime);
            case "NODEREPORT" -> adminService.nodeReport(effectiveNodeId, effectiveCore);
            case "ACLREPORT" -> adminService.aclReport(aclid, effectiveCore);
            case "TXREPORT" -> adminService.txReport(txid, effectiveCore);
            case "ACLTXREPORT" -> adminService.aclTxReport(acltxid, effectiveCore);
            case "CHECK" -> adminService.check(effectiveCore);
            case "PURGE" -> adminService.purge(txid, acltxid, effectiveNodeId, aclid, effectiveCore);
            case "REINDEX" -> adminService.reindex(txid, acltxid, effectiveNodeId, aclid, query, effectiveCore);
            case "RETRY" -> adminService.retry(effectiveCore);
            case "INDEX" -> adminService.index(txid, acltxid, effectiveNodeId, aclid, effectiveCore);
            case "LOG4J" -> adminService.log4j(resource);
            case "NEWCORE", "NEWINDEX" -> adminService.newCore(coreName, storeRef, template);
            case "UPDATECORE", "UPDATEINDEX" -> adminService.updateCore(coreName);
            case "UPDATESHARED" -> adminService.updateShared();
            case "NEWDEFAULTINDEX", "NEWDEFAULTCORE" -> adminService.newDefaultIndex(coreName, storeRef, template);
            case "REMOVECORE" -> adminService.removeCore(coreName, storeRef);
            default -> throw new IllegalArgumentException("Unknown action: " + upperAction);
        };
    }

    private String resolveResponseKey(String upperAction)
    {
        return switch (upperAction)
        {
            case "SUMMARY" -> "Summary";
            case "NODEREPORT", "ACLREPORT", "TXREPORT", "ACLTXREPORT", "REPORT" -> "report";
            default -> "action";
        };
    }

    private Map<String, Object> buildHeader(int status, long qTime)
    {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("status", status);
        header.put("QTime", qTime);
        return header;
    }
}
