package com.demo.groupa;

import com.kubefn.api.*;
import com.demo.shared.VersionInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GROUP A FUNCTION — uses VersionInfo v1.0
 *
 * This function runs simultaneously with Group B's function in the SAME JVM.
 * Group B uses a DIFFERENT VersionInfo class (v2.0) with incompatible changes.
 *
 * PROOF OF ISOLATION:
 * - This function calls VersionInfo.legacyMethod() (only exists in v1)
 * - Group B calls VersionInfo.newFeature() (only exists in v2)
 * - Both work. No collision. Same JVM.
 *
 * In microservices: this works because separate JVMs.
 * In KubeFn: this works because separate classloaders. Zero serialization overhead.
 */
@FnRoute(path = "/group-a/info", methods = {"GET"})
@FnGroup("group-a")
public class GroupAFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", "group-a");
        result.put("version", VersionInfo.VERSION);            // "1.0"
        result.put("author", VersionInfo.AUTHOR);              // "Team Alpha"
        result.put("legacyMethod", VersionInfo.legacyMethod()); // exists in v1 only
        result.put("classLoader", VersionInfo.class.getClassLoader().toString());
        result.put("classIdentity", System.identityHashCode(VersionInfo.class));

        // Publish to heap — Group B can read this zero-copy
        ctx.heap().publish("group-a:info", result, Map.class);

        result.put("heapNote", "Published to heap key 'group-a:info'. Group B reads this zero-copy.");
        return KubeFnResponse.ok(result);
    }
}
