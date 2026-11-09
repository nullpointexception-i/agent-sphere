package com.buukle.agent.capability.mcp.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.mcp.dtvo.vo.McpHubVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;

import java.util.List;

/**
 * Tenant-scoped MCP Hub: publish from a local MCP, browse tenant + ancestor hubs,
 * follow without copying, or install a private copy.
 */
public interface McpHubService {

    /** Publish (or re-publish) one local MCP into its tenant hub; every call bumps the version. */
    McpHubVO publish(Long mcpId);

    /** Hub entries of the current tenant plus every ancestor tenant. */
    IPage<McpHubVO> pageHubMcps(int page, int size, String keyword);

    /** One hub entry with its shared auth config, when visible to the current tenant. */
    McpHubVO getHub(Long hubId);

    /** Hub entries the current user follows. */
    List<McpHubVO> listFollowed();

    /** Subscribe to a hub entry without creating a local copy. */
    void follow(Long hubId);

    /** Remove the subscription; installed copies and instance bindings are unaffected. */
    void unfollow(Long hubId);

    /** Install a hub entry as a local copy; the auth config is copied from the hub. */
    McpVO install(Long hubId, Boolean autoUpdate);
}
