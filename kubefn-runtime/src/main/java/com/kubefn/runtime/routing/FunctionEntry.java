package com.kubefn.runtime.routing;

import com.kubefn.api.KubeFnHandler;

/**
 * A registered function entry in the router.
 * Links a route to its handler instance and metadata.
 */
public record FunctionEntry(
        String groupName,
        String functionName,
        String className,
        String revisionId,
        KubeFnHandler handler
) {}
