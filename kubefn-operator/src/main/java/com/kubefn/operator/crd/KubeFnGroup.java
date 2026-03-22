package com.kubefn.operator.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("kubefn.com")
@Version("v1alpha1")
@Kind("KubeFnGroup")
@Plural("kubefngroups")
@ShortNames("kfg")
public class KubeFnGroup extends CustomResource<KubeFnGroupSpec, KubeFnGroupStatus>
        implements Namespaced {

    @Override
    protected KubeFnGroupStatus initStatus() {
        return new KubeFnGroupStatus();
    }
}
