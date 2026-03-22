package com.kubefn.operator.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * KubeFnFunction CRD — defines a single function within a group.
 *
 * When a KubeFnFunction is created:
 * 1. The operator finds the referenced KubeFnGroup's Deployment
 * 2. If the source is a ConfigMap, it mounts the ConfigMap as a volume
 * 3. It triggers a rolling restart to pick up the new function
 * 4. Updates the function status when loaded
 */
@Group("kubefn.com")
@Version("v1alpha1")
@Kind("KubeFnFunction")
@Plural("kubefnfunctions")
@ShortNames("kff")
public class KubeFnFunction extends CustomResource<KubeFnFunctionSpec, KubeFnFunctionStatus>
        implements Namespaced {

    @Override
    protected KubeFnFunctionStatus initStatus() {
        return new KubeFnFunctionStatus();
    }
}
