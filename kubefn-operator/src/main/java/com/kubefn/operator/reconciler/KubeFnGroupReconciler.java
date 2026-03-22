package com.kubefn.operator.reconciler;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import com.kubefn.operator.crd.KubeFnGroup;
import com.kubefn.operator.crd.KubeFnGroupSpec;
import com.kubefn.operator.crd.KubeFnGroupStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ControllerConfiguration
public class KubeFnGroupReconciler implements Reconciler<KubeFnGroup> {

    private static final Logger log = LoggerFactory.getLogger(KubeFnGroupReconciler.class);

    private static final String LABEL_APP_NAME = "app.kubernetes.io/name";
    private static final String LABEL_GROUP = "kubefn.com/group";
    private static final String APP_NAME_VALUE = "kubefn-runtime";
    private static final int HTTP_PORT = 8080;
    private static final int ADMIN_PORT = 8081;

    private KubernetesClient client;

    public KubeFnGroupReconciler() {}

    @Override
    public UpdateControl<KubeFnGroup> reconcile(KubeFnGroup resource, Context<KubeFnGroup> context) {
        this.client = context.getClient();
        String name = resource.getMetadata().getName();
        String namespace = resource.getMetadata().getNamespace();
        KubeFnGroupSpec spec = resource.getSpec();

        log.info("Reconciling KubeFnGroup '{}' in namespace '{}'", name, namespace);

        try {
            Deployment desired = buildDeployment(resource);
            client.apps().deployments().inNamespace(namespace).resource(desired).serverSideApply();

            Service desiredService = buildService(resource);
            client.services().inNamespace(namespace).resource(desiredService).serverSideApply();

            updateStatus(resource, namespace, name);

            log.info("Successfully reconciled KubeFnGroup '{}'", name);
            return UpdateControl.patchStatus(resource);
        } catch (Exception e) {
            log.error("Failed to reconcile KubeFnGroup '{}': {}", name, e.getMessage(), e);
            resource.getStatus().setPhase("Error");
            return UpdateControl.patchStatus(resource);
        }
    }

    private Deployment buildDeployment(KubeFnGroup resource) {
        String name = resource.getMetadata().getName();
        String namespace = resource.getMetadata().getNamespace();
        KubeFnGroupSpec spec = resource.getSpec();
        Map<String, String> labels = buildLabels(name);

        Container container = buildContainer(spec, name);

        return new DeploymentBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .withLabels(labels)
                        .withOwnerReferences(new OwnerReferenceBuilder()
                                .withApiVersion(resource.getApiVersion())
                                .withKind(resource.getKind())
                                .withName(name)
                                .withUid(resource.getMetadata().getUid())
                                .withController(true)
                                .withBlockOwnerDeletion(true)
                                .build())
                        .build())
                .withNewSpec()
                    .withReplicas(spec.getReplicas())
                    .withNewSelector()
                        .withMatchLabels(labels)
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                            .withContainers(container)
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private Container buildContainer(KubeFnGroupSpec spec, String name) {
        ContainerBuilder builder = new ContainerBuilder()
                .withName(name)
                .withImage(spec.getImage())
                .withPorts(
                        new ContainerPortBuilder()
                                .withName("http")
                                .withContainerPort(HTTP_PORT)
                                .build(),
                        new ContainerPortBuilder()
                                .withName("admin")
                                .withContainerPort(ADMIN_PORT)
                                .build())
                .withLivenessProbe(buildProbe("/healthz"))
                .withReadinessProbe(buildProbe("/readyz"));

        List<String> command = buildCommand(spec);
        if (!command.isEmpty()) {
            builder.withArgs(command);
        }

        List<EnvVar> envVars = buildEnvVars(spec);
        if (!envVars.isEmpty()) {
            builder.withEnv(envVars);
        }

        if (spec.getResources() != null) {
            builder.withResources(buildResourceRequirements(spec.getResources()));
        }

        return builder.build();
    }

    private Probe buildProbe(String path) {
        return new ProbeBuilder()
                .withNewHttpGet()
                    .withPath(path)
                    .withPort(new IntOrString(ADMIN_PORT))
                .endHttpGet()
                .withInitialDelaySeconds(10)
                .withPeriodSeconds(10)
                .withTimeoutSeconds(3)
                .withFailureThreshold(3)
                .build();
    }

    private List<String> buildCommand(KubeFnGroupSpec spec) {
        if (spec.getJvmArgs() == null || spec.getJvmArgs().isEmpty()) {
            return List.of();
        }
        return List.copyOf(spec.getJvmArgs());
    }

    private List<EnvVar> buildEnvVars(KubeFnGroupSpec spec) {
        if (spec.getConfig() == null || spec.getConfig().isEmpty()) {
            return List.of();
        }
        List<EnvVar> envVars = new ArrayList<>();
        for (Map.Entry<String, String> entry : spec.getConfig().entrySet()) {
            envVars.add(new EnvVarBuilder()
                    .withName(entry.getKey())
                    .withValue(entry.getValue())
                    .build());
        }
        return envVars;
    }

    private io.fabric8.kubernetes.api.model.ResourceRequirements buildResourceRequirements(
            KubeFnGroupSpec.ResourceRequirements resources) {
        ResourceRequirementsBuilder builder = new ResourceRequirementsBuilder();

        if (resources.getRequests() != null) {
            Map<String, Quantity> requests = new HashMap<>();
            if (resources.getRequests().getCpu() != null) {
                requests.put("cpu", new Quantity(resources.getRequests().getCpu()));
            }
            if (resources.getRequests().getMemory() != null) {
                requests.put("memory", new Quantity(resources.getRequests().getMemory()));
            }
            builder.withRequests(requests);
        }

        if (resources.getLimits() != null) {
            Map<String, Quantity> limits = new HashMap<>();
            if (resources.getLimits().getCpu() != null) {
                limits.put("cpu", new Quantity(resources.getLimits().getCpu()));
            }
            if (resources.getLimits().getMemory() != null) {
                limits.put("memory", new Quantity(resources.getLimits().getMemory()));
            }
            builder.withLimits(limits);
        }

        return builder.build();
    }

    private Service buildService(KubeFnGroup resource) {
        String name = resource.getMetadata().getName();
        String namespace = resource.getMetadata().getNamespace();
        Map<String, String> labels = buildLabels(name);

        return new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .withLabels(labels)
                        .withOwnerReferences(new OwnerReferenceBuilder()
                                .withApiVersion(resource.getApiVersion())
                                .withKind(resource.getKind())
                                .withName(name)
                                .withUid(resource.getMetadata().getUid())
                                .withController(true)
                                .withBlockOwnerDeletion(true)
                                .build())
                        .build())
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(labels)
                    .withPorts(
                            new ServicePortBuilder()
                                    .withName("http")
                                    .withPort(80)
                                    .withTargetPort(new IntOrString(HTTP_PORT))
                                    .build(),
                            new ServicePortBuilder()
                                    .withName("admin")
                                    .withPort(ADMIN_PORT)
                                    .withTargetPort(new IntOrString(ADMIN_PORT))
                                    .build())
                .endSpec()
                .build();
    }

    private void updateStatus(KubeFnGroup resource, String namespace, String name) {
        KubeFnGroupStatus status = resource.getStatus();
        if (status == null) {
            status = new KubeFnGroupStatus();
            resource.setStatus(status);
        }

        Deployment existing = client.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .get();

        if (existing != null && existing.getStatus() != null) {
            int ready = existing.getStatus().getReadyReplicas() != null
                    ? existing.getStatus().getReadyReplicas()
                    : 0;
            status.setReadyReplicas(ready);

            boolean available = false;
            if (existing.getStatus().getConditions() != null) {
                for (DeploymentCondition condition : existing.getStatus().getConditions()) {
                    if ("Available".equals(condition.getType())
                            && "True".equals(condition.getStatus())) {
                        available = true;
                        break;
                    }
                }
            }
            status.setPhase(available ? "Running" : "Progressing");
        } else {
            status.setPhase("Pending");
        }
    }

    private Map<String, String> buildLabels(String groupName) {
        Map<String, String> labels = new HashMap<>();
        labels.put(LABEL_APP_NAME, APP_NAME_VALUE);
        labels.put(LABEL_GROUP, groupName);
        return labels;
    }
}
