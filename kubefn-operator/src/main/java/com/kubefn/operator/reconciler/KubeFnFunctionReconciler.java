package com.kubefn.operator.reconciler;

import com.kubefn.operator.crd.KubeFnFunction;
import com.kubefn.operator.crd.KubeFnFunctionSpec;
import com.kubefn.operator.crd.KubeFnFunctionStatus;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reconciles KubeFnFunction CRDs.
 *
 * When a KubeFnFunction is created/updated:
 * 1. Finds the referenced KubeFnGroup's Deployment
 * 2. Mounts the function's ConfigMap as a volume in the Deployment
 * 3. Triggers a rolling restart by updating an annotation
 * 4. Updates the function status
 *
 * This enables: kubectl apply -f function.yaml → auto-deploy
 */
@ControllerConfiguration
public class KubeFnFunctionReconciler implements Reconciler<KubeFnFunction> {

    private static final Logger log = LoggerFactory.getLogger(KubeFnFunctionReconciler.class);

    @Override
    public UpdateControl<KubeFnFunction> reconcile(KubeFnFunction resource,
                                                    io.javaoperatorsdk.operator.api.reconciler.Context<KubeFnFunction> context) {
        KubernetesClient client = context.getClient();
        String name = resource.getMetadata().getName();
        String namespace = resource.getMetadata().getNamespace();
        KubeFnFunctionSpec spec = resource.getSpec();

        log.info("Reconciling KubeFnFunction '{}' in namespace '{}' (group={})",
                name, namespace, spec.getGroupRef());

        try {
            // 1. Find the group's Deployment
            String groupName = spec.getGroupRef();
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(groupName)
                    .get();

            if (deployment == null) {
                log.warn("Deployment '{}' not found for function '{}'. " +
                        "Create the KubeFnGroup first.", groupName, name);
                updateStatus(resource, false, "Deployment not found: " + groupName);
                return UpdateControl.patchStatus(resource);
            }

            // 2. If source is ConfigMap, add volume mount
            if (spec.getSource() != null && "configmap".equals(spec.getSource().getType())
                    && spec.getSource().getConfigMapRef() != null) {

                String configMapName = spec.getSource().getConfigMapRef().getName();
                String volumeName = "fn-" + name;
                String mountPath = "/var/kubefn/functions/" + groupName + "/" + name;

                // Check if ConfigMap exists
                ConfigMap cm = client.configMaps()
                        .inNamespace(namespace)
                        .withName(configMapName)
                        .get();

                if (cm == null) {
                    log.warn("ConfigMap '{}' not found for function '{}'", configMapName, name);
                    updateStatus(resource, false, "ConfigMap not found: " + configMapName);
                    return UpdateControl.patchStatus(resource);
                }

                // Add volume and volume mount to the deployment
                var podSpec = deployment.getSpec().getTemplate().getSpec();
                var container = podSpec.getContainers().get(0);

                // Check if volume already exists
                boolean volumeExists = podSpec.getVolumes() != null &&
                        podSpec.getVolumes().stream()
                                .anyMatch(v -> volumeName.equals(v.getName()));

                if (!volumeExists) {
                    // Add volume
                    if (podSpec.getVolumes() == null) {
                        podSpec.setVolumes(new java.util.ArrayList<>());
                    }
                    podSpec.getVolumes().add(new VolumeBuilder()
                            .withName(volumeName)
                            .withConfigMap(new ConfigMapVolumeSourceBuilder()
                                    .withName(configMapName)
                                    .build())
                            .build());

                    // Add volume mount
                    if (container.getVolumeMounts() == null) {
                        container.setVolumeMounts(new java.util.ArrayList<>());
                    }
                    container.getVolumeMounts().add(new VolumeMountBuilder()
                            .withName(volumeName)
                            .withMountPath(mountPath)
                            .withReadOnly(true)
                            .build());
                }
            }

            // 3. Trigger rolling restart by updating annotation
            Map<String, String> annotations = deployment.getSpec().getTemplate()
                    .getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new java.util.HashMap<>();
                deployment.getSpec().getTemplate().getMetadata().setAnnotations(annotations);
            }
            annotations.put("kubefn.com/last-function-update", Instant.now().toString());
            annotations.put("kubefn.com/function-" + name, spec.getClassName());

            // Apply the updated deployment
            client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(groupName)
                    .patch(deployment);

            log.info("Function '{}' deployed to group '{}' — deployment updated",
                    name, groupName);

            // 4. Update status
            updateStatus(resource, true, null);
            return UpdateControl.patchStatus(resource);

        } catch (Exception e) {
            log.error("Failed to reconcile function '{}': {}", name, e.getMessage(), e);
            updateStatus(resource, false, e.getMessage());
            return UpdateControl.patchStatus(resource);
        }
    }

    private void updateStatus(KubeFnFunction resource, boolean loaded, String error) {
        KubeFnFunctionStatus status = resource.getStatus();
        if (status == null) {
            status = new KubeFnFunctionStatus();
            resource.setStatus(status);
        }
        status.setLoaded(loaded);
        status.setLastDeployed(Instant.now().toString());
        if (error != null) {
            status.getErrors().add(error);
            // Keep only last 10 errors
            if (status.getErrors().size() > 10) {
                status.setErrors(status.getErrors().subList(
                        status.getErrors().size() - 10, status.getErrors().size()));
            }
        }
    }
}
