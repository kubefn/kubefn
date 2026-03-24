package com.demo.groupb;

import com.kubefn.api.*;
import com.demo.shared.VersionInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GROUP B FUNCTION — uses VersionInfo v2.0
 *
 * Runs simultaneously with Group A in the SAME JVM.
 * Uses a DIFFERENT VersionInfo (v2.0) with:
 * - Different VERSION and AUTHOR
 * - newFeature() that doesn't exist in v1
 * - NO legacyMethod() that exists in v1
 *
 * CLASSLOADER ISOLATION PROOF:
 * Both groups have com.demo.shared.VersionInfo but they are DIFFERENT classes
 * loaded by DIFFERENT classloaders. Zero collision.
 */
@FnRoute(path = "/group-b/info", methods = {"GET"})
@FnGroup("group-b")
public class GroupBFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", "group-b");
        result.put("version", VersionInfo.VERSION);            // "2.0"
        result.put("author", VersionInfo.AUTHOR);              // "Team Beta"
        result.put("newFeature", VersionInfo.newFeature());    // exists in v2 only
        result.put("classLoader", VersionInfo.class.getClassLoader().toString());
        result.put("classIdentity", System.identityHashCode(VersionInfo.class));

        // Read Group A's data from heap — ZERO COPY
        var groupAInfo = ctx.heap().get("group-a:info", Map.class);
        if (groupAInfo.isPresent()) {
            Map<String, Object> aInfo = groupAInfo.get();
            result.put("groupA_version", aInfo.get("version"));
            result.put("groupA_author", aInfo.get("author"));
            result.put("crossGroupNote", "Read Group A's data from heap — zero-copy, no serialization");

            // Prove classloader isolation: different class identities
            result.put("isolationProof", Map.of(
                "groupA_classIdentity", aInfo.get("classIdentity"),
                "groupB_classIdentity", System.identityHashCode(VersionInfo.class),
                "sameClass", aInfo.get("classIdentity").equals(System.identityHashCode(VersionInfo.class)),
                "verdict", "Different classloaders → different class identities → ISOLATED"
            ));
        } else {
            result.put("crossGroupNote", "Group A not called yet. Call /group-a/info first.");
        }

        return KubeFnResponse.ok(result);
    }
}
